using System;
using System.Runtime.InteropServices;
using System.Threading;

namespace GorillaPassthrough
{
    /// <summary>Opens the shared memory Minecraft's passthrough mod writes its frames into.</summary>
    internal sealed unsafe class FrameReader
    {
        private const uint FileMapAllAccess = 0xF001F;

        [DllImport("kernel32", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern IntPtr OpenFileMappingW(uint desiredAccess, bool inheritHandle, string name);

        [DllImport("kernel32", SetLastError = true)]
        private static extern IntPtr MapViewOfFile(IntPtr mapping, uint desiredAccess, uint offsetHigh, uint offsetLow, UIntPtr bytes);

        private byte* _base;
        private int _lastFrame = -1;

        public bool IsOpen => _base != null;

        public bool TryOpen()
        {
            if (_base != null)
            {
                return true;
            }
            IntPtr mapping = OpenFileMappingW(FileMapAllAccess, false, Protocol.SharedMemoryName);
            if (mapping == IntPtr.Zero)
            {
                return false;
            }
            IntPtr view = MapViewOfFile(mapping, FileMapAllAccess, 0, 0, UIntPtr.Zero);
            if (view == IntPtr.Zero)
            {
                Plugin.Log.LogError("Gorilla Passthrough: MapViewOfFile failed (" + Marshal.GetLastWin32Error() + ")");
                return false;
            }
            var b = (byte*)view;
            if (ReadInt(b, Protocol.H_Magic) != Protocol.Magic || ReadInt(b, Protocol.H_Version) != Protocol.Version)
            {
                Plugin.Log.LogError("Gorilla Passthrough: the Minecraft mod's frame format doesn't match this plugin; update both");
                return false;
            }
            _base = b;
            Plugin.Log.LogInfo("Gorilla Passthrough: reading Minecraft's frames from " + Protocol.SharedMemoryName);
            return true;
        }

        /// <summary>
        /// The newest finished frame, if there is one we haven't seen. The slot stays reserved for us (Minecraft won't
        /// write into it) until the next call.
        /// </summary>
        public byte* TryTakeFrame()
        {
            if (_base == null)
            {
                return null;
            }
            int frame = ReadInt(_base, Protocol.H_Frame);
            int latest = ReadInt(_base, Protocol.H_Latest);
            if (frame == _lastFrame || latest < 0)
            {
                return null;
            }
            // reserve the slot; Minecraft never writes the slot named here (if it picked this slot a moment before
            // seeing the reservation, the sequence number check in PassthroughView catches it)
            WriteInt(_base, Protocol.H_Reader, latest);
            Thread.MemoryBarrier();
            byte* slot = _base + Protocol.HeaderBytes + (long)latest * ReadInt(_base, Protocol.H_SlotBytes);
            if ((Volatile.Read(ref *(long*)(slot + Protocol.S_Seq)) & 1L) != 0)
            {
                return null;
            }
            _lastFrame = frame;
            return slot;
        }

        public static long Seq(byte* block) => Volatile.Read(ref *(long*)block);

        public byte* Panel => _base == null ? null : _base + ReadInt(_base, Protocol.H_PanelOffset);

        public static int ReadInt(byte* b, int offset) => Volatile.Read(ref *(int*)(b + offset));

        public static float ReadFloat(byte* b, int offset) => *(float*)(b + offset);

        private static void WriteInt(byte* b, int offset, int value) => Volatile.Write(ref *(int*)(b + offset), value);
    }
}
