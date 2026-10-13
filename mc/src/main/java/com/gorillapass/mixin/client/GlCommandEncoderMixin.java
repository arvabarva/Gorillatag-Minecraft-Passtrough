package com.gorillapass.mixin.client;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minecraft's OpenGL backend sets its shared read framebuffer's read buffer to GL_NONE when copying a depth texture
 * and never sets it back, so the next colour copy fails with "No color buffer". Vanilla never copies depth to a
 * buffer; the passthrough does every frame, so put the read buffer back right after.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlCommandEncoder")
public abstract class GlCommandEncoderMixin {
	private static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;

	@Inject(
		method = "copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/opengl/GlStateManager;_readPixels(IIIIIIJ)V", shift = At.Shift.AFTER)
	)
	private void gorillapass$restoreReadBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel,
		int x, int y, int width, int height, CallbackInfo ci) {
		if (source.getFormat().hasDepthAspect()) {
			GlStateManager._glReadBuffer(GL_COLOR_ATTACHMENT0);
		}
	}
}
