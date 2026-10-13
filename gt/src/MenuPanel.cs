using GorillaLocomotion;
using UnityEngine;

namespace GorillaPassthrough
{
    /// <summary>
    /// While a Minecraft menu is open (inventory, crafting table, chest, furnace...), the Minecraft window floats in
    /// front of you, big. Point at it with your right hand: the trigger is a left click, the grip a right click, and
    /// holding the left grip makes clicks shift-clicks. Minecraft itself does the rest, exactly as with a mouse.
    /// </summary>
    internal sealed class MenuPanel
    {
        private const float Width = 1.35f;
        private const float Distance = 0.85f;

        private readonly Link _link;
        private readonly PanelTexture _panel;
        private readonly GameObject _quad;
        private readonly Material _material;
        private readonly GameObject _cursor;
        private readonly GameObject _beam;
        private bool _open;
        private bool _placed;
        private float _lastU = -1f, _lastV = -1f;
        private int _lastButtons = -1;
        private bool _lastShift;

        public bool IsOpen => _open;

        public MenuPanel(Link link, PanelTexture panel)
        {
            _link = link;
            _panel = panel;
            _quad = GameObject.CreatePrimitive(PrimitiveType.Quad);
            _quad.name = "Gorilla Passthrough: Minecraft menu";
            Object.Destroy(_quad.GetComponent<Collider>());
            Object.DontDestroyOnLoad(_quad);
            _material = Shaders.CreateUnlit("Gorilla Passthrough: Minecraft menu");
            Setup(_quad, _material);
            _quad.SetActive(false);

            Material pointer = Shaders.CreateUnlit("Gorilla Passthrough: menu pointer");
            Shaders.SetTexture(pointer, Texture2D.whiteTexture);
            Shaders.SetColor(pointer, new Color(0.55f, 0.85f, 1f));
            _cursor = GameObject.CreatePrimitive(PrimitiveType.Sphere);
            _cursor.name = "Gorilla Passthrough: menu cursor";
            Object.Destroy(_cursor.GetComponent<Collider>());
            Object.DontDestroyOnLoad(_cursor);
            _cursor.transform.localScale = Vector3.one * 0.012f;
            Setup(_cursor, pointer);
            _cursor.SetActive(false);
            _beam = GameObject.CreatePrimitive(PrimitiveType.Cube);
            _beam.name = "Gorilla Passthrough: menu pointer beam";
            Object.Destroy(_beam.GetComponent<Collider>());
            Object.DontDestroyOnLoad(_beam);
            Setup(_beam, pointer);
            _beam.SetActive(false);
        }

        private static void Setup(GameObject go, Material material)
        {
            var renderer = go.GetComponent<MeshRenderer>();
            renderer.sharedMaterial = material;
            renderer.shadowCastingMode = UnityEngine.Rendering.ShadowCastingMode.Off;
            renderer.receiveShadows = false;
        }

        /// <summary>Minecraft opened or closed a menu.</summary>
        public void SetOpen(bool open)
        {
            _open = open;
            _placed = false;
            _lastButtons = -1;
            if (!open)
            {
                _quad.SetActive(false);
                _cursor.SetActive(false);
                _beam.SetActive(false);
            }
        }

        public void Update(FrameReader reader, Transform head, Transform pointer, ControllerInputPoller input)
        {
            if (!_open || head == null)
            {
                return;
            }
            _panel.Refresh(reader);
            Texture texture = _panel.Texture;
            if (texture == null)
            {
                return;
            }
            if (!_placed)
            {
                // where you look when it opens, a bit below eye level, standing still in the world
                _placed = true;
                Vector3 forward = Vector3.ProjectOnPlane(head.forward, Vector3.up);
                if (forward.sqrMagnitude < 1e-4f)
                {
                    forward = head.up;
                }
                Vector3 position = head.position + forward.normalized * Distance - Vector3.up * 0.12f;
                _quad.transform.position = position;
                _quad.transform.rotation = Quaternion.LookRotation(position - head.position, Vector3.up);
            }
            Shaders.SetTexture(_material, texture);
            _quad.transform.localScale = new Vector3(Width, Width * texture.height / texture.width, 1f);
            _quad.SetActive(true);

            if (pointer == null)
            {
                return;
            }
            // where the right hand points on the panel
            var plane = new Plane(_quad.transform.forward, _quad.transform.position);
            var ray = new Ray(pointer.position, pointer.forward);
            bool hit = plane.Raycast(ray, out float distance) && distance > 0f;
            Vector3 point = hit ? ray.GetPoint(distance) : Vector3.zero;
            Vector3 local = hit ? _quad.transform.InverseTransformPoint(point) : Vector3.zero;
            float u = local.x + 0.5f;
            float v = local.y + 0.5f;
            hit &= u >= 0f && u <= 1f && v >= 0f && v <= 1f;

            _cursor.SetActive(hit);
            _beam.SetActive(hit);
            if (!hit)
            {
                return;
            }
            Vector3 towardYou = -_quad.transform.forward * 0.004f;
            _cursor.transform.position = point + towardYou;
            Vector3 from = pointer.position;
            _beam.transform.position = (from + point) * 0.5f;
            _beam.transform.rotation = Quaternion.LookRotation(point - from);
            _beam.transform.localScale = new Vector3(0.002f, 0.002f, distance);

            int buttons = 0;
            if (input != null)
            {
                if (input.rightControllerIndexFloat > 0.6f) buttons |= 1;
                if (input.rightControllerGripFloat > 0.6f) buttons |= 2;
            }
            bool shift = input != null && input.leftControllerGripFloat > 0.6f;
            if (buttons != _lastButtons || shift != _lastShift || Mathf.Abs(u - _lastU) > 0.0005f || Mathf.Abs(v - _lastV) > 0.0005f)
            {
                if (buttons != _lastButtons && buttons > _lastButtons && _lastButtons >= 0)
                {
                    GorillaTagger.Instance?.StartVibration(false, 0.25f, 0.03f);
                }
                _lastButtons = buttons;
                _lastShift = shift;
                _lastU = u;
                _lastV = v;
                _link.Send(Protocol.C_Pointer, new JavaWriter().Float(u).Float(v).Byte(buttons).Byte(shift ? 1 : 0));
            }
        }
    }
}
