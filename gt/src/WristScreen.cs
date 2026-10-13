using UnityEngine;

namespace GorillaPassthrough
{
    /// <summary>
    /// A small screen above the left hand showing the whole Minecraft window: hotbar, hearts, chat. Raise your left
    /// hand to look at it; X turns it off. Hidden while a menu panel is open (the menu shows the same picture, big).
    /// </summary>
    internal sealed class WristScreen
    {
        private const float Width = 0.26f;
        private const float ShowWithinMeters = 0.55f;

        private readonly GameObject _quad;
        private readonly Material _material;
        private readonly PanelTexture _panel;

        public WristScreen(PanelTexture panel)
        {
            _panel = panel;
            _quad = GameObject.CreatePrimitive(PrimitiveType.Quad);
            _quad.name = "Gorilla Passthrough: wrist screen";
            Object.Destroy(_quad.GetComponent<Collider>());
            Object.DontDestroyOnLoad(_quad);
            _material = Shaders.CreateUnlit("Gorilla Passthrough: wrist screen");
            var renderer = _quad.GetComponent<MeshRenderer>();
            renderer.sharedMaterial = _material;
            renderer.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            renderer.receiveShadows = false;
            _quad.SetActive(false);
        }

        public void Update(FrameReader reader, Transform leftHand, Transform head, bool enabled)
        {
            bool show = enabled && leftHand != null && head != null && reader.IsOpen
                && Vector3.Distance(leftHand.position, head.position) < ShowWithinMeters;
            if (show || (Plugin.DebugScreenshots.Value && _panel.Texture == null && reader.IsOpen))
            {
                _panel.Refresh(reader); // (in debug also once at the start, which saves the window picture)
            }
            Texture texture = _panel.Texture;
            show &= texture != null;
            if (show)
            {
                Shaders.SetTexture(_material, texture);
                Vector3 position = leftHand.position + Vector3.up * 0.13f;
                _quad.transform.position = position;
                // face the eyes (a Quad's visible side looks down its -z)
                _quad.transform.rotation = Quaternion.LookRotation(position - head.position, Vector3.up);
                _quad.transform.localScale = new Vector3(Width, Width * texture.height / texture.width, 1f);
            }
            if (_quad.activeSelf != show)
            {
                _quad.SetActive(show);
            }
        }
    }
}
