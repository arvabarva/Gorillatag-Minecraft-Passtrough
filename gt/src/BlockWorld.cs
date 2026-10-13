using System.Collections.Generic;
using UnityEngine;

namespace GorillaPassthrough
{
    /// <summary>
    /// Where the Minecraft world sits in Gorilla Tag, and the invisible colliders the gorilla climbs on. Nothing here is
    /// drawn: the picture of the world is Minecraft's own (see <see cref="PassthroughView"/>).
    /// <para>
    /// Minecraft x, y, z (z = south) maps to Unity x, y, -z, far above Gorilla Tag's own maps, around an anchor so
    /// Unity's floats stay precise wherever you are in the Minecraft world. 1 block = 1 metre.
    /// </para>
    /// </summary>
    internal sealed class BlockWorld
    {
        private static readonly Vector3 Base = new Vector3(0f, 3000f, 0f);

        private sealed class Section
        {
            public int X, Y, Z;
            public bool[] Full;
            public Dictionary<int, byte[]> Partial;
            public GameObject Object;
        }

        private readonly Dictionary<long, Section> _sections = new Dictionary<long, Section>();
        private readonly HashSet<Collider> _colliders = new HashSet<Collider>();
        private readonly Transform _root;
        private readonly int _layer;
        private long _anchorX, _anchorZ;
        private bool _anchored;

        public BlockWorld(int layer)
        {
            _layer = layer;
            var root = new GameObject("Gorilla Passthrough colliders");
            Object.DontDestroyOnLoad(root);
            _root = root.transform;
        }

        public bool Anchored => _anchored;
        public int SectionCount => _sections.Count;

        // ---- coordinates ----

        public Vector3 ToUnity(double x, double y, double z)
        {
            return Base + new Vector3((float)(x - _anchorX), (float)y, (float)-(z - _anchorZ));
        }

        public void ToMinecraft(Vector3 unity, out double x, out double y, out double z)
        {
            Vector3 local = unity - Base;
            x = _anchorX + (double)local.x;
            y = local.y;
            z = _anchorZ - (double)local.z;
        }

        public Vector3 ToMinecraftLocal(Vector3 unity)
        {
            ToMinecraft(unity, out double x, out double y, out double z);
            return new Vector3((float)x, (float)y, (float)z);
        }

        /// <summary>Re-centres Unity's coordinates on a Minecraft position (on teleports far from the old anchor).</summary>
        public void AnchorAt(double x, double z)
        {
            long ax = (long)System.Math.Floor(x / 16.0) * 16;
            long az = (long)System.Math.Floor(z / 16.0) * 16;
            if (_anchored && System.Math.Abs(ax - _anchorX) < 2048 && System.Math.Abs(az - _anchorZ) < 2048)
            {
                return;
            }
            _anchorX = ax;
            _anchorZ = az;
            _anchored = true;
            foreach (Section section in _sections.Values)
            {
                Place(section);
            }
            Plugin.Log.LogInfo("Gorilla Passthrough: Minecraft block (" + ax + ", 0, " + az + ") is at Unity " + Base);
        }

        // ---- sections from Minecraft ----

        public void Clear()
        {
            foreach (Section section in _sections.Values)
            {
                Destroy(section);
            }
            _sections.Clear();
        }

        public void Unload(int sx, int sy, int sz)
        {
            long key = Key(sx, sy, sz);
            if (_sections.TryGetValue(key, out Section section))
            {
                Destroy(section);
                _sections.Remove(key);
            }
        }

        public void ReadSection(JavaReader reader)
        {
            int sx = reader.Int(), sy = reader.Int(), sz = reader.Int();
            byte[] mask = reader.Bytes(512);
            int partialCount = (ushort)reader.Short();
            var full = new bool[4096];
            for (int i = 0; i < 4096; i++)
            {
                full[i] = (mask[i >> 3] & (1 << (i & 7))) != 0;
            }
            var partial = new Dictionary<int, byte[]>(partialCount);
            for (int i = 0; i < partialCount; i++)
            {
                int cell = (ushort)reader.Short();
                partial[cell] = reader.Bytes(6);
            }

            long key = Key(sx, sy, sz);
            if (_sections.TryGetValue(key, out Section old))
            {
                Destroy(old);
            }
            var section = new Section { X = sx, Y = sy, Z = sz, Full = full, Partial = partial };
            _sections[key] = section;
            Build(section);
        }

        public bool HasSection(int sx, int sy, int sz) => _sections.ContainsKey(Key(sx, sy, sz));

        public bool IsOurCollider(Collider collider) => collider != null && _colliders.Contains(collider);

        /// <summary>The collision box of the block at a Minecraft position (in that block's 0..1 space), if solid.</summary>
        public bool TryGetBox(int x, int y, int z, out Vector3 min, out Vector3 max)
        {
            min = Vector3.zero;
            max = Vector3.one;
            int sx = x >> 4, sy = y >> 4, sz = z >> 4;
            if (!_sections.TryGetValue(Key(sx, sy, sz), out Section section))
            {
                return false;
            }
            int cell = (((y & 15) * 16) + (z & 15)) * 16 + (x & 15);
            if (section.Full[cell])
            {
                return true;
            }
            if (section.Partial.TryGetValue(cell, out byte[] box))
            {
                min = new Vector3(box[0], box[1], box[2]) / 16f;
                max = new Vector3(box[3], box[4], box[5]) / 16f;
                return true;
            }
            return false;
        }

        private void Build(Section section)
        {
            var go = new GameObject("section " + section.X + " " + section.Y + " " + section.Z) { layer = _layer };
            go.transform.SetParent(_root, false);
            section.Object = go;
            Place(section);

            // merge solid blocks into as few boxes as possible: runs along x, then rows along z, then layers along y
            var used = new bool[4096];
            bool[] full = section.Full;
            for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++)
            {
                int start = Cell(x, y, z);
                if (!full[start] || used[start])
                {
                    continue;
                }
                int w = 1;
                while (x + w < 16 && full[Cell(x + w, y, z)] && !used[Cell(x + w, y, z)]) w++;
                int d = 1;
                while (z + d < 16 && Row(full, used, x, w, y, z + d)) d++;
                int h = 1;
                while (y + h < 16 && Plane(full, used, x, w, y + h, z, d)) h++;
                for (int yy = y; yy < y + h; yy++)
                for (int zz = z; zz < z + d; zz++)
                for (int xx = x; xx < x + w; xx++)
                {
                    used[Cell(xx, yy, zz)] = true;
                }
                AddBox(go, new Vector3(x, y, z), new Vector3(x + w, y + h, z + d));
            }
            foreach (KeyValuePair<int, byte[]> pair in section.Partial)
            {
                int cell = pair.Key;
                int x = cell & 15, z = (cell >> 4) & 15, y = cell >> 8;
                byte[] b = pair.Value;
                AddBox(go, new Vector3(x + b[0] / 16f, y + b[1] / 16f, z + b[2] / 16f), new Vector3(x + b[3] / 16f, y + b[4] / 16f, z + b[5] / 16f));
            }
        }

        /// <summary>A box given in the section's Minecraft-local coordinates; the section object flips z for Unity.</summary>
        private void AddBox(GameObject go, Vector3 min, Vector3 max)
        {
            Vector3 size = max - min;
            if (size.x <= 0f || size.y <= 0f || size.z <= 0f)
            {
                return;
            }
            var box = go.AddComponent<BoxCollider>();
            box.center = new Vector3((min.x + max.x) * 0.5f, (min.y + max.y) * 0.5f, -(min.z + max.z) * 0.5f);
            box.size = size;
            _colliders.Add(box);
        }

        private void Place(Section section)
        {
            if (section.Object != null)
            {
                section.Object.transform.position = ToUnity(section.X * 16, section.Y * 16, section.Z * 16);
            }
        }

        private void Destroy(Section section)
        {
            if (section.Object == null)
            {
                return;
            }
            foreach (Collider collider in section.Object.GetComponents<Collider>())
            {
                _colliders.Remove(collider);
            }
            Object.Destroy(section.Object);
            section.Object = null;
        }

        private static bool Row(bool[] full, bool[] used, int x, int w, int y, int z)
        {
            for (int i = x; i < x + w; i++)
            {
                int c = Cell(i, y, z);
                if (!full[c] || used[c]) return false;
            }
            return true;
        }

        private static bool Plane(bool[] full, bool[] used, int x, int w, int y, int z, int d)
        {
            for (int k = z; k < z + d; k++)
            {
                if (!Row(full, used, x, w, y, k)) return false;
            }
            return true;
        }

        private static int Cell(int x, int y, int z) => (y * 16 + z) * 16 + x;

        private static long Key(int sx, int sy, int sz) => ((long)(sx & 0x3FFFFF) << 42) | ((long)(sz & 0x3FFFFF) << 20) | (long)(sy & 0xFFFFF);
    }
}
