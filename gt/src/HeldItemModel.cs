using System.Collections.Generic;
using UnityEngine;
using UnityEngine.Rendering;

namespace GorillaPassthrough
{
    /// <summary>
    /// The item the gorilla holds, as a little 3D model in its right fist, built from the item's own picture that
    /// Minecraft sends (the way Minecraft itself makes held items: every pixel one block-pixel deep). Blocks become a
    /// small cube. It sits on the hand, so it moves with it with no delay; it is lit by Minecraft's light level where the
    /// gorilla is.
    /// </summary>
    internal sealed class HeldItemModel
    {
        /// <summary>A 16-pixel item picture is this wide: a sword is about 40 cm long.</summary>
        private const float ItemSize = 0.3f;
        private const float BlockSize = 0.14f;

        private readonly GameObject _object;
        private readonly Mesh _mesh;
        private readonly Material _material;
        private int _kind;
        private Vector3 _handle;
        private float _light = 1f;
        private Transform _parent;
        private readonly bool _rideOnHand;

        /// <param name="rideOnHand">
        /// Parent the item to the hand (your own gorilla, whose hands Gorilla Tag may move again later in the frame).
        /// Another gorilla's item isn't: in VR, things inside the copied gorilla are drawn as if on your own body.
        /// </param>
        public HeldItemModel(bool rideOnHand = true)
        {
            _rideOnHand = rideOnHand;
            _object = new GameObject("Gorilla Passthrough: held item");
            Object.DontDestroyOnLoad(_object);
            _mesh = new Mesh { name = "held item", indexFormat = IndexFormat.UInt32 };
            _object.AddComponent<MeshFilter>().sharedMesh = _mesh;
            var renderer = _object.AddComponent<MeshRenderer>();
            renderer.shadowCastingMode = ShadowCastingMode.Off;
            renderer.receiveShadows = false;
            _material = Shaders.CreateUnlit("Gorilla Passthrough: held item");
            Shaders.SetTexture(_material, Texture2D.whiteTexture);
            renderer.sharedMaterial = _material;
            _object.SetActive(false);
        }

        public void Destroy()
        {
            Object.Destroy(_object);
            Object.Destroy(_mesh);
            Object.Destroy(_material);
        }

        /// <summary>Minecraft's light level (0..15) where the gorilla is.</summary>
        public void SetLight(int level)
        {
            float light = 0.2f + 0.8f * Mathf.Clamp01(level / 15f);
            if (Mathf.Abs(light - _light) > 0.01f)
            {
                _light = light;
                Shaders.SetColor(_material, new Color(light, light, light, 1f));
            }
        }

        public void Read(JavaReader reader)
        {
            _kind = reader.Byte();
            if (_kind == 0)
            {
                _mesh.Clear();
                return;
            }
            int width = (ushort)reader.Short();
            int height = (ushort)reader.Short();
            byte[] pixels = reader.Bytes(width * height * 4);
            if (_kind == 2)
            {
                BuildBlock(pixels, width, height);
            }
            else
            {
                BuildFlat(pixels, width, height);
            }
        }

        /// <summary>Called late every frame, after Gorilla Tag placed the hands.</summary>
        public void Update(Transform hand, Transform controller, bool visible)
        {
            bool show = visible && _kind != 0 && hand != null && controller != null;
            if (_object.activeSelf != show)
            {
                _object.SetActive(show);
            }
            if (!show)
            {
                return;
            }
            if (_rideOnHand && _parent != hand)
            {
                // riding on the hand: if Gorilla Tag moves the hand again later this frame, the item goes with it
                _parent = hand;
                _object.transform.SetParent(hand, true);
            }
            Vector3 side = controller.right;
            Vector3 along = Quaternion.AngleAxis(-Plugin.HeldItemTilt.Value, side) * controller.forward;
            // the hand's point is on top of the fist; the item goes down into it
            Vector3 grip = hand.position - controller.up * Plugin.HeldItemLower.Value;
            if (_kind == 2)
            {
                // a block sits in the palm, just in front of the knuckles
                Quaternion rotation = Quaternion.LookRotation(along, controller.up);
                _object.transform.SetPositionAndRotation(grip + along * (BlockSize * 0.5f + 0.02f), rotation);
            }
            else
            {
                // a flat item's picture runs handle (bottom-left) to tip (top-right): that diagonal points out of the fist
                Vector3 up = Vector3.Cross(along, side).normalized;
                Quaternion rotation = Quaternion.LookRotation(side, (along - up).normalized);
                _object.transform.SetPositionAndRotation(grip - rotation * _handle, rotation);
            }
        }

        private void BuildFlat(byte[] pixels, int width, int height)
        {
            float px = ItemSize / 16f;
            float half = px * 0.5f;
            var builder = new Builder();
            for (int row = 0; row < height; row++)
            {
                for (int col = 0; col < width; col++)
                {
                    if (!Opaque(pixels, width, height, col, row))
                    {
                        continue;
                    }
                    Color32 color = ColorAt(pixels, width, col, row);
                    // picture rows run top to bottom; the model's y runs up
                    float x0 = col * px, x1 = x0 + px;
                    float y0 = (height - 1 - row) * px, y1 = y0 + px;
                    builder.Quad(new Vector3(x0, y0, -half), new Vector3(x0, y1, -half), new Vector3(x1, y1, -half), new Vector3(x1, y0, -half), color, 1f);
                    builder.Quad(new Vector3(x1, y0, half), new Vector3(x1, y1, half), new Vector3(x0, y1, half), new Vector3(x0, y0, half), color, 1f);
                    if (!Opaque(pixels, width, height, col - 1, row))
                        builder.Quad(new Vector3(x0, y0, half), new Vector3(x0, y1, half), new Vector3(x0, y1, -half), new Vector3(x0, y0, -half), color, 0.75f);
                    if (!Opaque(pixels, width, height, col + 1, row))
                        builder.Quad(new Vector3(x1, y0, -half), new Vector3(x1, y1, -half), new Vector3(x1, y1, half), new Vector3(x1, y0, half), color, 0.75f);
                    if (!Opaque(pixels, width, height, col, row - 1))
                        builder.Quad(new Vector3(x0, y1, -half), new Vector3(x0, y1, half), new Vector3(x1, y1, half), new Vector3(x1, y1, -half), color, 0.9f);
                    if (!Opaque(pixels, width, height, col, row + 1))
                        builder.Quad(new Vector3(x0, y0, half), new Vector3(x0, y0, -half), new Vector3(x1, y0, -half), new Vector3(x1, y0, half), color, 0.6f);
                }
            }
            builder.Apply(_mesh);
            // the handle: a little in from the bottom-left corner, where Minecraft's swords and tools have it
            _handle = new Vector3(width * 0.16f * px, height * 0.16f * px, 0f);
        }

        private void BuildBlock(byte[] pixels, int width, int height)
        {
            float s = BlockSize;
            float h = s * 0.5f;
            var builder = new Builder();
            // each face: an origin corner and the directions of the picture's columns and rows (top row first)
            Face(builder, pixels, width, height, new Vector3(-h, h, h), Vector3.right, Vector3.back, s, 1.0f);    // top
            Face(builder, pixels, width, height, new Vector3(-h, -h, -h), Vector3.right, Vector3.forward, s, 0.5f); // bottom
            Face(builder, pixels, width, height, new Vector3(-h, h, -h), Vector3.right, Vector3.down, s, 0.8f);  // front
            Face(builder, pixels, width, height, new Vector3(h, h, h), Vector3.left, Vector3.down, s, 0.8f);      // back
            Face(builder, pixels, width, height, new Vector3(-h, h, h), Vector3.back, Vector3.down, s, 0.6f);    // left
            Face(builder, pixels, width, height, new Vector3(h, h, -h), Vector3.forward, Vector3.down, s, 0.6f); // right
            builder.Apply(_mesh);
            _handle = Vector3.zero;
        }

        private static void Face(Builder builder, byte[] pixels, int width, int height, Vector3 origin, Vector3 across, Vector3 down, float size, float shade)
        {
            float pw = size / width, ph = size / height;
            for (int row = 0; row < height; row++)
            {
                for (int col = 0; col < width; col++)
                {
                    if (!Opaque(pixels, width, height, col, row))
                    {
                        continue;
                    }
                    Vector3 a = origin + across * (col * pw) + down * (row * ph);
                    Vector3 b = a + across * pw;
                    Vector3 c = b + down * ph;
                    Vector3 d = a + down * ph;
                    builder.Quad(a, b, c, d, ColorAt(pixels, width, col, row), shade);
                }
            }
        }

        private static bool Opaque(byte[] pixels, int width, int height, int col, int row)
        {
            return col >= 0 && row >= 0 && col < width && row < height && pixels[(row * width + col) * 4 + 3] >= 128;
        }

        private static Color32 ColorAt(byte[] pixels, int width, int col, int row)
        {
            int i = (row * width + col) * 4;
            return new Color32(pixels[i], pixels[i + 1], pixels[i + 2], 255);
        }

        private sealed class Builder
        {
            private readonly List<Vector3> _vertices = new List<Vector3>();
            private readonly List<Color32> _colors = new List<Color32>();
            private readonly List<Vector2> _uvs = new List<Vector2>();
            private readonly List<int> _triangles = new List<int>();
            private readonly bool _linear = QualitySettings.activeColorSpace == ColorSpace.Linear;

            public void Quad(Vector3 a, Vector3 b, Vector3 c, Vector3 d, Color32 srgb, float shade)
            {
                var color = new Color(srgb.r / 255f * shade, srgb.g / 255f * shade, srgb.b / 255f * shade, 1f);
                // vertex colours go to the screen as they are; in a linear-colour project they must be linear
                Color32 vertexColor = _linear ? color.linear : color;
                int start = _vertices.Count;
                _vertices.Add(a);
                _vertices.Add(b);
                _vertices.Add(c);
                _vertices.Add(d);
                for (int i = 0; i < 4; i++)
                {
                    _colors.Add(vertexColor);
                    _uvs.Add(Vector2.zero);
                }
                _triangles.Add(start); _triangles.Add(start + 1); _triangles.Add(start + 2);
                _triangles.Add(start); _triangles.Add(start + 2); _triangles.Add(start + 3);
            }

            public void Apply(Mesh mesh)
            {
                mesh.Clear();
                mesh.SetVertices(_vertices);
                mesh.SetColors(_colors);
                mesh.SetUVs(0, _uvs);
                mesh.SetTriangles(_triangles, 0);
                mesh.RecalculateBounds();
            }
        }
    }
}
