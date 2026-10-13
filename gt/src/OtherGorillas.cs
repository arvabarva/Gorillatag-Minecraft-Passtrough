using System.Collections.Generic;
using UnityEngine;

namespace GorillaPassthrough
{
    /// <summary>
    /// Other players who play through Gorilla Tag, drawn as real Gorilla Tag gorillas. Every gorilla's Gorilla Tag sends
    /// the pose of its own gorilla's bones (where Gorilla Tag's arm IK put them) through Minecraft; here each one gets a
    /// copy of the gorilla model in exactly that pose, in its fur colour, with what it holds and wears. Gorilla Tag stays
    /// offline: no Gorilla Tag room, no Gorilla Tag networking.
    /// </summary>
    internal sealed class OtherGorillas
    {
        /// <summary>The bones that make a gorilla's pose, relative to the rig's root.</summary>
        private static readonly string[] Bones =
        {
            "rig",
            "rig/body_pivot",
            "rig/body_pivot/body",
            "rig/head",
            "rig/body_pivot/shoulder.L",
            "rig/body_pivot/shoulder.L/upper_arm.L",
            "rig/body_pivot/shoulder.L/upper_arm.L/forearm.L",
            "rig/body_pivot/shoulder.R",
            "rig/body_pivot/shoulder.R/upper_arm.R",
            "rig/body_pivot/shoulder.R/upper_arm.R/forearm.R",
            "rig/hand.L",
            "rig/hand.R",
        };
        private const string LeftHand = "rig/hand.L/CenterHand";
        private const string RightHand = "rig/hand.R/CenterHand";
        private const float SendInterval = 0.05f;
        /// <summary>Other gorillas are shown this far in the past, so there's always a newer pose to glide to.</summary>
        private const float Delay = 0.12f;

        private readonly BlockWorld _world;
        private readonly Dictionary<int, Gorilla> _gorillas = new Dictionary<int, Gorilla>();
        private float _sendTimer;
        private bool _poseSaved;
        private int _lightLevel = 15;
        private float _logTimer;

        public OtherGorillas(BlockWorld world)
        {
            _world = world;
        }

        public int Count => _gorillas.Count;

        /// <summary>Debug: where the first other gorilla is shown.</summary>
        public bool TryGetFirst(out Vector3 position)
        {
            foreach (Gorilla gorilla in _gorillas.Values)
            {
                position = gorilla.Position;
                return gorilla.HasPose;
            }
            position = default;
            return false;
        }

        // ---- our own gorilla's pose, for the others ----

        /// <summary>Late every frame: 20 times a second, our gorilla's pose goes to Minecraft for the other players.</summary>
        public void SendOwnPose(Link link, VRRig rig)
        {
            _sendTimer += Time.deltaTime;
            if (_sendTimer < SendInterval || rig == null || !_world.Anchored)
            {
                return;
            }
            _sendTimer = 0f;
            Transform root = rig.transform;
            _world.ToMinecraft(root.position, out double x, out double y, out double z);
            Quaternion r = root.rotation;
            var pose = new JavaWriter().Double(x).Double(y).Double(z).Float(r.x).Float(r.y).Float(r.z).Float(r.w).Byte(Bones.Length);
            foreach (string path in Bones)
            {
                Transform bone = root.Find(path);
                Vector3 p = bone != null ? bone.localPosition : Vector3.zero;
                Quaternion q = bone != null ? bone.localRotation : Quaternion.identity;
                pose.Float(p.x).Float(p.y).Float(p.z).Float(q.x).Float(q.y).Float(q.z).Float(q.w);
            }
            pose.Double(Time.realtimeSinceStartup);
            link.Send(Protocol.C_Rig, pose);
            if (Plugin.DebugScreenshots.Value && !_poseSaved)
            {
                // for testing a second gorilla on one PC (tools/mp_gorilla.py replays it)
                _poseSaved = true;
                Vector3 feet = GorillaBody.Feet(GorillaLocomotion.GTPlayer.Instance);
                _world.ToMinecraft(feet, out double fx, out double fy, out double fz);
                string text = string.Format(System.Globalization.CultureInfo.InvariantCulture, "{0} {1} {2}\n{3}", fx, fy, fz,
                    System.BitConverter.ToString(pose.ToArray()).Replace("-", ""));
                System.IO.File.WriteAllText(System.IO.Path.Combine(BepInEx.Paths.BepInExRootPath, "gorillapass-pose.txt"), text);
            }
        }

        // ---- the other gorillas ----

        public void ReadPose(JavaReader reader, VRRig template, GorillaLocomotion.GTPlayer player)
        {
            int id = reader.Int();
            int fur = reader.Int();
            if (!_gorillas.TryGetValue(id, out Gorilla gorilla))
            {
                if (template == null)
                {
                    return;
                }
                gorilla = new Gorilla(template, player);
                _gorillas[id] = gorilla;
                Plugin.Log.LogInfo("Gorilla Passthrough: another gorilla (Minecraft player " + id + ") is here");
            }
            gorilla.SetFur(fur);
            var snapshot = new Snapshot();
            double x = reader.Double(), y = reader.Double(), z = reader.Double();
            snapshot.Root = _world.ToUnity(x, y, z);
            snapshot.Rotation = new Quaternion(reader.Float(), reader.Float(), reader.Float(), reader.Float());
            int count = reader.Byte();
            for (int i = 0; i < count; i++)
            {
                var p = new Vector3(reader.Float(), reader.Float(), reader.Float());
                var q = new Quaternion(reader.Float(), reader.Float(), reader.Float(), reader.Float());
                if (i < Bones.Length)
                {
                    snapshot.Positions[i] = p;
                    snapshot.Rotations[i] = q;
                }
            }
            for (int i = count; i < Bones.Length; i++)
            {
                snapshot.Positions[i] = gorilla.RestPositions[i];
                snapshot.Rotations[i] = gorilla.RestRotations[i];
            }
            // when their Gorilla Tag took it (on its own clock); older plugins don't say, then it's when it got here
            float now = Time.realtimeSinceStartup;
            snapshot.Time = reader.Remaining >= 8 ? (float)reader.Double() : now;
            gorilla.Add(snapshot, now);
        }

        public void ReadEquipment(JavaReader reader)
        {
            int id = reader.Int();
            if (_gorillas.TryGetValue(id, out Gorilla gorilla))
            {
                gorilla.Held.Read(reader);
                gorilla.Armor.Read(reader);
            }
        }

        public void Remove(int id)
        {
            if (_gorillas.TryGetValue(id, out Gorilla gorilla))
            {
                gorilla.Destroy();
                _gorillas.Remove(id);
            }
        }

        public void Clear()
        {
            foreach (Gorilla gorilla in _gorillas.Values)
            {
                gorilla.Destroy();
            }
            _gorillas.Clear();
        }

        public void SetLight(int level)
        {
            _lightLevel = level;
        }

        /// <summary>Late every frame: every other gorilla is shown between the two poses around a moment just past.</summary>
        public void Update()
        {
            float now = Time.realtimeSinceStartup;
            _logTimer += Time.deltaTime;
            bool log = _logTimer > 10f;
            if (log)
            {
                _logTimer = 0f;
            }
            foreach (KeyValuePair<int, Gorilla> entry in _gorillas)
            {
                Gorilla gorilla = entry.Value;
                gorilla.Show(now);
                gorilla.Held.SetLight(_lightLevel);
                gorilla.Armor.SetLight(_lightLevel);
                if (log && gorilla.HasPose && GorillaLocomotion.GTPlayer.Instance != null)
                {
                    Plugin.Log.LogInfo("Gorilla Passthrough: gorilla " + entry.Key + " is "
                        + gorilla.Describe(GorillaLocomotion.GTPlayer.Instance.headCollider.transform.position));
                }
            }
        }

        /// <summary>One pose of another gorilla, as their Gorilla Tag sent it.</summary>
        private sealed class Snapshot
        {
            public float Time;
            public Vector3 Root;
            public Quaternion Rotation;
            public readonly Vector3[] Positions = new Vector3[Bones.Length];
            public readonly Quaternion[] Rotations = new Quaternion[Bones.Length];
        }

        /// <summary>One other gorilla: a copy of Gorilla Tag's gorilla model with nothing but its meshes.</summary>
        private sealed class Gorilla
        {
            private readonly GameObject _holder;
            private readonly Transform _root;
            private readonly Transform[] _bones = new Transform[Bones.Length];
            private readonly SkinnedMeshRenderer _body;
            private readonly Mesh _baked;
            private readonly MeshRenderer _bakedRenderer;
            private readonly Transform _face, _faceStandIn;
            private readonly Transform _head, _leftHand, _rightHand, _rightController;
            private readonly Vector3 _headCentreInHead;
            private readonly Quaternion _lookInHead;
            private readonly float _headRadius;
            private int _fur = int.MinValue;
            private readonly List<Snapshot> _snapshots = new List<Snapshot>();
            private float _clockOffset;
            private bool _hasClock;

            public readonly Vector3[] RestPositions = new Vector3[Bones.Length];
            public readonly Quaternion[] RestRotations = new Quaternion[Bones.Length];
            public bool HasPose => _snapshots.Count > 0;
            public Vector3 Position => _root.position;
            public readonly HeldItemModel Held = new HeldItemModel(false);
            public readonly ArmorModel Armor = new ArmorModel(null);

            public Gorilla(VRRig template, GorillaLocomotion.GTPlayer player)
            {
                // copied while switched off, so none of the gorilla's game scripts ever start; then they're removed
                _holder = new GameObject("Gorilla Passthrough: another gorilla");
                _holder.SetActive(false);
                Object.DontDestroyOnLoad(_holder);
                GameObject copy = Object.Instantiate(template.gameObject, _holder.transform);
                copy.name = "gorilla";
                StripToMeshes(copy);
                _root = copy.transform;
                int foreign = OwnBones(_root, template.transform);
                Plugin.Log.LogInfo("Gorilla Passthrough: copied the gorilla model (VR " + UnityEngine.XR.XRSettings.enabled + ", body "
                    + (template.mainSkin != null ? template.mainSkin.name : "none") + "), " + foreign + " bones were outside it (now its own)");

                // only the gorilla itself: its body (with chest and ears) and face, none of your own cosmetics
                foreach (Renderer renderer in copy.GetComponentsInChildren<Renderer>(true))
                {
                    renderer.enabled = false;
                }
                SkinnedMeshRenderer body = Find(template.transform, template.mainSkin != null ? template.mainSkin.transform : null)?.GetComponent<SkinnedMeshRenderer>();
                if (body != null)
                {
                    // Gorilla Tag's own skinning isn't used: in VR it drew this body on top of you. Every frame the pose is
                    // baked into a plain mesh instead, drawn exactly where this gorilla is.
                    body.enabled = false;
                    body.gameObject.SetActive(true);
                    _body = body;
                    _baked = new Mesh { name = "another gorilla" };
                    _baked.MarkDynamic();
                    var drawn = new GameObject("body");
                    drawn.transform.SetParent(_holder.transform, false);
                    drawn.AddComponent<MeshFilter>().sharedMesh = _baked;
                    _bakedRenderer = drawn.AddComponent<MeshRenderer>();
                    _bakedRenderer.sharedMaterials = body.sharedMaterials;
                }
                MeshRenderer face = Find(template.transform, template.faceSkin != null ? template.faceSkin.transform : null)?.GetComponent<MeshRenderer>();
                if (face != null)
                {
                    // drawn by a stand-in outside the copy that follows the head, like the body
                    face.enabled = false;
                    _face = face.transform;
                    var stand = new GameObject("face");
                    stand.transform.SetParent(_holder.transform, false);
                    stand.AddComponent<MeshFilter>().sharedMesh = face.GetComponent<MeshFilter>()?.sharedMesh;
                    stand.AddComponent<MeshRenderer>().sharedMaterials = face.sharedMaterials;
                    _faceStandIn = stand.transform;
                }
                // your own face is only in mirrors (in VR it'd be inside your eyes); theirs is to be seen
                foreach (Renderer part in copy.GetComponentsInChildren<Renderer>(true))
                {
                    if (part.enabled)
                    {
                        part.gameObject.layer = 0;
                    }
                }
                if (Plugin.DebugScreenshots.Value && template.mainSkin != null)
                {
                    LogColours(template.mainSkin.sharedMaterials[0]);
                }

                for (int i = 0; i < Bones.Length; i++)
                {
                    _bones[i] = _root.Find(Bones[i]);
                    RestPositions[i] = _bones[i] != null ? _bones[i].localPosition : Vector3.zero;
                    RestRotations[i] = _bones[i] != null ? _bones[i].localRotation : Quaternion.identity;
                }
                _head = _root.Find("rig/head");
                _leftHand = _root.Find(LeftHand);
                _rightHand = _root.Find(RightHand);

                // where the head's middle, the eyes' direction and the controller sit relative to the bones: the same on
                // every gorilla, so measured on our own
                Transform ownHead = template.transform.Find("rig/head");
                Transform ownRightHand = template.transform.Find(RightHand);
                Vector3 headCentre = player.headCollider.transform.TransformPoint(player.headCollider.center);
                Transform eyes = player.mainCamera != null ? player.mainCamera.transform : player.headCollider.transform;
                _headCentreInHead = ownHead != null ? ownHead.InverseTransformPoint(headCentre) : Vector3.zero;
                _lookInHead = ownHead != null ? Quaternion.Inverse(ownHead.rotation) * eyes.rotation : Quaternion.identity;
                _headRadius = ArmorModel.HeadRadius(player);
                _rightController = new GameObject("controller").transform;
                _rightController.SetParent(_rightHand != null ? _rightHand : _root, false);
                Transform ownController = ControllerOf(player);
                if (ownRightHand != null && ownController != null)
                {
                    _rightController.localRotation = Quaternion.Inverse(ownRightHand.rotation) * ownController.rotation;
                }
                _holder.SetActive(true);
                copy.SetActive(true);
            }

            /// <summary>Debug: which colour properties the fur material has, and what they hold on our own gorilla.</summary>
            private static void LogColours(Material material)
            {
                Shader shader = material.shader;
                var text = new System.Text.StringBuilder("Gorilla Passthrough: fur material " + material.name + " shader " + shader.name + ":");
                for (int i = 0; i < shader.GetPropertyCount(); i++)
                {
                    var type = shader.GetPropertyType(i);
                    string name = shader.GetPropertyName(i);
                    if (type == UnityEngine.Rendering.ShaderPropertyType.Color)
                    {
                        text.Append(' ').Append(name).Append('=').Append(material.GetColor(name));
                    }
                    else if ((type == UnityEngine.Rendering.ShaderPropertyType.Float || type == UnityEngine.Rendering.ShaderPropertyType.Range)
                        && (name.Contains("Color") || name.Contains("Index") || name.Contains("Mode")))
                    {
                        text.Append(' ').Append(name).Append('=').Append(material.GetFloat(name));
                    }
                }
                text.Append(" keywords: ").Append(string.Join(",", material.shaderKeywords));
                Plugin.Log.LogInfo(text.ToString());
            }

            public void SetFur(int argb)
            {
                if (argb == _fur)
                {
                    return;
                }
                _fur = argb;
                var color = new Color(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, 1f);
                if (_bakedRenderer != null)
                {
                    // Gorilla Tag colours each gorilla's (shared) fur material through the renderer, not the material
                    var block = new MaterialPropertyBlock();
                    _bakedRenderer.GetPropertyBlock(block, 0);
                    block.SetColor("_BaseColor", color);
                    _bakedRenderer.SetPropertyBlock(block, 0);
                }
            }

            public void Add(Snapshot snapshot, float now)
            {
                if (_snapshots.Count > 0 && snapshot.Time <= _snapshots[_snapshots.Count - 1].Time)
                {
                    return; // late or repeated
                }
                // their clock -> ours: the quickest a pose ever got here, creeping up slowly in case the way gets slower
                float offset = now - snapshot.Time;
                if (!_hasClock || offset < _clockOffset)
                {
                    _clockOffset = offset;
                    _hasClock = true;
                }
                else
                {
                    _clockOffset += (offset - _clockOffset) * 0.02f;
                }
                _snapshots.Add(snapshot);
                if (_snapshots.Count > 30)
                {
                    _snapshots.RemoveAt(0);
                }
            }

            /// <summary>Shows the gorilla as it was a moment ago, between the two poses around that moment.</summary>
            public void Show(float now)
            {
                if (!HasPose)
                {
                    return;
                }
                float time = now - _clockOffset - Delay;
                Snapshot a = _snapshots[0], b = a;
                for (int i = 1; i < _snapshots.Count; i++)
                {
                    b = _snapshots[i];
                    if (b.Time >= time)
                    {
                        break;
                    }
                    a = b;
                }
                float t = b.Time > a.Time ? Mathf.Clamp01((time - a.Time) / (b.Time - a.Time)) : 1f;
                if ((b.Root - a.Root).sqrMagnitude > 9f)
                {
                    t = t < 1f ? 0f : 1f; // a jump (a teleport): no gliding through the world
                }
                while (_snapshots.Count > 2 && _snapshots[1].Time < time)
                {
                    _snapshots.RemoveAt(0);
                }
                _root.SetPositionAndRotation(Vector3.Lerp(a.Root, b.Root, t), Quaternion.Slerp(a.Rotation, b.Rotation, t));
                for (int i = 0; i < _bones.Length; i++)
                {
                    if (_bones[i] != null)
                    {
                        _bones[i].localPosition = Vector3.Lerp(a.Positions[i], b.Positions[i], t);
                        _bones[i].localRotation = Quaternion.Slerp(a.Rotations[i], b.Rotations[i], t);
                    }
                }
                if (_body != null)
                {
                    _body.BakeMesh(_baked, true);
                    _baked.RecalculateBounds();
                    _bakedRenderer.transform.SetPositionAndRotation(_body.transform.position, _body.transform.rotation);
                }
                if (_face != null)
                {
                    _faceStandIn.SetPositionAndRotation(_face.position, _face.rotation);
                    _faceStandIn.localScale = _face.lossyScale;
                }
                Held.Update(_rightHand, _rightController, true);
                if (_head != null)
                {
                    Armor.Update(_head.TransformPoint(_headCentreInHead), _headRadius, _head.rotation * _lookInHead, _leftHand, _rightHand, true);
                }
            }

            public void Destroy()
            {
                Held.Destroy();
                Armor.Destroy();
                Object.Destroy(_holder);
                if (_baked != null)
                {
                    Object.Destroy(_baked);
                }
            }

            private Transform Find(Transform templateRoot, Transform part)
            {
                if (part == null)
                {
                    return null;
                }
                string path = part.name;
                for (Transform p = part.parent; p != null && p != templateRoot; p = p.parent)
                {
                    path = p.name + "/" + path;
                }
                // the same part in the copy (the holder's only child)
                return _holder.transform.GetChild(0).Find(path);
            }

            private static Transform ControllerOf(GorillaLocomotion.GTPlayer player)
            {
                object hand = HarmonyLib.AccessTools.Field(typeof(GorillaLocomotion.GTPlayer), "rightHand")?.GetValue(player);
                return hand == null ? null : HarmonyLib.AccessTools.Field(hand.GetType(), "controllerTransform")?.GetValue(hand) as Transform;
            }

            /// <summary>
            /// The copy's meshes must be skinned to the copy's own bones. Bones that Gorilla Tag keeps outside the gorilla
            /// (it can move some while you play in VR) would still be yours in the copy, so the other gorilla would sit on
            /// you and move with you. Those get a bone of their own in the copy, in the same place.
            /// </summary>
            private static int OwnBones(Transform copy, Transform template)
            {
                int foreign = 0;
                var made = new Dictionary<Transform, Transform>();
                foreach (SkinnedMeshRenderer skin in copy.GetComponentsInChildren<SkinnedMeshRenderer>(true))
                {
                    Transform[] bones = skin.bones;
                    bool changed = false;
                    for (int i = 0; i < bones.Length; i++)
                    {
                        if (bones[i] != null && !bones[i].IsChildOf(copy))
                        {
                            bones[i] = CopyOf(bones[i], copy, template, made);
                            changed = true;
                            foreign++;
                        }
                    }
                    if (changed)
                    {
                        skin.bones = bones;
                    }
                    if (skin.rootBone != null && !skin.rootBone.IsChildOf(copy))
                    {
                        skin.rootBone = CopyOf(skin.rootBone, copy, template, made);
                        foreign++;
                    }
                }
                return foreign;
            }

            private static Transform CopyOf(Transform bone, Transform copy, Transform template, Dictionary<Transform, Transform> made)
            {
                if (made.TryGetValue(bone, out Transform done))
                {
                    return done;
                }
                Transform result = null;
                if (bone.IsChildOf(template) && bone != template)
                {
                    string path = bone.name;
                    for (Transform p = bone.parent; p != template; p = p.parent)
                    {
                        path = p.name + "/" + path;
                    }
                    result = copy.Find(path);
                }
                if (result == null)
                {
                    // moved out of the gorilla: make it again on the bone it belongs to ("head_new" on "rig/head")
                    Transform templateOwner = template, copyOwner = copy;
                    string owner = bone.name.EndsWith("_new") ? bone.name.Substring(0, bone.name.Length - 4) : bone.name;
                    foreach (string path in Bones)
                    {
                        if (path == owner || path.EndsWith("/" + owner))
                        {
                            Transform t = template.Find(path), c = copy.Find(path);
                            if (t != null && c != null)
                            {
                                templateOwner = t;
                                copyOwner = c;
                            }
                            break;
                        }
                    }
                    result = new GameObject(bone.name).transform;
                    result.SetParent(copyOwner, false);
                    result.localPosition = templateOwner.InverseTransformPoint(bone.position);
                    result.localRotation = Quaternion.Inverse(templateOwner.rotation) * bone.rotation;
                    Vector3 s = bone.lossyScale, o = templateOwner.lossyScale;
                    result.localScale = new Vector3(s.x / o.x, s.y / o.y, s.z / o.z);
                }
                made[bone] = result;
                return result;
            }

            /// <summary>Where this gorilla's model really is, for the log: every part that's drawn, and how far from you.</summary>
            public string Describe(Vector3 you)
            {
                var text = new System.Text.StringBuilder(string.Format(System.Globalization.CultureInfo.InvariantCulture,
                    "{0:F1} m from you; drawn:", (_root.position - you).magnitude));
                foreach (Renderer renderer in _holder.GetComponentsInChildren<Renderer>(false))
                {
                    if (renderer.enabled)
                    {
                        text.AppendFormat(System.Globalization.CultureInfo.InvariantCulture, " {0} (layer {1}) {2:F1} m from you,",
                            renderer.name, renderer.gameObject.layer, (renderer.bounds.center - you).magnitude);
                    }
                }
                return text.ToString();
            }

            /// <summary>Removes every component but the transforms and meshes, before any of them ever ran.</summary>
            private static void StripToMeshes(GameObject copy)
            {
                // scripts first (they depend on the rest), then everything else; some depend on each other, so a few passes
                for (int pass = 0; pass < 4; pass++)
                {
                    bool left = false;
                    foreach (Component component in copy.GetComponentsInChildren<Component>(true))
                    {
                        if (component == null || component is Transform || component is MeshFilter || component is MeshRenderer
                            || component is SkinnedMeshRenderer)
                        {
                            continue;
                        }
                        if (pass == 0 && !(component is MonoBehaviour))
                        {
                            left = true;
                            continue;
                        }
                        try
                        {
                            Object.DestroyImmediate(component);
                        }
                        catch (System.Exception)
                        {
                            left = true;
                        }
                    }
                    if (!left)
                    {
                        break;
                    }
                }
            }
        }
    }
}
