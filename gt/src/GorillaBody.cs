using GorillaLocomotion;
using UnityEngine;

namespace GorillaPassthrough
{
    /// <summary>
    /// Gorilla Tag's side of the body: every frame tells Minecraft where the gorilla's head is (Minecraft draws from
    /// there) and where its feet are (the Minecraft player follows), and follows Minecraft's own teleports (spawning,
    /// respawning, portals, ender pearls).
    /// </summary>
    internal sealed class GorillaBody
    {
        private const float FovMarginDegrees = 5f;
        private const int MaxPictureWidth = 2560;
        private const float MaxHalfAngleDegrees = 70f;

        private readonly Link _link;
        private readonly BlockWorld _world;
        private readonly PassthroughView _view;
        private int _poseId;
        private int _teleportPending;
        private int _teleportDone;
        private bool _placed;
        private Vector3 _teleportTarget;
        private int _settleFrames;
        private bool _holding;
        private float _tanH = 1.5f, _tanV = 1.5f, _maxDistance = 500f;
        private float _nextFovCheck;
        private int _pictureWidth = 1920, _pictureHeight = 1920;

        public bool Placed => _placed;

        public bool Holding => _holding;
        public float TanH => _tanH;
        public float TanV => _tanV;
        public float MaxDistance => _maxDistance;

        public GorillaBody(Link link, BlockWorld world, PassthroughView view)
        {
            _link = link;
            _world = world;
            _view = view;
        }

        /// <summary>Minecraft put the player somewhere: move the gorilla there and hold it until the ground exists.</summary>
        public void Teleport(int id, double x, double y, double z)
        {
            _world.AnchorAt(x, z);
            _teleportPending = id;
            _teleportTarget = _world.ToUnity(x, y, z);
            _placed = true;
            _settleFrames = 0;
            MoveFeetTo(_teleportTarget);
            Plugin.Log.LogInfo(string.Format("Gorilla Passthrough: teleport {0} to Minecraft ({1:F1}, {2:F1}, {3:F1})", id, x, y, z));
        }

        public void Reset()
        {
            _placed = false;
            _teleportPending = 0;
            _teleportDone = 0;
            _holding = false;
        }

        /// <summary>Called late every frame, after Gorilla Tag moved the gorilla.</summary>
        public void Update(GTPlayer player, Camera camera)
        {
            if (!_placed || player == null || camera == null)
            {
                return;
            }

            Vector3 feet = Feet(player);
            ToSection(feet, out int sx, out int sy, out int sz);
            bool groundKnown = _world.HasSection(sx, sy, sz) && _world.HasSection(sx, sy - 1, sz);

            if (_teleportDone != _teleportPending)
            {
                // hover where Minecraft put us until the blocks under us have colliders, or we'd fall through the world
                player.SetPlayerVelocity(Vector3.zero);
                if (Vector3.Distance(feet, _teleportTarget) > 0.3f && _settleFrames < 30)
                {
                    MoveFeetTo(_teleportTarget);
                }
                _settleFrames++;
                ToSection(_teleportTarget, out int tx, out int ty, out int tz);
                if (_world.HasSection(tx, ty, tz) && _world.HasSection(tx, ty - 1, tz))
                {
                    _teleportDone = _teleportPending;
                    player.playerRigidBody.useGravity = true;
                }
            }
            else
            {
                // safety net: never fall into parts of the world Minecraft hasn't sent yet
                bool hold = !groundKnown;
                if (hold)
                {
                    player.SetPlayerVelocity(Vector3.zero);
                }
                if (hold != _holding)
                {
                    _holding = hold;
                    player.playerRigidBody.useGravity = !hold;
                }
            }

            UpdateFov(camera);
            SendPose(player, camera.transform, feet);
        }

        private void SendPose(GTPlayer player, Transform head, Vector3 feet)
        {
            _poseId++;
            _view.RememberPose(_poseId, head.position, head.rotation);
            _world.ToMinecraft(head.position, out double hx, out double hy, out double hz);
            _world.ToMinecraft(feet, out double fx, out double fy, out double fz);
            Quaternion q = head.rotation;
            Vector3 look = head.forward;
            // Minecraft's yaw 0 faces south (+z), which is Unity -z
            float yaw = Mathf.Atan2(-look.x, -look.z) * Mathf.Rad2Deg;
            float pitch = -Mathf.Asin(Mathf.Clamp(look.y, -1f, 1f)) * Mathf.Rad2Deg;
            int flags = OnGround(player) ? Protocol.PoseOnGround : 0;
            // both hands, so other players' Minecraft can draw this gorilla's arms reaching for them
            GorillaTagger tagger = GorillaTagger.Instance;
            Vector3 left = tagger != null ? tagger.leftHandTransform.position : feet;
            Vector3 right = tagger != null ? tagger.rightHandTransform.position : feet;
            _world.ToMinecraft(left, out double lx, out double ly, out double lz);
            _world.ToMinecraft(right, out double rx, out double ry, out double rz);

            // mirroring z turns a Unity rotation (x, y, z, w) into the Minecraft rotation (-x, -y, z, w)
            _link.Send(Protocol.C_Pose, new JavaWriter()
                .Int(_poseId).Int(_teleportDone)
                .Double(hx).Double(hy).Double(hz)
                .Float(-q.x).Float(-q.y).Float(q.z).Float(q.w)
                .Float(_tanH).Float(_tanV).Float(_maxDistance)
                .Double(fx).Double(fy).Double(fz)
                .Float(yaw).Float(pitch).Byte(flags)
                .Double(lx).Double(ly).Double(lz).Double(rx).Double(ry).Double(rz)
                .Int(_pictureWidth).Int(_pictureHeight));
        }

        /// <summary>
        /// Minecraft draws one wide view from the middle of the head; it has to cover both eyes' views plus a margin
        /// for head turns while the frame is on its way.
        /// </summary>
        private void UpdateFov(Camera camera)
        {
            if (Time.time < _nextFovCheck)
            {
                return;
            }
            _nextFovCheck = Time.time + 1f;
            float tanLeft, tanRight, tanUp, tanDown;
            if (camera.stereoEnabled)
            {
                tanLeft = tanRight = tanUp = tanDown = 0f;
                foreach (Camera.StereoscopicEye eye in new[] { Camera.StereoscopicEye.Left, Camera.StereoscopicEye.Right })
                {
                    Matrix4x4 p = camera.GetStereoProjectionMatrix(eye);
                    tanLeft = Mathf.Max(tanLeft, Mathf.Abs((p.m02 - 1f) / p.m00));
                    tanRight = Mathf.Max(tanRight, Mathf.Abs((p.m02 + 1f) / p.m00));
                    tanDown = Mathf.Max(tanDown, Mathf.Abs((p.m12 - 1f) / p.m11));
                    tanUp = Mathf.Max(tanUp, Mathf.Abs((p.m12 + 1f) / p.m11));
                }
            }
            else
            {
                tanUp = tanDown = Mathf.Tan(camera.fieldOfView * 0.5f * Mathf.Deg2Rad);
                tanLeft = tanRight = tanUp * camera.aspect;
            }
            _tanH = Widen(Mathf.Max(tanLeft, tanRight));
            _tanV = Widen(Mathf.Max(tanUp, tanDown));

            if (camera.farClipPlane < 650f)
            {
                camera.farClipPlane = 650f;
            }
            _maxDistance = Mathf.Min(camera.farClipPlane * 0.95f, 600f);

            // the size Minecraft draws the world at: shaped like this view, so the pixels are spread evenly over it
            int height = Mathf.Clamp(Plugin.PictureHeight.Value, 480, 2560);
            int width = Mathf.RoundToInt(height * _tanH / _tanV);
            if (width > MaxPictureWidth)
            {
                height = Mathf.RoundToInt(height * (float)MaxPictureWidth / width);
                width = MaxPictureWidth;
            }
            _pictureWidth = width;
            _pictureHeight = height;
        }

        private static float Widen(float tan)
        {
            float degrees = Mathf.Atan(tan) * Mathf.Rad2Deg + FovMarginDegrees;
            return Mathf.Tan(Mathf.Min(degrees, MaxHalfAngleDegrees) * Mathf.Deg2Rad);
        }

        private void ToSection(Vector3 unity, out int sx, out int sy, out int sz)
        {
            _world.ToMinecraft(unity, out double x, out double y, out double z);
            sx = (int)System.Math.Floor(x / 16.0);
            sy = (int)System.Math.Floor(y / 16.0);
            sz = (int)System.Math.Floor(z / 16.0);
        }

        private static void MoveFeetTo(Vector3 target)
        {
            GTPlayer player = GTPlayer.Instance;
            if (player == null)
            {
                return;
            }
            // GTPlayer.TeleportTo puts its camera at the given point, so aim the camera where the feet should go
            // plus however far the camera is from the feet right now
            Vector3 camera = player.mainCamera != null ? player.mainCamera.transform.position : player.headCollider.transform.position;
            Vector3 destination = target + (camera - Feet(player)) + Vector3.up * 0.02f;
            player.playerRigidBody.useGravity = false;
            player.TeleportTo(destination, player.transform.rotation, false, false);
            player.SetPlayerVelocity(Vector3.zero);
        }

        public static Vector3 Feet(GTPlayer player)
        {
            Bounds body = player.bodyCollider.bounds;
            return new Vector3(body.center.x, body.min.y, body.center.z);
        }

        private bool OnGround(GTPlayer player)
        {
            Bounds body = player.bodyCollider.bounds;
            var probe = new Vector3(body.center.x, body.min.y + 0.05f, body.center.z);
            foreach (Collider collider in Physics.OverlapSphere(probe, 0.1f))
            {
                if (_world.IsOurCollider(collider))
                {
                    return true;
                }
            }
            return false;
        }
    }
}
