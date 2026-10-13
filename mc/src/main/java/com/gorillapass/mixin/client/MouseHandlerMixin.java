package com.gorillapass.mixin.client;

import com.gorillapass.client.PassthroughClient;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	/** The gorilla's head turns the view, not the mouse. */
	@Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
	private void gorillapass$noMouseTurning(double mousea, CallbackInfo ci) {
		if (PassthroughClient.INSTANCE != null && PassthroughClient.INSTANCE.isLinked()) {
			ci.cancel();
		}
	}

	/** Leaves the desktop mouse alone while you play in the headset (closing the pause menu would grab it). */
	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private void gorillapass$noMouseGrab(CallbackInfo ci) {
		if (PassthroughClient.INSTANCE != null && PassthroughClient.INSTANCE.isLinked()) {
			ci.cancel();
		}
	}
}
