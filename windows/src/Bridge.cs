using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;

namespace PdaNetNext
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
        public BridgeMode ActiveMode { get; private set; }
        public AdbDevice ActiveDevice { get; private set; }

        readonly int port = AppSettings.RelayPort;
        readonly JobObject job = new JobObject();
        readonly AutoResetEvent wake = new AutoResetEvent(false);
        readonly Thread worker;
        volatile bool disposed;
        volatile bool wantConnected;
        volatile BridgeMode wantMode;
        volatile string wantSerial;
        volatile bool restartRequested;

        Process singBox;
        bool connected;
        int statusFailures;
        string lastDeviceKey = "";
        DateTime lastAppLaunch = DateTime.MinValue;
        DateTime retryAfter = DateTime.MinValue;

        static string BinDir => Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "bin");
        static string SingBoxExe => Path.Combine(BinDir, "sing-box.exe");
        static string PhoneApk => Path.Combine(BinDir, "PdaNet-Next.apk");
        static string DataDir => Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "PdaNet-Next");

        public Bridge()
        {
            worker = new Thread(Loop) { IsBackground = true, Name = "bridge" };
        }

        public void Start(bool connectNow)
        {
            if (worker.IsAlive) return;
            wantConnected = connectNow;
            wantMode = AppSettings.Mode;
            worker.Start();
        }

        public void RequestConnect(BridgeMode mode, string serial)
        {
            wantMode = mode;
            wantSerial = serial;
            wantConnected = true;
            retryAfter = DateTime.MinValue;
            bool otherPhone = serial != null && ActiveDevice != null && serial != ActiveDevice.Serial;
            if (connected && (mode != ActiveMode || otherPhone)) restartRequested = true;
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
            job.Dispose();
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
                    Teardown("switching mode", BridgeState.Connecting);
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
                    SetState(BridgeState.Waiting, "Open PdaNet-Next on the phone and tap Start (unlock the phone first)");
                    return;
                }
            }

            var mode = wantMode;
            if (mode == BridgeMode.WholePc)
            {
                if (!StartSingBox()) return;
            }
            else
            {
                SystemProxy.Apply(port);
            }

            AppSettings.ActiveSession = mode.ToString();
            ActiveMode = mode;
            ActiveDevice = device;
            connected = true;
            statusFailures = 0;
            StatusUpdated?.Invoke(status);
            SetState(BridgeState.Connected, mode == BridgeMode.WholePc
                ? "Whole PC is online through " + device.DisplayName
                : "Browsers are online through " + device.DisplayName);
        }

        bool EnsurePhoneApp(AdbDevice device)
        {
            var installed = Adb.Run(device.Serial, "shell pm path " + Adb.PhonePackage);
            if (!installed.Output.Contains("package:"))
            {
                if (!File.Exists(PhoneApk))
                {
                    SetState(BridgeState.Error, "Install the PdaNet-Next app on the phone first");
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

        bool StartSingBox()
        {
            if (!File.Exists(SingBoxExe))
            {
                SetState(BridgeState.Error, "bin\\sing-box.exe is missing. Re-extract the PdaNet-Next zip.");
                return false;
            }
            Directory.CreateDirectory(DataDir);
            string config = Path.Combine(DataDir, "sing-box.json");
            File.WriteAllText(config, SingBoxConfig.Build(port, AppSettings.KeepLanDirect), new UTF8Encoding(false));

            var psi = new ProcessStartInfo(SingBoxExe, "run -c \"" + config + "\" -D \"" + DataDir + "\"")
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            var p = new Process { StartInfo = psi, EnableRaisingEvents = true };
            p.OutputDataReceived += (s, e) => { if (e.Data != null) Emit("sing-box: " + e.Data); };
            p.ErrorDataReceived += (s, e) => { if (e.Data != null) Emit("sing-box: " + e.Data); };
            p.Exited += (s, e) => wake.Set();
            p.Start();
            try { job.Add(p); } catch (Exception e) { Emit("job object: " + e.Message); }
            p.BeginOutputReadLine();
            p.BeginErrorReadLine();

            // sing-box exits within a second when the config or the adapter is rejected.
            if (p.WaitForExit(2500))
            {
                SetState(BridgeState.Error, "The virtual adapter could not start (see log). Is another VPN or TUN app running?");
                return false;
            }
            singBox = p;
            return true;
        }

        void CheckHealth(List<AdbDevice> devices)
        {
            if (singBox != null && singBox.HasExited)
            {
                Emit("sing-box exited with code " + singBox.ExitCode + ", restarting the bridge");
                Teardown("adapter stopped", BridgeState.Connecting);
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
                SetState(BridgeState.Waiting, "The phone app stopped. Open PdaNet-Next on the phone and tap Start.");
            }
        }

        void Teardown(string reason, BridgeState next)
        {
            bool wasConnected = connected;
            connected = false;
            if (singBox != null)
            {
                try { if (!singBox.HasExited) { singBox.Kill(); singBox.WaitForExit(5000); } } catch { }
                singBox.Dispose();
                singBox = null;
            }
            try { SystemProxy.Restore(); } catch (Exception e) { Emit("proxy restore: " + e.Message); }
            if (ActiveDevice != null) Adb.Run(ActiveDevice.Serial, "forward --remove tcp:" + port, 5000);
            // Drop cached fake-IP answers so apps resolve normally again.
            ProcessRunner.Run("ipconfig.exe", "/flushdns", 5000);
            AppSettings.ActiveSession = null;
            if (wasConnected) Emit("Disconnected (" + reason + ")");
            SetState(next, next == BridgeState.Disconnected ? "Not connected" : "Reconnecting…");
        }

        void RepairAfterCrash()
        {
            string stale = AppSettings.ActiveSession;
            if (stale == null && !SystemProxy.HasBackup) return;
            Emit("Previous session did not close cleanly; restoring network settings");
            foreach (var p in Process.GetProcessesByName("sing-box"))
            {
                try
                {
                    if (string.Equals(p.MainModule.FileName, SingBoxExe, StringComparison.OrdinalIgnoreCase)) p.Kill();
                }
                catch { }
            }
            try { SystemProxy.Restore(); } catch (Exception e) { Emit("proxy restore: " + e.Message); }
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
                if (s != null) return s;
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

        void Emit(string line) => Log?.Invoke(DateTime.Now.ToString("HH:mm:ss") + "  " + line);

        static string LastLine(string s) => s.Trim().Split('\n').LastOrDefault()?.Trim() ?? "";
    }
}
