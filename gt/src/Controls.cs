using System.Collections.Generic;
using GorillaLocomotion;
using UnityEngine;

namespace GorillaPassthrough
{
    /// <summary>
    /// The gorilla's hands in Minecraft (moving is plain Gorilla Tag):
    /// <list type="bullet">
    /// <item>hand on a block + hold trigger: mine it (only the trigger mines: every step in Gorilla Tag is a hand
    /// slapping the ground)</item>
    /// <item>hand on a block + grip: place the held block / open doors, chests, crafting tables (on the wrist screen)</item>
    /// <item>grip in the air: eat, drink, throw (hold it like right click)</item>
    /// <item>hand in a mob or player + trigger: hit it</item>
    /// <item>left thumbstick left/right: hotbar slot, B: drop the held item, X: wrist screen on/off,
    /// Y: inventory (see <see cref="MenuPanel"/>)</item>
    /// </list>
    /// </summary>
    internal sealed class Controls
    {
        private const float ReachMeters = 0.2f;

        private readonly Link _link;
        private readonly BlockWorld _world;
        private readonly Dictionary<int, Bounds> _entities = new Dictionary<int, Bounds>();
        private readonly bool[] _gripWas = new bool[2];
        private readonly bool[] _usingItem = new bool[2];
        private readonly float[] _punchCooldown = new float[2];
        private bool _hotbarArmed = true;
        private bool _dropWas;
        private bool _screenToggleWas;
        private bool _inventoryWas;
        private float _mineTimer;
        private float _focusTimer;
        private bool _focusSent;

        public bool WristScreenOn { get; private set; } = true;

        public Controls(Link link, BlockWorld world)
        {
            _link = link;
            _world = world;
        }

        /// <summary>Minecraft's mobs and animals near the gorilla (Minecraft coordinates), for punching.</summary>
        public void ReadEntities(JavaReader reader)
        {
            _entities.Clear();
            int count = reader.Short();
            for (int i = 0; i < count; i++)
            {
                int id = reader.Int();
                var min = new Vector3(reader.Float(), reader.Float(), reader.Float());
                var max = new Vector3(reader.Float(), reader.Float(), reader.Float());
                var bounds = new Bounds();
                bounds.SetMinMax(min, max);
                _entities[id] = bounds;
            }
        }

        public void Update(GTPlayer player, ControllerInputPoller input, Transform[] hands, bool menuOpen)
        {
            if (input == null)
            {
                return;
            }

            // Y: the inventory, or close whatever Minecraft menu is open
            bool inventory = input.leftControllerSecondaryButton;
            if (inventory && !_inventoryWas)
            {
                _link.Send(Protocol.C_Inventory, null);
            }
            _inventoryWas = inventory;
            if (menuOpen)
            {
                // the hands point at the menu now; let go of anything they were doing
                StopUsing();
                return;
            }

            bool toggle = input.leftControllerPrimaryButton;
            if (toggle && !_screenToggleWas)
            {
                WristScreenOn = !WristScreenOn;
            }
            _screenToggleWas = toggle;

            bool drop = input.rightControllerSecondaryButton;
            if (drop && !_dropWas)
            {
                _link.Send(Protocol.C_Drop, new JavaWriter().Byte(0));
            }
            _dropWas = drop;

            float stick = input.leftControllerPrimary2DAxis.x;
            if (_hotbarArmed && Mathf.Abs(stick) > 0.7f)
            {
                _hotbarArmed = false;
                _link.Send(Protocol.C_Slot, new JavaWriter().Byte(stick > 0f ? 1 : -1));
            }
            else if (Mathf.Abs(stick) < 0.3f)
            {
                _hotbarArmed = true;
            }

            float[] trigger = { input.leftControllerIndexFloat, input.rightControllerIndexFloat };
            float[] grip = { input.leftControllerGripFloat, input.rightControllerGripFloat };
            _mineTimer += Time.deltaTime;
            bool sendMine = _mineTimer >= 0.05f;
            if (sendMine)
            {
                _mineTimer = 0f;
            }

            bool anyFocus = false;
            Vector3Int focusBlock = default;
            int focusFace = 1;
            for (int hand = 1; hand >= 0; hand--)
            {
                Vector3 handPosition = HandPosition(player, hand, hands[hand]);
                bool onBlock = FindBlock(handPosition, ReachMeters, out Vector3Int block, out int face, out Vector3 point);
                // outline only the block you're about to act on (squeezing), not every block you walk on
                bool squeezing = trigger[hand] > 0.2f || grip[hand] > 0.2f;
                if (onBlock && squeezing && !anyFocus)
                {
                    anyFocus = true;
                    focusBlock = block;
                    focusFace = face;
                }

                // mining: hold the trigger with the hand on a block
                if (trigger[hand] > 0.6f && onBlock && sendMine)
                {
                    _link.Send(Protocol.C_Mine, new JavaWriter().Int(block.x).Int(block.y).Int(block.z).Byte(face));
                }

                // the grip: use the held item on the block, or in the air
                bool gripDown = grip[hand] > 0.6f;
                if (gripDown && !_gripWas[hand])
                {
                    if (onBlock)
                    {
                        _link.Send(Protocol.C_UseOn, new JavaWriter().Int(block.x).Int(block.y).Int(block.z).Byte(face)
                            .Float(point.x).Float(point.y).Float(point.z));
                    }
                    else
                    {
                        _link.Send(Protocol.C_UseItem, new JavaWriter().Byte(1));
                        _usingItem[hand] = true;
                    }
                }
                else if (!gripDown && _usingItem[hand])
                {
                    _link.Send(Protocol.C_UseItem, new JavaWriter().Byte(0));
                    _usingItem[hand] = false;
                }
                _gripWas[hand] = gripDown;

                PunchMob(hand, hands[hand], trigger[hand] > 0.6f);
            }

            // Minecraft outlines the block a hand rests on
            _focusTimer += Time.deltaTime;
            if (_focusTimer >= 0.1f)
            {
                _focusTimer = 0f;
                if (anyFocus)
                {
                    _link.Send(Protocol.C_Focus, new JavaWriter().Byte(1).Int(focusBlock.x).Int(focusBlock.y).Int(focusBlock.z).Byte(focusFace));
                    _focusSent = true;
                }
                else if (_focusSent)
                {
                    _link.Send(Protocol.C_Focus, new JavaWriter().Byte(0));
                    _focusSent = false;
                }
            }
        }

        private void StopUsing()
        {
            for (int hand = 0; hand < 2; hand++)
            {
                if (_usingItem[hand])
                {
                    _link.Send(Protocol.C_UseItem, new JavaWriter().Byte(0));
                    _usingItem[hand] = false;
                }
                _gripWas[hand] = true; // a grip still held when the menu closes doesn't count as a new press
            }
        }

        /// <summary>
        /// A hand in a mob, animal or player with its trigger held hits it (just touching never does: you'd hit your
        /// friends while climbing past them). Holding on keeps hitting at Minecraft's own pace.
        /// </summary>
        private void PunchMob(int hand, Transform handTransform, bool triggerHeld)
        {
            _punchCooldown[hand] = Mathf.Max(0f, _punchCooldown[hand] - Time.deltaTime);
            if (handTransform == null || !triggerHeld || _punchCooldown[hand] > 0f)
            {
                return;
            }
            Vector3 position = handTransform.position;
            Vector3 mc = _world.ToMinecraftLocal(position);
            foreach (KeyValuePair<int, Bounds> entity in _entities)
            {
                Bounds bounds = entity.Value;
                bounds.Expand(0.16f);
                if (bounds.Contains(mc))
                {
                    _link.Send(Protocol.C_Attack, new JavaWriter().Int(entity.Key));
                    _punchCooldown[hand] = 0.6f;
                    GorillaTagger.Instance?.StartVibration(hand == 0, 0.6f, 0.08f);
                    return;
                }
            }
        }

        /// <summary>
        /// Where Gorilla Tag holds the hand: stopped at a surface when it presses against one (not the real controller,
        /// which can be inside the block).
        /// </summary>
        private static Vector3 HandPosition(GTPlayer player, int hand, Transform fallback)
        {
            object state = HandField[hand]?.GetValue(player);
            if (state != null && LastPosition != null)
            {
                return (Vector3)LastPosition.GetValue(state);
            }
            return fallback != null ? fallback.position : player.headCollider.transform.position;
        }

        private static readonly System.Reflection.FieldInfo[] HandField =
        {
            HarmonyLib.AccessTools.Field(typeof(GTPlayer), "leftHand"),
            HarmonyLib.AccessTools.Field(typeof(GTPlayer), "rightHand"),
        };

        private static readonly System.Reflection.FieldInfo LastPosition =
            HandField[0] != null ? HarmonyLib.AccessTools.Field(HandField[0].FieldType, "lastPosition") : null;

        /// <summary>
        /// The block whose collision box is closest to a point (within reach), the face pointing at the point and the
        /// closest spot on it, in Minecraft coordinates.
        /// </summary>
        private bool FindBlock(Vector3 unityPoint, float reach, out Vector3Int block, out int face, out Vector3 hitPoint)
        {
            Vector3 p = _world.ToMinecraftLocal(unityPoint);
            block = default;
            face = 1;
            hitPoint = p;
            float best = reach;
            bool found = false;
            var center = new Vector3Int(Mathf.FloorToInt(p.x), Mathf.FloorToInt(p.y), Mathf.FloorToInt(p.z));
            for (int dx = -1; dx <= 1; dx++)
            for (int dy = -1; dy <= 1; dy++)
            for (int dz = -1; dz <= 1; dz++)
            {
                var pos = new Vector3Int(center.x + dx, center.y + dy, center.z + dz);
                if (!_world.TryGetBox(pos.x, pos.y, pos.z, out Vector3 boxMin, out Vector3 boxMax))
                {
                    continue;
                }
                Vector3 min = pos + boxMin;
                Vector3 max = pos + boxMax;
                var closest = new Vector3(Mathf.Clamp(p.x, min.x, max.x), Mathf.Clamp(p.y, min.y, max.y), Mathf.Clamp(p.z, min.z, max.z));
                float distance = (p - closest).magnitude;
                if (distance < best || (distance == 0f && !found))
                {
                    best = distance;
                    found = true;
                    block = pos;
                    hitPoint = closest;
                    face = distance > 1e-4f ? FaceOf(p - closest) : NearestFace(p, min, max);
                }
            }
            return found;
        }

        /// <summary>Minecraft's face order: down, up, north (-z), south (+z), west (-x), east (+x).</summary>
        private static int FaceOf(Vector3 n)
        {
            float ax = Mathf.Abs(n.x), ay = Mathf.Abs(n.y), az = Mathf.Abs(n.z);
            if (ay >= ax && ay >= az) return n.y < 0f ? 0 : 1;
            if (az >= ax) return n.z < 0f ? 2 : 3;
            return n.x < 0f ? 4 : 5;
        }

        private static int NearestFace(Vector3 p, Vector3 min, Vector3 max)
        {
            float[] d = { p.y - min.y, max.y - p.y, p.z - min.z, max.z - p.z, p.x - min.x, max.x - p.x };
            int face = 0;
            for (int i = 1; i < 6; i++)
            {
                if (d[i] < d[face]) face = i;
            }
            return face;
        }
    }
}
