package com.gorillapass.client;

import com.gorillapass.mixin.client.GameRendererAccessor;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import java.util.Collections;
import net.minecraft.client.Minecraft;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.WindowRenderState;
import org.jspecify.annotations.Nullable;

/**
 * GameRenderer.render() while Gorilla Tag is linked: the world is drawn into its own picture, shaped like the headset's
 * view (about square) at the size Gorilla Tag asks for, instead of into the 16:9 window, so every pixel ends up where
 * your eyes are. The window still gets the HUD and menus, over the world picture stretched to fit, for the monitor and
 * the wrist and menu screens.
 */
public final class PassthroughRenderer {
	private static @Nullable RenderTarget target;
	private static boolean levelAtTargetSize;

	private PassthroughRenderer() {
	}

	/** Draws the frame; false = not now (Minecraft draws normally). */
	public static boolean render(GameRenderer renderer, int width, int height) {
		GameRenderState state = renderer.gameRenderState();
		if (!state.shouldRenderLevel || width < 64 || height < 64) {
			return false;
		}
		GameRendererAccessor access = (GameRendererAccessor) renderer;
		Minecraft minecraft = Minecraft.getInstance();
		WindowRenderState window = state.windowRenderState;
		OptionsRenderState options = state.optionsRenderState;
		RenderTarget windowTarget = access.gorillapass$getMainRenderTarget();
		int windowWidth = window.width;
		int windowHeight = window.height;
		if (windowTarget.width != windowWidth || windowTarget.height != windowHeight) {
			windowTarget.resize(windowWidth, windowHeight);
		}
		RenderTarget world = ensureTarget(renderer, width, height);
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		float partialTicks = state.levelRenderState.worldPartialTicks;

		// the world, at the headset's shape
		access.gorillapass$preparePostEffects(state.requestedPostEffects);
		access.gorillapass$getLightmap().render(state.lightmapRenderState);
		try {
			access.gorillapass$setMainRenderTarget(world);
			window.width = width;
			window.height = height;
			encoder.clearColorAndDepthTextures(world.getColorTexture(), state.guiRenderState.clearColorOverride, world.getDepthTexture(), 0.0);
			access.gorillapass$getGlobalSettingsUniform().update(width, height, options.glintStrength, state.levelRenderState.gameTime, partialTicks,
				options.menuBackgroundBlurriness, state.levelRenderState.cameraRenderState.pos, options.textureFiltering == TextureFilteringMethod.RGSS);
			renderer.renderLevel();
			access.gorillapass$tryTakeScreenshotIfNeeded();
			minecraft.levelRenderer.blitEntityOutline();
			access.gorillapass$applyPostEffects();
		} finally {
			window.width = windowWidth;
			window.height = windowHeight;
			access.gorillapass$setMainRenderTarget(windowTarget);
		}

		// the window: the world stretched to fit, then the HUD and menus on top
		encoder.clearColorAndDepthTextures(windowTarget.getColorTexture(), state.guiRenderState.clearColorOverride, windowTarget.getDepthTexture(), 0.0);
		world.blitAndBlendToTexture(windowTarget.getColorTextureView(), windowTarget.getDepthTextureView());
		access.gorillapass$getGlobalSettingsUniform().update(windowWidth, windowHeight, options.glintStrength, state.levelRenderState.gameTime,
			partialTicks, options.menuBackgroundBlurriness, state.levelRenderState.cameraRenderState.pos, options.textureFiltering == TextureFilteringMethod.RGSS);
		access.gorillapass$getFogRenderer().endFrame();
		encoder.clearDepthTexture(windowTarget.getDepthTexture(), 0.0);
		renderer.lighting().setupFor(Lighting.Entry.ITEMS_3D);
		access.gorillapass$setUseUiLightmap(true);
		access.gorillapass$getGuiRenderer().render();
		access.gorillapass$getGuiRenderer().endFrame();
		access.gorillapass$setUseUiLightmap(false);
		renderer.renderBuffers().endFrame();
		access.gorillapass$getResourcePool().endFrame();
		return true;
	}

	/** Minecraft resized its own images to the window: the next linked frame sizes them back to the picture. */
	public static void windowResized() {
		levelAtTargetSize = false;
	}

	/** After drawing at the headset's shape, the world's helper images go back to the window's size. */
	public static void restoreWindowSize(GameRenderer renderer) {
		if (levelAtTargetSize && target != null) {
			levelAtTargetSize = false;
			WindowRenderState window = renderer.gameRenderState().windowRenderState;
			renderer.resize(window.width, window.height);
		}
	}

	private static RenderTarget ensureTarget(GameRenderer renderer, int width, int height) {
		if (target == null || target.width != width || target.height != height) {
			if (target != null) {
				target.destroyBuffers();
			}
			target = new TextureTarget("Gorilla Passthrough world", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
			levelAtTargetSize = false;
		}
		if (!levelAtTargetSize) {
			// the images the world is drawn with (entity outlines, the 3D hand, effects) follow the picture's size
			levelAtTargetSize = true;
			GameRendererAccessor access = (GameRendererAccessor) renderer;
			access.gorillapass$getResourcePool().clear();
			access.gorillapass$getHud3DTarget().resize(width, height);
			Minecraft.getInstance().levelRenderer.resize(width, height);
		}
		return target;
	}
}
