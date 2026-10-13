package com.gorillapass.mixin.client;

import com.gorillapass.client.PassthroughClient;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private @Nullable Entity entity;
	@Shadow
	@Final
	private Quaternionf rotation;
	@Shadow
	@Final
	private Vector3f forwards;
	@Shadow
	@Final
	private Vector3f up;
	@Shadow
	@Final
	private Vector3f left;
	@Shadow
	private float xRot;
	@Shadow
	private float yRot;
	@Shadow
	private int matrixPropertiesDirty;
	@Shadow
	private Frustum cullFrustum;
	@Shadow
	private float depthFar;
	@Shadow
	private float fov;
	@Shadow
	@Final
	private Projection projection;

	@Shadow
	protected abstract void setPosition(Vec3 position);

	@Shadow
	public abstract boolean isDetached();

	@Shadow
	public abstract Matrix4f getViewRotationMatrix(Matrix4f dest);

	/**
	 * Minecraft's camera is the gorilla's head in Gorilla Tag: same spot, same tilt, and a field of view wide enough
	 * to cover the headset (plus a margin, since the picture arrives a little late).
	 */
	@Inject(method = "update", at = @At("TAIL"))
	private void gorillapass$gorillaEyes(DeltaTracker deltaTracker, CallbackInfo ci) {
		PassthroughClient client = PassthroughClient.INSTANCE;
		PassthroughClient.Pose pose = client != null ? client.cameraPose() : null;
		if (pose == null || this.entity == null || this.entity != Minecraft.getInstance().player || this.isDetached()) {
			return;
		}
		Vec3 head = new Vec3(pose.headX(), pose.headY(), pose.headZ());
		this.setPosition(head);
		this.rotation.set(pose.qx(), pose.qy(), pose.qz(), pose.qw()).normalize();
		this.forwards.set(0.0F, 0.0F, -1.0F).rotate(this.rotation);
		this.up.set(0.0F, 1.0F, 0.0F).rotate(this.rotation);
		this.left.set(-1.0F, 0.0F, 0.0F).rotate(this.rotation);
		this.yRot = (float) Math.toDegrees(Math.atan2(-this.forwards.x, this.forwards.z));
		this.xRot = (float) Math.toDegrees(-Math.asin(Math.clamp(this.forwards.y, -1.0F, 1.0F)));
		this.matrixPropertiesDirty |= 3;

		float verticalFov = (float) Math.toDegrees(2.0 * Math.atan(pose.tanV()));
		this.fov = verticalFov;
		// width/height only set the aspect ratio: tanH/tanV, whatever shape the Minecraft window is
		this.projection.setupPerspective(0.05F, this.depthFar, verticalFov, pose.tanH(), pose.tanV());

		boolean zZeroToOne = RenderSystem.getDevice().getDeviceInfo().isZZeroToOne();
		Matrix4f cullProjection = new Matrix4f().perspective((float) Math.toRadians(verticalFov), pose.tanH() / pose.tanV(), 0.05F,
			this.depthFar, zZeroToOne);
		this.cullFrustum = new Frustum(this.getViewRotationMatrix(new Matrix4f()), cullProjection);
		this.cullFrustum.prepare(head.x, head.y, head.z);
		client.markRendered(pose);
	}
}
