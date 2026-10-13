using System;
using UnityEngine;

namespace GorillaPassthrough
{
    /// <summary>
    /// The whole Minecraft window (HUD, menus, tooltips), as Minecraft shares it every few frames. Used by the wrist
    /// screen and by the menu panel.
    /// </summary>
    internal sealed unsafe class PanelTexture
    {
        private long _lastSeq = -1;
        private Texture2D _upload;
        private bool _saved;

        /// <summary>
        /// The window with mipmaps (made on the graphics card), so text and item icons stay smooth instead of shimmering
        /// when the panel is smaller in the headset than the window is on the monitor.
        /// </summary>
        public RenderTexture Texture { get; private set; }

        /// <summary>Called whenever the window picture is shown; uploads it only when Minecraft wrote a new one.</summary>
        public void Refresh(FrameReader reader)
        {
            byte* panel = reader.Panel;
            if (panel == null)
            {
                return;
            }
            long seq = FrameReader.Seq(panel);
            if (seq == _lastSeq || (seq & 1L) != 0)
            {
                return;
            }
            int width = FrameReader.ReadInt(panel, Protocol.P_Width);
            int height = FrameReader.ReadInt(panel, Protocol.P_Height);
            if (width <= 0 || height <= 0)
            {
                return;
            }
            if (_upload == null || _upload.width != width || _upload.height != height)
            {
                if (_upload != null)
                {
                    UnityEngine.Object.Destroy(_upload);
                    Texture.Release();
                    UnityEngine.Object.Destroy(Texture);
                }
                _upload = new Texture2D(width, height, TextureFormat.RGBA32, false, false)
                {
                    name = "Minecraft window (upload)",
                    filterMode = FilterMode.Bilinear,
                    wrapMode = TextureWrapMode.Clamp,
                };
                Texture = new RenderTexture(width, height, 0, RenderTextureFormat.ARGB32, RenderTextureReadWrite.sRGB)
                {
                    name = "Minecraft window",
                    useMipMap = true,
                    autoGenerateMips = true,
                    filterMode = FilterMode.Trilinear,
                    wrapMode = TextureWrapMode.Clamp,
                    anisoLevel = 8,
                };
                Texture.Create();
            }
            _upload.LoadRawTextureData((IntPtr)(panel + Protocol.P_Data), width * height * 4);
            _upload.Apply(false, false);
            Graphics.Blit(_upload, Texture);
            _lastSeq = seq;
            if (Plugin.DebugScreenshots.Value && !_saved)
            {
                // both ways up the same? (the materials flip the window picture themselves)
                _saved = true;
                var previous = RenderTexture.active;
                RenderTexture.active = Texture;
                var copy = new Texture2D(width, height, TextureFormat.RGBA32, false, false);
                copy.ReadPixels(new Rect(0, 0, width, height), 0, 0);
                RenderTexture.active = previous;
                string dir = BepInEx.Paths.BepInExRootPath;
                System.IO.File.WriteAllBytes(System.IO.Path.Combine(dir, "gorillapass-panel-mipmapped.png"), copy.EncodeToPNG());
                System.IO.File.WriteAllBytes(System.IO.Path.Combine(dir, "gorillapass-panel-upload.png"), _upload.EncodeToPNG());
                UnityEngine.Object.Destroy(copy);
            }
        }
    }
}
