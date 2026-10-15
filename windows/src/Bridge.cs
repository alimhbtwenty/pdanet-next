using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;

namespace PdaNext
{
    public enum BridgeState { Disconnected, Waiting, Connecting, Connected, Error }

    /// <summary>
    /// Owns the connection. All work happens on one background thread, so connect, disconnect,
    /// cable-unplug handling and crash recovery never race each other. The UI only sets intents.
    /// </summary>
    public sealed class Bridge : IDisposable
    {
        public event Action<BridgeState, string> StateChanged;
        public event Action<string> Log;
        public event Action<PhoneStatus> StatusUpdated;
        public event Action<List<AdbDevice>> DevicesChanged;

        public BridgeState State { get; private set; } = BridgeState.Disconnected;
        /// <summary>The phone's address on the USB network while connected.</summary>
        public string RouterAddress => tunnel?.Hello?.Gateway4?.ToString();
        public AdbDevice ActiveDevice { get; private set; }
        public int Port => port;
        public PhoneStatus LastStatus { get; private set; }

        readonly int port = AppSettings.RelayPort;
        readonly AutoResetEvent wake = new AutoResetEvent(false);
        readonly Thread worker;
        volatile bool disposed;
        volatile bool wantConnected;
        volatile string wantSerial;
        volatile bool restartRequested;

        RouterTunnel tunnel;
        bool connected;
        int statusFailures;
        string lastDeviceKey = "";
        DateTime lastAppLaunch = DateTime.MinValue;
        DateTime retryAfter = DateTime.MinValue;

        static string BinDir => Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "bin");
        static string PhoneApk => Path.Combine(BinDir, "PdaNext.apk");
        static string DataDir => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PdaNext");

        public Bridge()
        {
            worker = new Thread(Loop) { IsBackground = true, Name = "bridge" };
        }

        public void Start(bool connectNow)
        {
            if (worker.IsAlive) return;
            wantConnected = connectNow;
            worker.Start();
        }

        public void RequestConnect(string serial)
        {
            wantSerial = serial;
            wantConnected = true;
            retryAfter = DateTime.MinValue;
            bool otherPhone = serial != null && ActiveDevice != null && serial != ActiveDevice.Serial;
            if (connected && otherPhone) restartRequested = true;
            wake.Set();
        }

        public void RequestDisconnect()
        {
            wantConnected = false;
            wake.Set();
        }

        public bool WantsConnection => wantConnected;

        /// <summary>Synchronous teardown for app exit.</summary>
        public void Dispose()
        {
            disposed = true;
            wake.Set();
            worker.Join(8000);
            Teardown("app closed", BridgeState.Disconnected);
        }

        void Loop()
        {
            RepairAfterCrash();
            while (!disposed)
            {
                try
                {
                    Tick();
                }
                catch (Exception e)
                {
                    Emit("Unexpected error: " + e.Message);
                }
                wake.WaitOne(connected ? 2000 : 1500);
            }
        }

        void Tick()
        {
            var devices = Adb.Devices();
            string key = string.Join("|", devices.Select(d => d.Serial + d.State));
            if (key != lastDeviceKey)
            {
                lastDeviceKey = key;
                DevicesChanged?.Invoke(devices);
            }

            if (!wantConnected)
            {
                if (connected) Teardown("disconnected", BridgeState.Disconnected);
                else if (State != BridgeState.Disconnected) SetState(BridgeState.Disconnected, "Not connected");
                return;
            }

            if (connected)
            {
                if (restartRequested)
                {
                    restartRequested = false;
                    Teardown("switching phone", BridgeState.Connecting);
                    return;
                }
                CheckHealth(devices);
                return;
            }

            TryConnect(devices);
        }

        void TryConnect(List<AdbDevice> devices)
        {
            // After a hard error, retry slowly unless the user presses Connect again.
            if (DateTime.UtcNow < retryAfter) return;
            var device = devices.FirstOrDefault(d => d.Ready && d.Serial == wantSerial) ?? devices.FirstOrDefault(d => d.Ready);
            if (device == null)
            {
                if (devices.Any(d => d.State == "unauthorized"))
                    SetState(BridgeState.Waiting, "Tap \"Allow\" on the USB debugging prompt on your phone");
                else if (devices.Any())
                    SetState(BridgeState.Waiting, "Phone is " + devices[0].State + ". Re-plug the cable.");
                else
                    SetState(BridgeState.Waiting, "Plug in your phone with USB debugging on");
                return;
            }

            SetState(BridgeState.Connecting, "Connecting to " + device.DisplayName + "…");
            var fwd = Adb.Run(device.Serial, "forward tcp:" + port + " tcp:" + port);
            if (!fwd.Ok)
            {
                SetState(BridgeState.Error, "adb forward failed: " + fwd.Output.Trim());
                return;
            }

            PhoneStatus status = TryStatus();
            if (status == null)
            {
                if (!EnsurePhoneApp(device)) return;
                status = WaitForStatus(TimeSpan.FromSeconds(12));
                if (status == null)
                {
                    SetState(BridgeState.Waiting, "Open PdaNext on the phone and tap Start (unlock the phone first)");
                    return;
                }
            }

            if (!StartTunnel()) return;

            AppSettings.ActiveSession = "router";
            ActiveDevice = device;
            connected = true;
            statusFailures = 0;
            StatusUpdated?.Invoke(status);
            SetState(BridgeState.Connected, "The whole PC is online through " + device.DisplayName + " (router " + tunnel.Hello.Gateway4 + ")");
        }

        bool EnsurePhoneApp(AdbDevice device)
        {
            var installed = Adb.Run(device.Serial, "shell pm path " + Adb.PhonePackage);
            if (!installed.Output.Contains("package:"))
            {
                if (!File.Exists(PhoneApk))
                {
                    SetState(BridgeState.Error, "Install the PdaNext app on the phone first");
                    return false;
                }
                SetState(BridgeState.Connecting, "Installing the phone app…");
                var install = Adb.Run(device.Serial, "install -r \"" + PhoneApk + "\"", 120000);
                Emit("adb install: " + install.Output.Trim());
                if (!install.Ok || !install.Output.Contains("Success"))
                {
                    SetState(BridgeState.Error, "Could not install the phone app: " + LastLine(install.Output));
                    return false;
                }
            }
            if (DateTime.UtcNow - lastAppLaunch > TimeSpan.FromSeconds(20))
            {
                lastAppLaunch = DateTime.UtcNow;
                SetState(BridgeState.Connecting, "Starting the phone app…");
                Adb.Run(device.Serial, "shell am start -n " + Adb.PhonePackage + "/.MainActivity --ez autostart true");
            }
            return true;
        }

        bool StartTunnel()
        {
            var t = new RouterTunnel(Emit);
            try
            {
                t.Start(port, BinDir);
            }
            catch (Exception e)
            {
                t.Dispose();
                SetState(BridgeState.Error, "Could not start the network adapter: " + e.Message);
                return false;
            }
            tunnel = t;
            return true;
        }

        void CheckHealth(List<AdbDevice> devices)
        {
            if (tunnel != null && !tunnel.IsAlive)
            {
                Teardown(tunnel.StopReason ?? "link stopped", BridgeState.Connecting);
                return;
            }

            if (!devices.Any(d => d.Serial == ActiveDevice.Serial && d.Ready))
            {
                // Cable pulled: put the PC back on its normal network right away.
                Teardown("phone unplugged", BridgeState.Waiting);
                SetState(BridgeState.Waiting, "Phone unplugged. Normal networking restored; plug it back in to reconnect.");
                return;
            }

            var status = TryStatus();
            if (status != null)
            {
                LastStatus = status;
                statusFailures = 0;
                StatusUpdated?.Invoke(status);
                return;
            }

            statusFailures++;
            if (statusFailures == 2)
            {
                // The phone app may have restarted: re-create the forward rule.
                Adb.Run(ActiveDevice.Serial, "forward tcp:" + port + " tcp:" + port);
            }
            if (statusFailures >= 4)
            {
                Teardown("phone app stopped answering", BridgeState.Waiting);
                SetState(BridgeState.Waiting, "The phone app stopped. Open PdaNext on the phone and tap Start.");
            }
        }

        void Teardown(string reason, BridgeState next)
        {
            bool wasConnected = connected;
            connected = false;
            if (tunnel != null)
            {
                try { tunnel.Dispose(); } catch (Exception e) { Emit("adapter cleanup: " + e.Message); }
                tunnel = null;
            }
            if (ActiveDevice != null) Adb.Run(ActiveDevice.Serial, "forward --remove tcp:" + port, 5000);
            AppSettings.ActiveSession = null;
            if (wasConnected) Emit("Disconnected (" + reason + ")");
            SetState(next, next == BridgeState.Disconnected ? "Not connected" : "Reconnecting…");
        }

        void RepairAfterCrash()
        {
            string stale = AppSettings.ActiveSession;
            if (stale == null) return;
            Emit("Previous session did not close cleanly; restoring network settings");
            // The adapter itself vanished with the old process; the DNS rule did not.
            try { NetConfig.Remove(Emit); } catch (Exception e) { Emit("DNS rule cleanup: " + e.Message); }
            AppSettings.ActiveSession = null;
        }

        PhoneStatus TryStatus()
        {
            try { return PhoneStatus.Fetch(port); }
            catch { return null; }
        }

        PhoneStatus WaitForStatus(TimeSpan timeout)
        {
            var until = DateTime.UtcNow + timeout;
            while (DateTime.UtcNow < until && wantConnected && !disposed)
            {
                var s = TryStatus();
                if (s != null)
                {
                    LastStatus = s;
                    return s;
                }
                Thread.Sleep(700);
            }
            return null;
        }

        void SetState(BridgeState state, string message)
        {
            State = state;
            if (state == BridgeState.Error) retryAfter = DateTime.UtcNow.AddSeconds(15);
            StateChanged?.Invoke(state, message);
        }

        void Emit(string line)
        {
            string formatted = DateTime.Now.ToString("HH:mm:ss") + "  " + line;
            string redacted = SecretRedactor.Redact(formatted);
            FileLogger.Log(redacted);
            Log?.Invoke(redacted);
        }

        static string LastLine(string s) => s.Trim().Split('\n').LastOrDefault()?.Trim() ?? "";
    }
}
