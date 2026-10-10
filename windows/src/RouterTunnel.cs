using System;
using System.IO;
using System.Net.Sockets;
using System.Threading;

namespace PdaNetNext
{
    /// <summary>
    /// "Phone as a router": a Wintun adapter whose packets go, unchanged, over the adb-forwarded
    /// TCP stream to the phone's router, and back. Two pump threads, one per direction.
    /// </summary>
    public sealed class RouterTunnel : IDisposable
    {
        public const string AdapterName = "PdaNet-Next";

        readonly Action<string> log;
        TcpClient client;
        BufferedStream output;
        readonly object writeLock = new object();
        Wintun tun;
        Thread up, down, keepAlive;
        volatile bool running;

        public RouterHello Hello { get; private set; }
        public bool IsAlive => running;
        public string StopReason { get; private set; }

        public RouterTunnel(Action<string> log) { this.log = log; }

        public void Start(int port, string binDir)
        {
            client = new TcpClient { NoDelay = true, ReceiveBufferSize = 1 << 20, SendBufferSize = 1 << 20 };
            client.Connect("127.0.0.1", port);
            var stream = client.GetStream();
            stream.ReadTimeout = 10000;
            Hello = RouterLinkProtocol.Handshake(stream);
            // The phone sends a keep-alive every 15 s, so 45 s of silence means the link is gone.
            stream.ReadTimeout = 45000;
            output = new BufferedStream(stream, 256 * 1024);

            Wintun.Load(binDir);
            tun = new Wintun(AdapterName);
            NetConfig.Apply(AdapterName, Hello, log);

            running = true;
            up = new Thread(PcToPhone) { IsBackground = true, Name = "tun->phone" };
            down = new Thread(PhoneToPc) { IsBackground = true, Name = "phone->tun" };
            keepAlive = new Thread(KeepAlive) { IsBackground = true, Name = "link keep-alive" };
            up.Start();
            down.Start();
            keepAlive.Start();
        }

        void PcToPhone()
        {
            var buffer = new byte[65535];
            try
            {
                while (running)
                {
                    int length = tun.Receive(buffer, 0);
                    if (length < 0) { Stop("adapter closed"); return; }
                    if (length == 0)
                    {
                        tun.WaitForPackets(250);
                        continue;
                    }
                    lock (writeLock)
                    {
                        RouterLinkProtocol.WriteFrameHeader(output, length);
                        output.Write(buffer, 0, length);
                        // Batch packets that are already waiting into one flush.
                        int more;
                        while ((more = tun.Receive(buffer, 0)) > 0)
                        {
                            RouterLinkProtocol.WriteFrameHeader(output, more);
                            output.Write(buffer, 0, more);
                        }
                        output.Flush();
                        if (more < 0) { Stop("adapter closed"); return; }
                    }
                }
            }
            catch (Exception e) { Stop("send to phone failed: " + e.Message); }
        }

        void PhoneToPc()
        {
            var buffer = new byte[65535];
            var stream = client.GetStream();
            try
            {
                while (running)
                {
                    int length = RouterLinkProtocol.ReadFrame(stream, buffer);
                    if (length > 0) tun.Send(buffer, length);
                }
            }
            catch (Exception e) { Stop(e is IOException ? "USB link closed" : "receive failed: " + e.Message); }
        }

        void KeepAlive()
        {
            try
            {
                while (running)
                {
                    Thread.Sleep(15000);
                    if (!running) return;
                    lock (writeLock)
                    {
                        RouterLinkProtocol.WriteFrameHeader(output, 0);
                        output.Flush();
                    }
                }
            }
            catch (Exception e) { Stop("keep-alive failed: " + e.Message); }
        }

        void Stop(string reason)
        {
            if (!running) return;
            running = false;
            StopReason = reason;
            log("Router link stopped: " + reason);
            try { client?.Close(); } catch { }
        }

        public void Dispose()
        {
            Stop("disconnected");
            try { client?.Close(); } catch { }
            // Let the pumps leave Wintun calls before the session is ended.
            up?.Join(2000);
            down?.Join(2000);
            NetConfig.Remove(log);
            tun?.Dispose();
            tun = null;
        }
    }
}
