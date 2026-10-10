using System;
using System.IO;
using System.Net;
using System.Text;

namespace PdaNetNext
{
    /// <summary>Addressing the phone hands out for the USB network (see core/.../router/RouterLink.kt).</summary>
    public sealed class RouterHello
    {
        public IPAddress Gateway4, Client4, Gateway6, Client6;
        public int Prefix4, Prefix6, Mtu;

        public string Mask4
        {
            get
            {
                uint mask = Prefix4 == 0 ? 0 : uint.MaxValue << (32 - Prefix4);
                return new IPAddress(new[] { (byte)(mask >> 24), (byte)(mask >> 16), (byte)(mask >> 8), (byte)mask }).ToString();
            }
        }
    }

    /// <summary>
    /// Wire format of the router link, platform-neutral so it is tested on any OS:
    /// "PDNR" + version, the phone's hello, then frames of u16 big-endian length + one IP packet.
    /// </summary>
    public static class RouterLinkProtocol
    {
        public const int Version = 1;
        const int HelloLength = 5 + 4 + 4 + 1 + 16 + 16 + 1 + 2;

        public static RouterHello Handshake(Stream stream)
        {
            var request = Encoding.ASCII.GetBytes("PDNR");
            stream.Write(request, 0, 4);
            stream.WriteByte(Version);
            stream.Flush();
            var h = new byte[HelloLength];
            ReadExactly(stream, h, HelloLength);
            if (Encoding.ASCII.GetString(h, 0, 4) != "PDNR" || h[4] != Version)
                throw new IOException("The phone app does not speak router mode. Update the phone app.");
            return new RouterHello
            {
                Gateway4 = new IPAddress(Slice(h, 5, 4)),
                Client4 = new IPAddress(Slice(h, 9, 4)),
                Prefix4 = h[13],
                Gateway6 = new IPAddress(Slice(h, 14, 16)),
                Client6 = new IPAddress(Slice(h, 30, 16)),
                Prefix6 = h[46],
                Mtu = (h[47] << 8) | h[48],
            };
        }

        /// <summary>Reads one frame into <paramref name="buffer"/>; returns its length (0 = keep-alive).</summary>
        public static int ReadFrame(Stream stream, byte[] buffer)
        {
            ReadExactly(stream, buffer, 2);
            int length = (buffer[0] << 8) | buffer[1];
            if (length > 0) ReadExactly(stream, buffer, length);
            return length;
        }

        public static void WriteFrameHeader(Stream stream, int length)
        {
            stream.WriteByte((byte)(length >> 8));
            stream.WriteByte((byte)length);
        }

        public static void ReadExactly(Stream stream, byte[] buffer, int count)
        {
            int read = 0;
            while (read < count)
            {
                int n = stream.Read(buffer, read, count - read);
                if (n <= 0) throw new EndOfStreamException("USB link closed");
                read += n;
            }
        }

        static byte[] Slice(byte[] b, int offset, int count)
        {
            var s = new byte[count];
            Array.Copy(b, offset, s, 0, count);
            return s;
        }
    }
}
