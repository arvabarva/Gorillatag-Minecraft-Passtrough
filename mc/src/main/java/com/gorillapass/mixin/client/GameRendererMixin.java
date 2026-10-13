package com.gorillapass.mixin.client;

import com.gorillapass.client.FrameExporter;
import com.gorillapass.client.PassthroughClient;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
	/** Linked: the world is drawn into its own picture shaped like the headset's view (see PassthroughRenderer). */
	@Inject(method = "render", at = @At("HEAD"), cancellable = true)
	private void gorillapass$headsetShapedPicture(CallbackInfo ci) {
		GameRenderer renderer = (GameRenderer) (Object) this;
		PassthroughClient client = PassthroughClient.INSTANCE;
		PassthroughClient.Pose pose = client != null && client.isLinked() ? client.cameraPose() : null;
		if (pose != null && com.gorillapass.client.PassthroughRenderer.render(renderer, pose.renderWidth(), pose.renderHeight())) {
			// the window and wrist screen still get their picture (render's TAIL is skipped by cancelling)
			FrameExporter.INSTANCE.capturePanel(renderer.mainRenderTarget(), net.minecraft.client.Minecraft.getInstance().gui.screen() != null);
			ci.cancel();
			return;
		}
		com.gorillapass.client.PassthroughRenderer.restoreWindowSize(renderer);
	}

	@Inject(method = "resize", at = @At("TAIL"))
	private void gorillapass$windowResized(int width, int height, CallbackInfo ci) {
		com.gorillapass.client.PassthroughRenderer.windowResized();
	}

	/**
	 * The world is drawn and its depth is still there (drawing the hand clears it next): hand this frame to Gorilla
	 * Tag.
	 */
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V"))
	private void gorillapass$shareWorld(CallbackInfo ci) {
		PassthroughClient client = PassthroughClient.INSTANCE;
		PassthroughClient.Pose pose = client != null ? client.takeRenderedPose() : null;
		GameRenderer renderer = (GameRenderer) (Object) this;
		if (pose == null) {
			return;
		}
		FrameExporter.INSTANCE.captureWorld(renderer.mainRenderTarget(),
			renderer.gameRenderState().levelRenderState.cameraRenderState.projectionMatrix, pose);
	}

	/** The whole window, HUD and menus included, for the screen on the gorilla's wrist. */
	@Inject(method = "render", at = @At("TAIL"))
	private void gorillapass$shareWindow(CallbackInfo ci) {
		PassthroughClient client = PassthroughClient.INSTANCE;
		if (client != null && client.isLinked()) {
			FrameExporter.INSTANCE.capturePanel(((GameRenderer) (Object) this).mainRenderTarget(),
				net.minecraft.client.Minecraft.getInstance().gui.screen() != null);
		}
	}

	/** The gorilla has its own hands in Gorilla Tag; Steve's arm would float in the middle of the picture. */
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void gorillapass$noScreenHand(CameraRenderState cameraState, PlayerRenderState playerState, GpuTextureView depthTextureView, CallbackInfo ci) {
		if (PassthroughClient.INSTANCE != null && PassthroughClient.INSTANCE.isLinked()) {
			ci.cancel();
		}
	}

	/** Shaking or tilting the picture would fight the headset (and make you sick). */
	@Inject(method = {"bobHurt", "bobView"}, at = @At("HEAD"), cancellable = true)
	private void gorillapass$noScreenShake(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
		if (PassthroughClient.INSTANCE != null && PassthroughClient.INSTANCE.isLinked()) {
			ci.cancel();
		}
	}
}
