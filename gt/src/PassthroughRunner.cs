using System;
using System.Diagnostics;
using System.Linq;
using GorillaLocomotion;
using UnityEngine;

namespace GorillaPassthrough
{
    /// <summary>Drives the passthrough every frame: connect to Minecraft, read its messages and frames, send the gorilla.</summary>
    internal sealed class PassthroughRunner : MonoBehaviour
    {
        private const float MessageBudgetMillis = 4f;

        private enum Phase { WaitingForGorillaTag, Connecting, Playing, Failed }

        private Phase _phase = Phase.WaitingForGorillaTag;
        private readonly Link _link = new Link();
        private readonly FrameReader _frames = new FrameReader();
        private BlockWorld _world;
        private PassthroughView _view;
        private GorillaBody _body;
        private Controls _controls;
        private WristScreen _wrist;
        private MenuPanel _menu;
        private HeldItemModel _held;
        private ArmorModel _armor;
        private OtherGorillas _others;
        private float _othersSince = -1f;
        private int _othersShots;
        private Transform _rightController;
        private Camera _camera;
        private float _nextConnectTry;
        private float _nextStatus;
        private float _lastHealth = -1f;
        private bool _loggedWaiting;

        private void Update()
        {
            try
            {
                switch (_phase)
                {
                    case Phase.WaitingForGorillaTag:
                        if (GTPlayer.Instance != null && GorillaTagger.Instance != null && GorillaTagger.Instance.mainCamera != null)
                        {
                            Setup();
                        }
                        break;
                    case Phase.Connecting:
                        Connect();
                        break;
                    case Phase.Playing:
                        ReadMessages();
                        break;
                }
            }
            catch (Exception e)
            {
                Plugin.Log.LogError("Gorilla Passthrough: " + e);
                _phase = Phase.Failed;
            }
        }

        /// <summary>After Gorilla Tag moved the gorilla this frame: send the pose, show Minecraft's newest frame.</summary>
        private void LateUpdate()
        {
            if (_phase != Phase.Playing)
            {
                return;
            }
            try
            {
                GTPlayer player = GTPlayer.Instance;
                _body.Update(player, _camera);
                if (_frames.IsOpen || _frames.TryOpen())
                {
                    _view.Update(_frames, _body.MaxDistance);
                }
                Transform[] hands = { GorillaTagger.Instance.leftHandTransform, GorillaTagger.Instance.rightHandTransform };
                ControllerInputPoller input = ControllerInputPoller.instance;
                if (_body.Placed)
                {
                    _controls.Update(player, input, hands, _menu.IsOpen);
                }
                if (_rightController == null)
                {
                    _rightController = ControllerOf(player, "rightHand") ?? hands[1];
                }
                _menu.Update(_frames, _camera.transform, _rightController, input);
                _held.Update(hands[1], _rightController, _body.Placed && !_menu.IsOpen);
                _armor.Update(player, _camera.transform, hands[0], hands[1], _body.Placed);
                if (_body.Placed)
                {
                    _others.SendOwnPose(_link, GorillaTagger.Instance.offlineVRRig);
                }
                _others.Update();
                _wrist.Update(_frames, hands[0], _camera.transform, _controls.WristScreenOn && !_menu.IsOpen);
                Diagnostics(player);
            }
            catch (Exception e)
            {
                Plugin.Log.LogError("Gorilla Passthrough: " + e);
                _phase = Phase.Failed;
            }
        }

        private void Setup()
        {
            if (GameObject.Find("GorillaCraft") != null)
            {
                Plugin.Log.LogError("Gorilla Passthrough: the GorillaCraft mod is running too. Set Enabled = false in "
                    + "BepInEx/config/com.gorillacraft.gorillatag.cfg to use the passthrough.");
                _phase = Phase.Failed;
                return;
            }
            OfflineGuard.LeaveRoom();
            _camera = GorillaTagger.Instance.mainCamera.GetComponent<Camera>();
            _world = new BlockWorld(FindWalkableLayer());
            _view = new PassthroughView(Plugin.FlipVertical.Value);
            _body = new GorillaBody(_link, _world, _view);
            _controls = new Controls(_link, _world);
            var panel = new PanelTexture();
            _wrist = new WristScreen(panel);
            _menu = new MenuPanel(_link, panel);
            _held = new HeldItemModel();
            _armor = new ArmorModel(_camera);
            _others = new OtherGorillas(_world);
            Plugin.Log.LogInfo("Gorilla Passthrough: ready, camera far plane " + _camera.farClipPlane + ", stereo " + _camera.stereoEnabled);
            _phase = Phase.Connecting;
        }

        private void Connect()
        {
            if (Time.time < _nextConnectTry)
            {
                return;
            }
            _nextConnectTry = Time.time + 1f;
            if (!_link.TryConnect())
            {
                if (!_loggedWaiting)
                {
                    _loggedWaiting = true;
                    Plugin.Log.LogInfo("Gorilla Passthrough: waiting for Minecraft (start it with the passthrough mod and open a single-player world)");
                }
                return;
            }
            Plugin.Log.LogInfo("Gorilla Passthrough: connected to Minecraft");
            // your fur colour, so other players' Minecraft draws your gorilla in it
            Color fur = FurColor();
            _link.Send(Protocol.C_Hello, new JavaWriter().Int(Protocol.Version).Float(DisplayRefreshRate()).Float(fur.r).Float(fur.g).Float(fur.b));
            _phase = Phase.Playing;
        }

        private void ReadMessages()
        {
            if (!_link.IsOpen)
            {
                Plugin.Log.LogInfo("Gorilla Passthrough: lost Minecraft, waiting for it again");
                _view.Hide();
                _world.Clear();
                _body.Reset();
                _menu.SetOpen(false);
                _others.Clear();
                ReleaseGorilla();
                _loggedWaiting = false;
                _phase = Phase.Connecting;
                return;
            }
            var budget = Stopwatch.StartNew();
            while (budget.Elapsed.TotalMilliseconds < MessageBudgetMillis && _link.TryReceive(out Message message))
            {
                Handle(message);
            }
        }

        private void Handle(Message message)
        {
            var reader = new JavaReader(message.Payload);
            switch (message.Type)
            {
                case Protocol.S_Hello:
                    int version = reader.Int();
                    string world = reader.Utf();
                    Plugin.Log.LogInfo("Gorilla Passthrough: Minecraft world '" + world + "' (protocol " + version + ")");
                    if (version != Protocol.Version)
                    {
                        Plugin.Log.LogError("Gorilla Passthrough: Minecraft mod protocol " + version + ", this plugin " + Protocol.Version + "; update both");
                    }
                    break;
                case Protocol.S_Section:
                    _world.ReadSection(reader);
                    break;
                case Protocol.S_UnloadSection:
                    _world.Unload(reader.Int(), reader.Int(), reader.Int());
                    break;
                case Protocol.S_Clear:
                    Plugin.Log.LogInfo("Gorilla Passthrough: entering " + reader.Utf());
                    _world.Clear();
                    break;
                case Protocol.S_Teleport:
                    int id = reader.Int();
                    _body.Teleport(id, reader.Double(), reader.Double(), reader.Double());
                    break;
                case Protocol.S_Entities:
                    _controls.ReadEntities(reader);
                    break;
                case Protocol.S_Status:
                    ReadStatus(reader);
                    break;
                case Protocol.S_RemoteRig:
                    _others.ReadPose(reader, GorillaTagger.Instance.offlineVRRig, GTPlayer.Instance);
                    break;
                case Protocol.S_RemoteEquipment:
                    _others.ReadEquipment(reader);
                    break;
                case Protocol.S_RemoteGone:
                    _others.Remove(reader.Int());
                    break;
                case Protocol.S_Armor:
                    _armor.Read(reader);
                    break;
                case Protocol.S_Held:
                    _held.Read(reader);
                    break;
                case Protocol.S_Screen:
                    bool open = reader.Byte() != 0;
                    Plugin.Log.LogInfo("Gorilla Passthrough: Minecraft menu " + (open ? "opened: " + reader.Utf() : "closed"));
                    _menu.SetOpen(open);
                    break;
            }
        }

        private void ReadStatus(JavaReader reader)
        {
            float health = reader.Float();
            reader.Float();
            reader.Int();
            reader.Int();
            reader.Int();
            reader.Byte();
            bool hurt = reader.Byte() != 0;
            int light = reader.Byte();
            _held.SetLight(light);
            _armor.SetLight(light);
            _others.SetLight(light);
            if (hurt || (_lastHealth >= 0f && health < _lastHealth))
            {
                // you feel Minecraft's hits in both hands
                GorillaTagger.Instance?.StartVibration(true, 0.8f, 0.15f);
                GorillaTagger.Instance?.StartVibration(false, 0.8f, 0.15f);
            }
            _lastHealth = health;
        }

        private void ReleaseGorilla()
        {
            GTPlayer player = GTPlayer.Instance;
            if (player != null)
            {
                player.playerRigidBody.useGravity = true;
            }
        }

        private float _firstFrameAt = -1f;
        private int _screenshots;

        private void Diagnostics(GTPlayer player)
        {
            if (_view.FramesShown > 0 && _firstFrameAt < 0f)
            {
                _firstFrameAt = Time.time;
                Plugin.Log.LogInfo("Gorilla Passthrough: Minecraft's picture is showing");
                if (Plugin.DebugScreenshots.Value)
                {
                    RigDump.Write(GorillaTagger.Instance.offlineVRRig);
                }
            }
            if (Plugin.DebugScreenshots.Value && _firstFrameAt >= 0f && _screenshots < 2
                && Time.time - _firstFrameAt > (_screenshots == 0 ? 10f : 25f))
            {
                _screenshots++;
                string path = System.IO.Path.Combine(BepInEx.Paths.BepInExRootPath, "gorillapass-" + _screenshots + ".png");
                ScreenCapture.CaptureScreenshot(path);
                Plugin.Log.LogInfo("Gorilla Passthrough: screenshot " + path);
                SaveHeadView(System.IO.Path.Combine(BepInEx.Paths.BepInExRootPath, "gorillapass-head-" + _screenshots + ".png"), false);
                SaveHeadView(System.IO.Path.Combine(BepInEx.Paths.BepInExRootPath, "gorillapass-mirror-" + _screenshots + ".png"), true);
                // what an eye a little to the side sees (where edges show what Minecraft couldn't see), and after a head move
                Transform head = _camera.transform;
                SaveHeadView(System.IO.Path.Combine(BepInEx.Paths.BepInExRootPath, "gorillapass-eye-" + _screenshots + ".png"), false,
                    head.position + head.right * 0.032f, head.rotation);
                SaveHeadView(System.IO.Path.Combine(BepInEx.Paths.BepInExRootPath, "gorillapass-moved-" + _screenshots + ".png"), false,
                    head.position + head.right * 0.15f + head.up * 0.05f, head.rotation);
            }
            if (Plugin.DebugScreenshots.Value && _others.Count > 0 && _othersShots < 2)
            {
                if (_othersSince < 0f)
                {
                    _othersSince = Time.time;
                }
                else if (Time.time - _othersSince > 4f + 8f * _othersShots && _others.TryGetFirst(out Vector3 other))
                {
                    // from above, both gorillas: where the other one is really drawn
                    _othersShots++;
                    Vector3 you = _camera.transform.position;
                    Vector3 middle = (you + other) * 0.5f;
                    Vector3 side = Vector3.Cross(other - you, Vector3.up).normalized;
                    Vector3 at = middle + side * 3f + Vector3.up * 2.5f;
                    SaveHeadView(System.IO.Path.Combine(BepInEx.Paths.BepInExRootPath, "gorillapass-others-" + _othersShots + ".png"), false,
                        at, Quaternion.LookRotation(middle - at, Vector3.up));
                    // and the other one up close, from your side (its face, what it holds)
                    Vector3 face = other + Vector3.up * 0.35f;
                    Vector3 near = face + Vector3.ProjectOnPlane(you - other, Vector3.up).normalized * 1.1f;
                    SaveHeadView(System.IO.Path.Combine(BepInEx.Paths.BepInExRootPath, "gorillapass-othersmirror-" + _othersShots + ".png"), false,
                        near, Quaternion.LookRotation(face - near, Vector3.up));
                    Plugin.Log.LogInfo("Gorilla Passthrough: other gorilla at " + other + ", you at " + you);
                }
            }
            if (Time.time < _nextStatus)
            {
                return;
            }
            _nextStatus = Time.time + 5f;
            Vector3 feet = _world.Anchored ? _world.ToMinecraftLocal(GorillaBody.Feet(player)) : Vector3.zero;
            Plugin.Log.LogInfo(string.Format(
                "Gorilla Passthrough status: feet ({0:F1}, {1:F1}, {2:F1}) sections {3} frames shown {4} dropped {5} fov tan {6:F2}x{7:F2} holding {8} fps {9:F0}",
                feet.x, feet.y, feet.z, _world.SectionCount, _view.FramesShown, _view.FramesDropped, _body.TanH, _body.TanV, _body.Holding,
                1f / Time.smoothDeltaTime));
        }

        /// <summary>
        /// What the gorilla's own eyes see (the desktop window shows a third-person camera instead): a copy of the head
        /// camera rendered once into a picture.
        /// </summary>
        private void SaveHeadView(string path, bool mirror, Vector3? from = null, Quaternion lookAt = default)
        {
            var target = new RenderTexture(1600, 900, 24, RenderTextureFormat.ARGB32);
            var go = new GameObject("Gorilla Passthrough debug eye");
            try
            {
                Camera eye = go.AddComponent<Camera>();
                eye.CopyFrom(_camera);
                eye.stereoTargetEye = StereoTargetEyeMask.None;
                eye.fieldOfView = 90f;
                eye.aspect = 16f / 9f;
                eye.transform.SetPositionAndRotation(_camera.transform.position, _camera.transform.rotation);
                if (mirror)
                {
                    // like a mirror 1.3 m in front of the gorilla: everything (also mirror-only things like the helmet)
                    Transform head = _camera.transform;
                    Vector3 flat = Vector3.ProjectOnPlane(head.forward, Vector3.up).normalized;
                    Vector3 at = head.position + flat * 1.3f + Vector3.up * 0.1f;
                    eye.transform.SetPositionAndRotation(at, Quaternion.LookRotation(head.position - Vector3.up * 0.2f - at, Vector3.up));
                    eye.cullingMask = ~0;
                }
                if (from.HasValue)
                {
                    eye.transform.SetPositionAndRotation(from.Value, lookAt);
                    eye.cullingMask = ~0;
                }
                eye.targetTexture = target;
                eye.Render();
                RenderTexture previous = RenderTexture.active;
                RenderTexture.active = target;
                var picture = new Texture2D(target.width, target.height, TextureFormat.RGB24, false);
                picture.ReadPixels(new Rect(0, 0, target.width, target.height), 0, 0);
                picture.Apply();
                RenderTexture.active = previous;
                System.IO.File.WriteAllBytes(path, picture.EncodeToPNG());
                Destroy(picture);
                Plugin.Log.LogInfo("Gorilla Passthrough: head view " + path);
            }
            catch (Exception e)
            {
                Plugin.Log.LogWarning("Gorilla Passthrough: couldn't save the head view: " + e.Message);
            }
            finally
            {
                Destroy(go);
                target.Release();
            }
        }

        /// <summary>Your Gorilla Tag fur colour, as Gorilla Tag saves it (the rig's own colour stays empty offline).</summary>
        private static Color FurColor()
        {
            float r = PlayerPrefs.GetFloat("redValue", -1f), g = PlayerPrefs.GetFloat("greenValue", -1f), b = PlayerPrefs.GetFloat("blueValue", -1f);
            if (r < 0f || g < 0f || b < 0f)
            {
                return new Color(0.55f, 0.35f, 0.24f);
            }
            return new Color(r, g, b);
        }

        /// <summary>How many pictures a second the headset shows (Minecraft draws no more than that).</summary>
        private static float DisplayRefreshRate()
        {
#pragma warning disable CS0618
            float rate = UnityEngine.XR.XRDevice.refreshRate;
#pragma warning restore CS0618
            if (rate <= 1f)
            {
                rate = (float)Screen.currentResolution.refreshRateRatio.value;
            }
            Plugin.Log.LogInfo("Gorilla Passthrough: display refresh rate " + rate + " Hz");
            return rate;
        }

        /// <summary>The tracked controller (not the hand model), whose forward is where you point.</summary>
        private static Transform ControllerOf(GTPlayer player, string field)
        {
            object hand = HarmonyLib.AccessTools.Field(typeof(GTPlayer), field)?.GetValue(player);
            return hand == null ? null : HarmonyLib.AccessTools.Field(hand.GetType(), "controllerTransform")?.GetValue(hand) as Transform;
        }

        /// <summary>The physics layer Gorilla Tag's own ground uses, so its movement treats blocks the same way.</summary>
        private static int FindWalkableLayer()
        {
            GTPlayer player = GTPlayer.Instance;
            int mask = player.locomotionEnabledLayers.value;
            Vector3 from = player.headCollider.transform.position;
            foreach (RaycastHit hit in Physics.RaycastAll(from, Vector3.down, 20f).OrderBy(h => h.distance))
            {
                int layer = hit.collider.gameObject.layer;
                if ((mask & (1 << layer)) != 0)
                {
                    Plugin.Log.LogInfo("Gorilla Passthrough: blocks use Gorilla Tag ground layer " + layer + " (" + LayerMask.LayerToName(layer) + ")");
                    return layer;
                }
            }
            for (int layer = 0; layer < 32; layer++)
            {
                if ((mask & (1 << layer)) != 0)
                {
                    return layer;
                }
            }
            return 0;
        }
    }
}
