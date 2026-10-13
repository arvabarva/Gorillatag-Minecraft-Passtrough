package com.gorillapass.mixin.client;

import com.gorillapass.client.PassthroughClient;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
	/**
	 * Minecraft slows itself down when its window isn't focused or nobody touched the keyboard for a while; the
	 * headset still needs fresh pictures. But no more than the headset can show: the rest would only slow VR down.
	 */
	@Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
	private void gorillapass$headsetPace(CallbackInfoReturnable<Integer> cir) {
		if (PassthroughClient.INSTANCE != null && PassthroughClient.INSTANCE.isLinked()) {
			cir.setReturnValue(PassthroughClient.INSTANCE.frameLimit());
		}
	}
}
