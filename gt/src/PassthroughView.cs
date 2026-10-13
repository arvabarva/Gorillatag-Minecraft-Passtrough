using System;
using System.Runtime.InteropServices;
using Unity.Collections;
using Unity.Collections.LowLevel.Unsafe;
using UnityEngine;
using UnityEngine.Rendering;

namespace GorillaPassthrough
{
    /// <summary>
    /// Shows what Minecraft draws. Every Minecraft frame comes with a finished mesh built from its depth buffer; it is
    /// textured with Minecraft's picture and placed at the exact head pose that frame was drawn from. Because it is real
    /// geometry in the world (not a flat screen in front of your face):
    /// <list type="bullet">
    /// <item>both eyes see depth, and the world stays still when you turn your head even though Minecraft's picture
    /// arrives a frame or two late,</item>
    /// <item>the gorilla's own arms and body sort correctly in front of and behind Minecraft's blocks.</item>
    /// </list>
    /// Minecraft builds the mesh (see DepthMesh.java); this only hands it to the graphics card, straight from shared
    /// memory, so it costs Gorilla Tag next to nothing. Only shaders already in Gorilla Tag's build are used.
    /// </summary>
    internal sealed unsafe class PassthroughView
    {
        private const int PoseHistory = 512;
        private const MeshUpdateFlags Quick = MeshUpdateFlags.DontValidateIndices | MeshUpdateFlags.DontRecalculateBounds
            | MeshUpdateFlags.DontNotifyMeshUsers | MeshUpdateFlags.DontResetBoneBounds;

        [StructLayout(LayoutKind.Sequential)]
        private struct Vertex
        {
            public Vector3 Position;
            public uint Color;
            public Vector2 Uv;
        }

        private static readonly VertexAttributeDescriptor[] Layout =
        {
            new VertexAttributeDescriptor(VertexAttribute.Position, VertexAttributeFormat.Float32, 3),
            new VertexAttributeDescriptor(VertexAttribute.Color, VertexAttributeFormat.UNorm8, 4),
            new VertexAttributeDescriptor(VertexAttribute.TexCoord0, VertexAttributeFormat.Float32, 2),
        };

        private struct SentPose
        {
            public int Id;
            public Vector3 Position;
            public Quaternion Rotation;
        }

        private readonly SentPose[] _poses = new SentPose[PoseHistory];
        private readonly GameObject _object;
        private readonly Mesh _mesh;
        private readonly Material[] _materials;
        private Texture2D _texture;
        private Texture2D _background;

        public int FramesShown { get; private set; }
        public int FramesDropped { get; private set; }

        public PassthroughView(bool flipVertical)
        {
            _object = new GameObject("Gorilla Passthrough: Minecraft");
            UnityEngine.Object.DontDestroyOnLoad(_object);
            _mesh = new Mesh { name = "Minecraft depth mesh" };
            _mesh.SetVertexBufferParams(Protocol.MaxVertices, Layout);
            _mesh.SetIndexBufferParams(Protocol.MaxIndices, IndexFormat.UInt32);
            _mesh.subMeshCount = 4;
            _mesh.bounds = new Bounds(Vector3.zero, Vector3.one * 4000f);
            _mesh.MarkDynamic();
            _object.AddComponent<MeshFilter>().sharedMesh = _mesh;
            var renderer = _object.AddComponent<MeshRenderer>();
            renderer.shadowCastingMode = ShadowCastingMode.Off;
            renderer.receiveShadows = false;
            renderer.lightProbeUsage = LightProbeUsage.Off;
            renderer.reflectionProbeUsage = ReflectionProbeUsage.Off;
            // plain cells; then on depth edges the far copy (also carried on in behind near things), twice: first all in
            // the background picture (the background filled in where near things hid it), then the picture's own far pixels
            // over it (their alpha says which are far); last the near copy, showing only the near pixels
            Material opaque = Shaders.CreateUnlit("Gorilla Passthrough: Minecraft picture");
            Material backFill = Shaders.CreateUnlit("Gorilla Passthrough: Minecraft background, far side of edges");
            backFill.renderQueue = (int)RenderQueue.Geometry + 2;
            Material back = Shaders.CreateUnlit("Gorilla Passthrough: Minecraft picture, far side of edges");
            Shaders.MakeBlended(back, inverted: true, queue: (int)RenderQueue.Geometry + 3);
            Material front = Shaders.CreateUnlit("Gorilla Passthrough: Minecraft picture, near side of edges");
            Shaders.MakeBlended(front, inverted: false, queue: (int)RenderQueue.Geometry + 4);
            _materials = new[] { opaque, backFill, back, front };
            if (flipVertical)
            {
                foreach (Material material in _materials)
                {
                    Shaders.FlipVertically(material);
                }
            }
            renderer.sharedMaterials = _materials;
            _object.SetActive(false);
        }

        /// <summary>Remembers where the head was for each pose sent, to place the frame Minecraft draws from it.</summary>
        public void RememberPose(int id, Vector3 position, Quaternion rotation)
        {
            _poses[id & (PoseHistory - 1)] = new SentPose { Id = id, Position = position, Rotation = rotation };
        }

        public void Hide()
        {
            _object.SetActive(false);
        }

        public void Update(FrameReader reader, float viewDistance)
        {
            byte* slot = reader.TryTakeFrame();
            if (slot == null)
            {
                return;
            }
            long seq = FrameReader.Seq(slot);
            int poseId = FrameReader.ReadInt(slot, Protocol.S_PoseId);
            int width = FrameReader.ReadInt(slot, Protocol.S_Width);
            int height = FrameReader.ReadInt(slot, Protocol.S_Height);
            int vertexCount = FrameReader.ReadInt(slot, Protocol.S_VertexCount);
            int opaque = FrameReader.ReadInt(slot, Protocol.S_OpaqueIndices);
            int back = FrameReader.ReadInt(slot, Protocol.S_BackIndices);
            int front = FrameReader.ReadInt(slot, Protocol.S_FrontIndices);
            SentPose pose = _poses[poseId & (PoseHistory - 1)];
            int indexCount = opaque + back + front;
            if (pose.Id != poseId || width <= 0 || height <= 0 || vertexCount <= 0 || vertexCount > Protocol.MaxVertices
                || opaque < 0 || back < 0 || front < 0 || indexCount > Protocol.MaxIndices)
            {
                FramesDropped++;
                return;
            }

            if (_texture == null || _texture.width != width || _texture.height != height)
            {
                if (_texture != null)
                {
                    UnityEngine.Object.Destroy(_texture);
                }
                // Minecraft's colours are sRGB, so the texture is too (linear = false)
                _texture = new Texture2D(width, height, TextureFormat.RGBA32, false, false)
                {
                    name = "Minecraft frame",
                    filterMode = FilterMode.Bilinear,
                    wrapMode = TextureWrapMode.Clamp,
                    anisoLevel = 4,
                };
                Shaders.SetTexture(_materials[0], _texture);
                Shaders.SetTexture(_materials[2], _texture);
                Shaders.SetTexture(_materials[3], _texture);
            }
            _texture.LoadRawTextureData((IntPtr)(slot + Protocol.S_ColorData), width * height * 4);
            _texture.Apply(false, false);

            int bgWidth = FrameReader.ReadInt(slot, Protocol.S_BgWidth);
            int bgHeight = FrameReader.ReadInt(slot, Protocol.S_BgHeight);
            if (bgWidth > 0 && bgHeight > 0 && bgWidth <= Protocol.MaxWidth && bgHeight <= Protocol.MaxHeight)
            {
                if (_background == null || _background.width != bgWidth || _background.height != bgHeight)
                {
                    if (_background != null)
                    {
                        UnityEngine.Object.Destroy(_background);
                    }
                    _background = new Texture2D(bgWidth, bgHeight, TextureFormat.RGBA32, false, false)
                    {
                        name = "Minecraft background",
                        filterMode = FilterMode.Bilinear,
                        wrapMode = TextureWrapMode.Clamp,
                    };
                    Shaders.SetTexture(_materials[1], _background);
                }
                _background.LoadRawTextureData((IntPtr)(slot + Protocol.S_BgData), bgWidth * bgHeight * 4);
                _background.Apply(false, false);
            }

            // the mesh goes from shared memory to the graphics card without being copied on the way
            NativeArray<Vertex> vertices = NativeArrayUnsafeUtility.ConvertExistingDataToNativeArray<Vertex>(
                slot + Protocol.S_VertexData, vertexCount, Allocator.None);
            NativeArray<int> indices = NativeArrayUnsafeUtility.ConvertExistingDataToNativeArray<int>(
                slot + Protocol.S_IndexData, indexCount, Allocator.None);
            _mesh.SetVertexBufferData(vertices, 0, 0, vertexCount, 0, Quick);
            _mesh.SetIndexBufferData(indices, 0, 0, indexCount, Quick);
            _mesh.SetSubMesh(0, new SubMeshDescriptor(0, opaque), Quick);
            _mesh.SetSubMesh(1, new SubMeshDescriptor(opaque, back), Quick);
            _mesh.SetSubMesh(2, new SubMeshDescriptor(opaque, back), Quick); // the same triangles, drawn twice
            _mesh.SetSubMesh(3, new SubMeshDescriptor(opaque + back, front), Quick);
            if (FrameReader.Seq(slot) != seq)
            {
                // Minecraft overwrote the slot while we read it (very rare); the next frame is moments away
                FramesDropped++;
            }

            _object.transform.SetPositionAndRotation(pose.Position, pose.Rotation);
            UpdateBackdrop(slot, pose.Position, viewDistance * 1.03f);
            if (!_object.activeSelf)
            {
                _object.SetActive(true);
            }
            FramesShown++;
        }

        // ---- the backdrop ----

        private GameObject _backdrop;
        private Mesh _backdropMesh;
        private Color32 _skyTop, _skyHorizon;

        /// <summary>
        /// Behind everything, a big sphere in Minecraft's current sky colours (straight up and at the horizon), so what
        /// is seen behind something right in front of your eyes is sky instead of Gorilla Tag's own sky.
        /// </summary>
        private void UpdateBackdrop(byte* slot, Vector3 head, float radius)
        {
            if (_backdrop == null)
            {
                _backdrop = new GameObject("Gorilla Passthrough: sky backdrop");
                UnityEngine.Object.DontDestroyOnLoad(_backdrop);
                _backdropMesh = BuildSphere(24, 48);
                _backdrop.AddComponent<MeshFilter>().sharedMesh = _backdropMesh;
                var renderer = _backdrop.AddComponent<MeshRenderer>();
                renderer.shadowCastingMode = ShadowCastingMode.Off;
                renderer.receiveShadows = false;
                Material material = Shaders.CreateUnlit("Gorilla Passthrough: sky backdrop");
                Shaders.SetTexture(material, Texture2D.whiteTexture);
                material.renderQueue = (int)RenderQueue.Geometry + 1;
                renderer.sharedMaterial = material;
            }
            Color32 top = SkyColor(slot, Protocol.S_SkyTop);
            Color32 horizon = SkyColor(slot, Protocol.S_SkyHorizon);
            if (!top.Equals(_skyTop) || !horizon.Equals(_skyHorizon))
            {
                _skyTop = top;
                _skyHorizon = horizon;
                Vector3[] vertices = _backdropMesh.vertices;
                var colors = new Color32[vertices.Length];
                for (int i = 0; i < vertices.Length; i++)
                {
                    colors[i] = Color32.Lerp(horizon, top, Mathf.Clamp01(vertices[i].y / 0.6f));
                }
                _backdropMesh.colors32 = colors;
            }
            _backdrop.transform.position = head;
            _backdrop.transform.localScale = Vector3.one * radius;
        }

        private static Color32 SkyColor(byte* slot, int offset)
        {
            var srgb = new Color(FrameReader.ReadFloat(slot, offset), FrameReader.ReadFloat(slot, offset + 4), FrameReader.ReadFloat(slot, offset + 8));
            // vertex colours are used as they are; in a linear-colour project they must be linear
            Color color = QualitySettings.activeColorSpace == ColorSpace.Linear ? srgb.linear : srgb;
            return color;
        }

        /// <summary>A unit sphere seen from the inside.</summary>
        private static Mesh BuildSphere(int rings, int segments)
        {
            var vertices = new Vector3[(rings + 1) * (segments + 1)];
            for (int r = 0; r <= rings; r++)
            {
                float polar = Mathf.PI * r / rings;
                for (int s = 0; s <= segments; s++)
                {
                    float azimuth = 2f * Mathf.PI * s / segments;
                    vertices[r * (segments + 1) + s] = new Vector3(Mathf.Sin(polar) * Mathf.Cos(azimuth), Mathf.Cos(polar), Mathf.Sin(polar) * Mathf.Sin(azimuth));
                }
            }
            var triangles = new int[rings * segments * 6];
            int t = 0;
            for (int r = 0; r < rings; r++)
            {
                for (int s = 0; s < segments; s++)
                {
                    int a = r * (segments + 1) + s;
                    int b = a + 1;
                    int c = a + segments + 1;
                    int d = c + 1;
                    triangles[t++] = a; triangles[t++] = b; triangles[t++] = c;
                    triangles[t++] = b; triangles[t++] = d; triangles[t++] = c;
                }
            }
            var mesh = new Mesh { name = "sky backdrop", vertices = vertices, triangles = triangles };
            mesh.bounds = new Bounds(Vector3.zero, Vector3.one * 2f);
            return mesh;
        }
    }
}
