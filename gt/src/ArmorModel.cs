using System.Collections.Generic;
using GorillaLocomotion;
using UnityEngine;
using UnityEngine.Rendering;

namespace GorillaPassthrough
{
    /// <summary>
    /// Minecraft armour on the Gorilla Tag gorilla: blocky shells in the colours of the pieces you wear in Minecraft
    /// (iron, gold, diamond, netherite, dyed leather...). A gorilla has no legs, so leggings are a band round the hips
    /// and boots become knuckle guards: gorillas walk on their knuckles. The helmet is hidden from your own eyes (it
    /// would sit in front of them); mirrors and the spectator camera show it.
    /// </summary>
    internal sealed class ArmorModel
    {
        private const int Helmet = 0, Chestplate = 1, Leggings = 2, Boots = 3;

        private readonly Material[] _materials = new Material[5];
        private readonly GameObject[] _pieces = new GameObject[5];
        private readonly Color32[][] _palettes = new Color32[4][];
        /// <summary>Minecraft's own armour texture per piece (humanoid layout), when it sent one.</summary>
        private readonly Texture2D[] _textures = new Texture2D[4];
        private readonly int _hiddenFromEyesLayer;
        private readonly bool _ownGorilla;
        private float _light = 1f;

        /// <param name="eyes">your eyes' camera, to hide your own helmet from; null for another player's gorilla</param>
        public ArmorModel(Camera eyes)
        {
            string[] names = { "helmet", "chestplate", "leggings", "left boot", "right boot" };
            for (int i = 0; i < _pieces.Length; i++)
            {
                _materials[i] = Shaders.CreateUnlit("Gorilla Passthrough: armour");
                Shaders.SetTexture(_materials[i], Texture2D.whiteTexture);
                Shaders.MakeCutout(_materials[i]);
                var piece = new GameObject("Gorilla Passthrough: " + names[i]);
                Object.DontDestroyOnLoad(piece);
                piece.AddComponent<MeshFilter>().sharedMesh = new Mesh { name = names[i] };
                var renderer = piece.AddComponent<MeshRenderer>();
                renderer.sharedMaterial = _materials[i];
                renderer.shadowCastingMode = ShadowCastingMode.Off;
                renderer.receiveShadows = false;
                piece.SetActive(false);
                _pieces[i] = piece;
            }
            _ownGorilla = eyes != null;
            _hiddenFromEyesLayer = _ownGorilla ? FindLayerHiddenFrom(eyes) : -1;
            if (_hiddenFromEyesLayer >= 0)
            {
                _pieces[Helmet].layer = _hiddenFromEyesLayer;
            }
        }

        public void Destroy()
        {
            foreach (GameObject piece in _pieces)
            {
                Object.Destroy(piece.GetComponent<MeshFilter>().sharedMesh);
                Object.Destroy(piece);
            }
            foreach (Material material in _materials)
            {
                Object.Destroy(material);
            }
            foreach (Texture2D texture in _textures)
            {
                if (texture != null)
                {
                    Object.Destroy(texture);
                }
            }
        }

        public void SetLight(int level)
        {
            float light = 0.25f + 0.75f * Mathf.Clamp01(level / 15f);
            if (Mathf.Abs(light - _light) > 0.01f)
            {
                _light = light;
                foreach (Material material in _materials)
                {
                    Shaders.SetColor(material, new Color(light, light, light, 1f));
                }
            }
        }

        /// <summary>Four pieces (helmet, chestplate, leggings, boots), each in the held item's format.</summary>
        public void Read(JavaReader reader)
        {
            for (int slot = 0; slot < 4; slot++)
            {
                int kind = reader.Byte();
                if (_textures[slot] != null)
                {
                    Object.Destroy(_textures[slot]);
                    _textures[slot] = null;
                }
                if (kind == 0)
                {
                    _palettes[slot] = null;
                    continue;
                }
                int width = (ushort)reader.Short();
                int height = (ushort)reader.Short();
                byte[] pixels = reader.Bytes(width * height * 4);
                _palettes[slot] = Palette(pixels);
                if (kind == 3)
                {
                    _textures[slot] = TextureOf(pixels, width, height);
                }
            }
            _built = false;
        }

        private bool _built;
        private float _builtHeadRadius, _builtBodyRadius, _builtBodyHeight;

        /// <summary>Called late every frame, after Gorilla Tag placed the gorilla.</summary>
        public void Update(GTPlayer player, Transform head, Transform leftHand, Transform rightHand, bool visible)
        {
            if (player == null || head == null)
            {
                return;
            }
            SphereCollider headCollider = player.headCollider;
            Update(headCollider.transform.TransformPoint(headCollider.center), HeadRadius(player), head.rotation, leftHand, rightHand, visible);
        }

        /// <summary>The size of a gorilla's head (every gorilla has the same build).</summary>
        public static float HeadRadius(GTPlayer player)
        {
            return player.headCollider.radius * MaxScale(player.headCollider.transform);
        }

        /// <summary>Dresses a gorilla whose head is at headCentre, looking along headRotation.</summary>
        public void Update(Vector3 headCentre, float headRadius, Quaternion headRotation, Transform leftHand, Transform rightHand, bool visible)
        {
            if (!_built || Mathf.Abs(headRadius - _builtHeadRadius) > 0.01f)
            {
                Build(headRadius, 0f, 0f);
            }

            // the body turns with where the gorilla looks, but stays upright
            Vector3 flat = Vector3.ProjectOnPlane(headRotation * Vector3.forward, Vector3.up);
            Quaternion bodyTurn = flat.sqrMagnitude > 1e-4f ? Quaternion.LookRotation(flat.normalized, Vector3.up) : Quaternion.identity;
            Vector3 fallback = headCentre - Vector3.up * headRadius * 1.7f;

            // your own helmet only where your eyes don't look from (mirrors, other cameras); another gorilla's always
            Place(Helmet, visible && (!_ownGorilla || _hiddenFromEyesLayer >= 0), headCentre, headRotation);
            Place(Chestplate, visible, headCentre, bodyTurn);
            Place(Leggings, visible, headCentre, bodyTurn);
            Place(Boots, visible, leftHand != null ? leftHand.position : fallback, leftHand != null ? leftHand.rotation : bodyTurn);
            Place(Boots + 1, visible, rightHand != null ? rightHand.position : fallback, rightHand != null ? rightHand.rotation : bodyTurn);
        }

        private void Place(int index, bool visible, Vector3 position, Quaternion rotation)
        {
            int slot = Mathf.Min(index, Boots);
            GameObject piece = _pieces[index];
            bool show = visible && _palettes[slot] != null;
            if (piece.activeSelf != show)
            {
                piece.SetActive(show);
            }
            if (show)
            {
                piece.transform.SetPositionAndRotation(position, rotation);
            }
        }

        private void Build(float headRadius, float bodyRadius, float bodyHeight)
        {
            _built = true;
            _builtHeadRadius = headRadius;
            _builtBodyRadius = bodyRadius;
            _builtBodyHeight = bodyHeight;
            float r = headRadius;
            // the gorilla's furry body is much bigger than its physics capsule; its proportions follow the head: about
            // 3.2 head-radii wide, its middle 1.7 head-radii below the head's centre
            float w = r * 3.5f;
            float d = r * 3.1f;
            float k = r * 0.95f;
            for (int i = 0; i < _pieces.Length; i++)
            {
                Texture2D texture = _textures[Mathf.Min(i, Boots)];
                Shaders.SetTexture(_materials[i], texture != null ? (Texture)texture : Texture2D.whiteTexture);
            }
            if (_textures[Helmet] != null)
            {
                // Minecraft's helmet, as on a player's head (its face opening and all)
                TexturedBox(Helmet, new Vector3(0f, r * 0.25f, -r * 0.02f), new Vector3(r * 2.45f, r * 2.3f, r * 2.5f), 0, 0, 8, 8, 8, 0f, 1f, top: true);
            }
            else
            {
                // a shell over the top and back of the head, a brim over the eyes
                Shell(Helmet, Helmet, new Vector3(0f, r * 0.45f, -r * 0.05f), new Vector3(r * 2.35f, r * 1.3f, r * 2.4f), top: true, bottom: false, front: 0.45f);
            }
            if (_textures[Chestplate] != null)
            {
                // the chestplate's body part round the upper body
                TexturedBox(Chestplate, new Vector3(0f, -r * 1.55f, -r * 0.1f), new Vector3(w, r * 1.7f, d), 16, 16, 8, 12, 4, 0f, 0.75f, top: true);
            }
            else
            {
                Shell(Chestplate, Chestplate, new Vector3(0f, -r * 1.45f, -r * 0.1f), new Vector3(w, r * 1.3f, d), top: true, bottom: false, front: 1f);
            }
            if (_textures[Leggings] != null)
            {
                // the leggings' waist, as a band round the hips (a gorilla has no legs to speak of)
                TexturedBox(Leggings, new Vector3(0f, -r * 2.4f, -r * 0.1f), new Vector3(w * 1.03f, r * 0.7f, d * 1.03f), 16, 16, 8, 12, 4, 0.5f, 1f, top: false);
            }
            else
            {
                Shell(Leggings, Leggings, new Vector3(0f, -r * 2.4f, -r * 0.1f), new Vector3(w * 1.03f, r * 0.6f, d * 1.03f), top: false, bottom: false, front: 1f);
            }
            for (int boot = Boots; boot <= Boots + 1; boot++)
            {
                if (_textures[Boots] != null)
                {
                    // the boots' feet, as guards over the knuckles (gorillas walk on their knuckles)
                    TexturedBox(boot, Vector3.zero, new Vector3(k, k, k), 0, 16, 4, 12, 4, 0.6f, 1f, top: true);
                }
                else
                {
                    Shell(boot, Boots, Vector3.zero, new Vector3(k, k, k), top: true, bottom: false, front: 1f);
                }
            }
        }

        /// <summary>
        /// A box textured like one of Minecraft's model cubes: the texture's cube at (u, v), w x h x d pixels, laid out the
        /// way Minecraft lays out cubes (top and bottom above; right side, front, left side, back below). The sides use
        /// only rows from <paramref name="from"/> to <paramref name="to"/> of their height (a boot is the bottom of a leg).
        /// +z is where the gorilla looks, +x its right.
        /// </summary>
        private void TexturedBox(int index, Vector3 centre, Vector3 size, int u, int v, int w, int h, int d, float from, float to, bool top)
        {
            Texture2D texture = _textures[Mathf.Min(index, Boots)];
            float tw = texture.width, th = texture.height;
            Mesh mesh = _pieces[index].GetComponent<MeshFilter>().sharedMesh;
            mesh.Clear();
            var vertices = new List<Vector3>();
            var uvs = new List<Vector2>();
            var colors = new List<Color32>();
            var triangles = new List<int>();
            Vector3 lo = centre - size * 0.5f, hi = centre + size * 0.5f;
            float rowFrom = d + h * from, rowTo = d + h * to;

            // one face: a corner, the directions the texture's columns and rows run along, and its rectangle in pixels
            void Face(Vector3 corner, Vector3 across, Vector3 down, float px, float py, float pw, float ph, float shade)
            {
                int start = vertices.Count;
                vertices.Add(corner);
                vertices.Add(corner + across);
                vertices.Add(corner + across + down);
                vertices.Add(corner + down);
                uvs.Add(new Vector2((u + px) / tw, 1f - (v + py) / th));
                uvs.Add(new Vector2((u + px + pw) / tw, 1f - (v + py) / th));
                uvs.Add(new Vector2((u + px + pw) / tw, 1f - (v + py + ph) / th));
                uvs.Add(new Vector2((u + px) / tw, 1f - (v + py + ph) / th));
                var c = new Color(shade, shade, shade, 1f);
                Color32 color = QualitySettings.activeColorSpace == ColorSpace.Linear ? c.linear : c;
                for (int i = 0; i < 4; i++)
                {
                    colors.Add(color);
                }
                triangles.Add(start); triangles.Add(start + 1); triangles.Add(start + 2);
                triangles.Add(start); triangles.Add(start + 2); triangles.Add(start + 3);
            }

            float top0 = hi.y, height = size.y;
            Vector3 down = new Vector3(0f, -height, 0f);
            // front (+z): the texture's left is the wearer's right (+x)
            Face(new Vector3(hi.x, top0, hi.z), new Vector3(-size.x, 0f, 0f), down, d, rowFrom, w, rowTo - rowFrom, 1f);
            // back (-z)
            Face(new Vector3(lo.x, top0, lo.z), new Vector3(size.x, 0f, 0f), down, d + w + d, rowFrom, w, rowTo - rowFrom, 0.8f);
            // the wearer's right side (+x): back on the texture's left, front on its right
            Face(new Vector3(hi.x, top0, lo.z), new Vector3(0f, 0f, size.z), down, 0f, rowFrom, d, rowTo - rowFrom, 0.9f);
            // the wearer's left side (-x)
            Face(new Vector3(lo.x, top0, hi.z), new Vector3(0f, 0f, -size.z), down, d + w, rowFrom, d, rowTo - rowFrom, 0.9f);
            if (top)
            {
                // seen from above: its bottom edge meets the front
                Face(new Vector3(hi.x, top0, lo.z), new Vector3(-size.x, 0f, 0f), new Vector3(0f, 0f, size.z), d, 0f, w, d, 1f);
            }
            mesh.SetVertices(vertices);
            mesh.SetUVs(0, uvs);
            mesh.SetColors(colors);
            mesh.SetTriangles(triangles, 0);
            mesh.RecalculateBounds();
        }

        /// <summary>The texture Minecraft sent (top row first) as a Unity texture, sharp pixels.</summary>
        private static Texture2D TextureOf(byte[] pixels, int width, int height)
        {
            var flipped = new byte[pixels.Length];
            for (int y = 0; y < height; y++)
            {
                System.Buffer.BlockCopy(pixels, y * width * 4, flipped, (height - 1 - y) * width * 4, width * 4);
            }
            // Minecraft's colours are sRGB
            var texture = new Texture2D(width, height, TextureFormat.RGBA32, false, false)
            {
                name = "Minecraft armour",
                filterMode = FilterMode.Point,
                wrapMode = TextureWrapMode.Clamp,
            };
            texture.LoadRawTextureData(flipped);
            texture.Apply(false, false);
            return texture;
        }

        /// <summary>
        /// A box shell (two-sided) with one coloured block-pixel per ~2 cm, coloured from the piece's own palette.
        /// <paramref name="front"/> is how far down the front face reaches (1 = all the way).
        /// </summary>
        private void Shell(int index, int slot, Vector3 centre, Vector3 size, bool top, bool bottom, float front)
        {
            Color32[] palette = _palettes[slot];
            Mesh mesh = _pieces[index].GetComponent<MeshFilter>().sharedMesh;
            mesh.Clear();
            if (palette == null)
            {
                return;
            }
            var builder = new ShellBuilder(palette, index * 7919);
            Vector3 h = size * 0.5f;
            Vector3 min = centre - h;
            Vector3 x = new Vector3(size.x, 0f, 0f), y = new Vector3(0f, size.y, 0f), z = new Vector3(0f, 0f, size.z);
            builder.Face(min + z, x, y * front, 0.85f, fromTop: true, fullHeight: y);   // front (+z faces where the head looks)
            builder.Face(min, x, y, 0.75f);                                              // back
            builder.Face(min, z, y, 0.7f);                                               // left
            builder.Face(min + x, z, y, 0.7f);                                           // right
            if (top) builder.Face(min + y, x, z, 1f);
            if (bottom) builder.Face(min, x, z, 0.5f);
            builder.Apply(mesh);
        }

        /// <summary>The piece's colours, darkest to lightest, from its opaque pixels.</summary>
        private static Color32[] Palette(byte[] pixels)
        {
            var colors = new List<Color32>();
            for (int i = 0; i + 3 < pixels.Length; i += 4)
            {
                if (pixels[i + 3] >= 128)
                {
                    colors.Add(new Color32(pixels[i], pixels[i + 1], pixels[i + 2], 255));
                }
            }
            if (colors.Count == 0)
            {
                return null;
            }
            colors.Sort((a, b) => (a.r + a.g + a.b).CompareTo(b.r + b.g + b.b));
            // drop the darkest quarter: that's the item picture's outline, not the material
            return colors.GetRange(colors.Count / 4, colors.Count - colors.Count / 4).ToArray();
        }

        private static float MaxScale(Transform transform)
        {
            Vector3 s = transform.lossyScale;
            return Mathf.Max(Mathf.Abs(s.x), Mathf.Max(Mathf.Abs(s.y), Mathf.Abs(s.z)));
        }

        /// <summary>A physics/render layer some other camera draws but your eyes don't (Gorilla Tag's mirror-only layer).</summary>
        private static int FindLayerHiddenFrom(Camera eyes)
        {
            foreach (string name in new[] { "MirrorOnly", "Mirror Only", "ThirdPersonOnly" })
            {
                int layer = LayerMask.NameToLayer(name);
                if (layer >= 0 && eyes != null && (eyes.cullingMask & (1 << layer)) == 0)
                {
                    Plugin.Log.LogInfo("Gorilla Passthrough: the helmet uses layer " + layer + " (" + name + "), hidden from your eyes");
                    return layer;
                }
            }
            Plugin.Log.LogInfo("Gorilla Passthrough: no mirror-only layer; the helmet is kept small so it doesn't cover your eyes");
            return -1;
        }

        private sealed class ShellBuilder
        {
            private const float Pixel = 0.022f;
            private readonly Color32[] _palette;
            private readonly bool _linear = QualitySettings.activeColorSpace == ColorSpace.Linear;
            private readonly List<Vector3> _vertices = new List<Vector3>();
            private readonly List<Color32> _colors = new List<Color32>();
            private readonly List<int> _triangles = new List<int>();
            private int _seed;

            public ShellBuilder(Color32[] palette, int seed)
            {
                _palette = palette;
                _seed = seed;
            }

            /// <summary>A rectangle from origin along across and up, split into block-pixels.</summary>
            public void Face(Vector3 origin, Vector3 across, Vector3 up, float shade, bool fromTop = false, Vector3 fullHeight = default)
            {
                if (fromTop)
                {
                    // a front face that only reaches part way down from the top
                    origin += fullHeight - up;
                }
                int columns = Mathf.Max(1, Mathf.RoundToInt(across.magnitude / Pixel));
                int rows = Mathf.Max(1, Mathf.RoundToInt(up.magnitude / Pixel));
                Vector3 du = across / columns, dv = up / rows;
                for (int row = 0; row < rows; row++)
                {
                    for (int col = 0; col < columns; col++)
                    {
                        Color32 c = _palette[Hash(row, col) % _palette.Length];
                        // a little darker at the edges, like Minecraft's armour trim
                        bool rim = row == 0 || row == rows - 1 || col == 0 || col == columns - 1;
                        float s = shade * (rim ? 0.8f : 1f);
                        var color = new Color(c.r / 255f * s, c.g / 255f * s, c.b / 255f * s, 1f);
                        Color32 vertexColor = _linear ? color.linear : color;
                        Vector3 a = origin + du * col + dv * row;
                        int start = _vertices.Count;
                        _vertices.Add(a);
                        _vertices.Add(a + dv);
                        _vertices.Add(a + dv + du);
                        _vertices.Add(a + du);
                        for (int i = 0; i < 4; i++)
                        {
                            _colors.Add(vertexColor);
                        }
                        _triangles.Add(start); _triangles.Add(start + 1); _triangles.Add(start + 2);
                        _triangles.Add(start); _triangles.Add(start + 2); _triangles.Add(start + 3);
                    }
                }
            }

            private int Hash(int row, int col)
            {
                unchecked
                {
                    int h = _seed + row * 73856093 ^ col * 19349663;
                    h ^= h >> 13;
                    h *= 0x5bd1e995;
                    return (h ^ (h >> 15)) & 0x7fffffff;
                }
            }

            public void Apply(Mesh mesh)
            {
                mesh.Clear();
                mesh.indexFormat = _vertices.Count > 65000 ? IndexFormat.UInt32 : IndexFormat.UInt16;
                mesh.SetVertices(_vertices);
                mesh.SetColors(_colors);
                mesh.SetTriangles(_triangles, 0);
                mesh.RecalculateBounds();
            }
        }
    }
}
