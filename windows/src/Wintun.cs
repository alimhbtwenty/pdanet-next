using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;

namespace PdaNetNext
{
    /// <summary>
    /// Minimal binding to wintun.dll (https://www.wintun.net), the signed layer-3 adapter driver
    /// used by WireGuard. The adapter is a software device tied to this process: Windows removes it
    /// when the session ends or the process exits, crashes included.
    /// </summary>
    public sealed class Wintun : IDisposable
    {
        public const int ErrorNoMoreItems = 259;
        public const int ErrorHandleEof = 38;

        static readonly Guid AdapterGuid = new Guid("b6a1c5e2-3f4d-4c6b-9a7e-5d2c1f0e8a41");
        static bool loaded;

        IntPtr adapter, session;
        public IntPtr ReadEvent { get; private set; }

        public static void Load(string directory)
        {
            if (loaded) return;
            string path = Path.Combine(directory, "wintun.dll");
            if (!File.Exists(path)) throw new FileNotFoundException("wintun.dll is missing next to the app (bin\\wintun.dll)", path);
            if (LoadLibrary(path) == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error(), "Cannot load wintun.dll");
            loaded = true;
        }

        public Wintun(string name, uint ringCapacity = 0x800000)
        {
            // A stale adapter of the same name (from a crash) would make creation fail.
            var stale = WintunOpenAdapter(name);
            if (stale != IntPtr.Zero) WintunCloseAdapter(stale);

            var guid = AdapterGuid;
            adapter = WintunCreateAdapter(name, "PdaNet-Next", ref guid);
            if (adapter == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error(), "Cannot create the virtual adapter");
            session = WintunStartSession(adapter, ringCapacity);
            if (session == IntPtr.Zero)
            {
                int err = Marshal.GetLastWin32Error();
                WintunCloseAdapter(adapter);
                adapter = IntPtr.Zero;
                throw new Win32Exception(err, "Cannot start the virtual adapter");
            }
            ReadEvent = WintunGetReadWaitEvent(session);
        }

        /// <summary>Copies the next packet from Windows into <paramref name="buffer"/> at <paramref name="offset"/>.
        /// Returns its length, 0 when none is waiting, -1 when the session has ended.</summary>
        public int Receive(byte[] buffer, int offset)
        {
            IntPtr packet = WintunReceivePacket(session, out uint size);
            if (packet == IntPtr.Zero)
            {
                int err = Marshal.GetLastWin32Error();
                return err == ErrorNoMoreItems ? 0 : -1;
            }
            int length = (int)Math.Min(size, (uint)(buffer.Length - offset));
            Marshal.Copy(packet, buffer, offset, length);
            WintunReleaseReceivePacket(session, packet);
            return length;
        }

        /// <summary>Hands a packet to Windows. False if the ring is full (the packet is dropped, as a router would).</summary>
        public bool Send(byte[] buffer, int length)
        {
            IntPtr packet = WintunAllocateSendPacket(session, (uint)length);
            if (packet == IntPtr.Zero) return false;
            Marshal.Copy(buffer, 0, packet, length);
            WintunSendPacket(session, packet);
            return true;
        }

        public bool WaitForPackets(int timeoutMs) => WaitForSingleObject(ReadEvent, (uint)timeoutMs) == 0;

        public void Dispose()
        {
            if (session != IntPtr.Zero) { WintunEndSession(session); session = IntPtr.Zero; }
            if (adapter != IntPtr.Zero) { WintunCloseAdapter(adapter); adapter = IntPtr.Zero; }
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern IntPtr LoadLibrary(string path);

        [DllImport("kernel32.dll")]
        static extern uint WaitForSingleObject(IntPtr handle, uint milliseconds);

        [DllImport("wintun.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern IntPtr WintunCreateAdapter(string name, string tunnelType, ref Guid requestedGuid);

        [DllImport("wintun.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern IntPtr WintunOpenAdapter(string name);

        [DllImport("wintun.dll")]
        static extern void WintunCloseAdapter(IntPtr adapter);

        [DllImport("wintun.dll", SetLastError = true)]
        static extern IntPtr WintunStartSession(IntPtr adapter, uint capacity);

        [DllImport("wintun.dll")]
        static extern void WintunEndSession(IntPtr session);

        [DllImport("wintun.dll")]
        static extern IntPtr WintunGetReadWaitEvent(IntPtr session);

        [DllImport("wintun.dll", SetLastError = true)]
        static extern IntPtr WintunReceivePacket(IntPtr session, out uint packetSize);

        [DllImport("wintun.dll")]
        static extern void WintunReleaseReceivePacket(IntPtr session, IntPtr packet);

        [DllImport("wintun.dll", SetLastError = true)]
        static extern IntPtr WintunAllocateSendPacket(IntPtr session, uint packetSize);

        [DllImport("wintun.dll")]
        static extern void WintunSendPacket(IntPtr session, IntPtr packet);
    }
}
