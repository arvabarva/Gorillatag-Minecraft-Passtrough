using System;
using System.IO;
using System.Text;

namespace GorillaPassthrough
{
    /// <summary>The passthrough link. Keep in sync with mc/src/main/java/com/gorillapass/client/Protocol.java.</summary>
    internal static class Protocol
    {
        public const int Version = 7;
        public const int Port = 25600;

        public const byte S_Hello = 0x01;
        public const byte S_Section = 0x02;
        public const byte S_UnloadSection = 0x03;
        public const byte S_Clear = 0x04;
        public const byte S_Teleport = 0x05;
        public const byte S_Entities = 0x06;
        public const byte S_Status = 0x07;
        public const byte S_Screen = 0x08;
        public const byte S_Held = 0x09;
        public const byte S_Armor = 0x0A;
        public const byte S_RemoteRig = 0x0B;
        public const byte S_RemoteEquipment = 0x0C;
        public const byte S_RemoteGone = 0x0D;

        public const byte C_Hello = 0x81;
        public const byte C_Pose = 0x82;
        public const byte C_Mine = 0x83;
        public const byte C_Punch = 0x84;
        public const byte C_UseOn = 0x85;
        public const byte C_UseItem = 0x86;
        public const byte C_Attack = 0x87;
        public const byte C_Slot = 0x88;
        public const byte C_Focus = 0x89;
        public const byte C_Drop = 0x8A;
        public const byte C_Inventory = 0x8B;
        public const byte C_Pointer = 0x8C;
        public const byte C_Rig = 0x8D;

        public const int PoseOnGround = 1;

        // shared memory (see FrameExporter.java)
        public const string SharedMemoryName = @"Local\GorillaPassthroughFrames";
        public const int Magic = 0x47505431;
        public const int MaxGrid = 320 * 320;
        public const int MaxVertices = MaxGrid * 5;
        public const int MaxIndices = MaxGrid * 18;
        public const int VertexBytes = 24;
        public const int MaxWidth = 2560;
        public const int MaxHeight = 2560;

        public const int H_Magic = 0;
        public const int H_Version = 4;
        public const int H_SlotBytes = 8;
        public const int H_Latest = 12;
        public const int H_Reader = 16;
        public const int H_Frame = 20;
        public const int H_PanelOffset = 24;
        public const int HeaderBytes = 256;

        public const int S_Seq = 0;
        public const int S_PoseId = 8;
        public const int S_Width = 12;
        public const int S_Height = 16;
        public const int S_GridW = 20;
        public const int S_GridH = 24;
        public const int S_TanH = 28;
        public const int S_TanV = 32;
        public const int S_SkyTop = 36;
        public const int S_SkyHorizon = 48;
        public const int S_VertexCount = 60;
        public const int S_OpaqueIndices = 64;
        public const int S_BackIndices = 68;
        public const int S_FrontIndices = 72;
        public const int S_BgWidth = 76;
        public const int S_BgHeight = 80;
        public const int S_VertexData = 128;
        public const int S_IndexData = S_VertexData + MaxVertices * VertexBytes;
        public const int S_ColorData = S_IndexData + MaxIndices * 4;
        /// <summary>The background picture (see DepthMesh.background in the Minecraft mod).</summary>
        public const int S_BgData = S_ColorData + MaxWidth * MaxHeight * 4;

        public const int P_Seq = 0;
        public const int P_Width = 8;
        public const int P_Height = 12;
        public const int P_Data = 16;
    }

    internal struct Message
    {
        public byte Type;
        public byte[] Payload;
    }

    /// <summary>Big-endian reader matching Java's DataInputStream.</summary>
    internal sealed class JavaReader
    {
        private readonly byte[] _data;
        private int _pos;

        public JavaReader(byte[] data)
        {
            _data = data;
        }

        public int Remaining => _data.Length - _pos;
        public byte Byte() => _data[_pos++];
        public short Short() { short v = (short)((_data[_pos] << 8) | _data[_pos + 1]); _pos += 2; return v; }
        public int Int() { int v = (_data[_pos] << 24) | (_data[_pos + 1] << 16) | (_data[_pos + 2] << 8) | _data[_pos + 3]; _pos += 4; return v; }
        public long Long() { long hi = (uint)Int(); long lo = (uint)Int(); return (hi << 32) | lo; }
        public float Float() => BitConverter.ToSingle(BitConverter.GetBytes(Int()), 0);
        public double Double() => BitConverter.Int64BitsToDouble(Long());

        public string Utf()
        {
            int length = (ushort)Short();
            string s = Encoding.UTF8.GetString(_data, _pos, length);
            _pos += length;
            return s;
        }

        public byte[] Bytes(int count)
        {
            var result = new byte[count];
            Buffer.BlockCopy(_data, _pos, result, 0, count);
            _pos += count;
            return result;
        }
    }

    /// <summary>Big-endian writer matching Java's DataOutputStream.</summary>
    internal sealed class JavaWriter
    {
        private readonly MemoryStream _stream = new MemoryStream(64);

        public JavaWriter Byte(int v) { _stream.WriteByte((byte)v); return this; }
        public JavaWriter Short(int v) { Byte(v >> 8); Byte(v); return this; }
        public JavaWriter Int(int v) { Byte(v >> 24); Byte(v >> 16); Byte(v >> 8); Byte(v); return this; }
        public JavaWriter Long(long v) { Int((int)(v >> 32)); Int((int)v); return this; }
        public JavaWriter Float(float v) { Int(BitConverter.ToInt32(BitConverter.GetBytes(v), 0)); return this; }
        public JavaWriter Double(double v) { Long(BitConverter.DoubleToInt64Bits(v)); return this; }
        public byte[] ToArray() => _stream.ToArray();
    }
}
