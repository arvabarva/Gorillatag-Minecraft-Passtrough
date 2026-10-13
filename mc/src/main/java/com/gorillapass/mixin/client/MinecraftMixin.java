package com.gorillapass.mixin.client;

import com.gorillapass.client.PassthroughClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	private static boolean gorillapass$linked() {
		return PassthroughClient.INSTANCE != null && PassthroughClient.INSTANCE.isLinked();
	}

	/** Every frame: the menu pointer and the held item follow the gorilla's hand. */
	@Inject(method = "renderFrame", at = @At("HEAD"))
	private void gorillapass$everyFrame(boolean advanceGameTime, CallbackInfo ci) {
		if (PassthroughClient.INSTANCE != null) {
			PassthroughClient.INSTANCE.onFrame((Minecraft) (Object) this);
		}
	}

	/** You're in the headset, so the Minecraft window is never focused: that must not pause the world. */
	@Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true)
	private void gorillapass$keepRunning(CallbackInfo ci) {
		if (gorillapass$linked()) {
			ci.cancel();
		}
	}

	@Inject(method = "isWindowActive", at = @At("HEAD"), cancellable = true)
	private void gorillapass$alwaysActive(CallbackInfoReturnable<Boolean> cir) {
		if (gorillapass$linked()) {
			cir.setReturnValue(true);
		}
	}

	/** Aiming is done with the gorilla's hands, not the middle of the screen. */
	@Inject(method = "pick", at = @At("TAIL"))
	private void gorillapass$pickWithHand(float partialTicks, CallbackInfo ci) {
		if (PassthroughClient.INSTANCE != null) {
			PassthroughClient.INSTANCE.overridePick((Minecraft) (Object) this);
		}
	}

	/** The (unpressed) mouse button would stop the mining a gorilla hand is doing. */
	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void gorillapass$handMining(boolean down, CallbackInfo ci) {
		if (PassthroughClient.INSTANCE != null && PassthroughClient.INSTANCE.isMining()) {
			ci.cancel();
		}
	}
}
