package com.gorillapass.mixin.client;

import com.gorillapass.Gorilla;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
	/** Minecraft nudges a player out of blocks it overlaps; Gorilla Tag's physics decides where the gorilla is. */
	@Inject(method = "moveTowardsClosestSpace", at = @At("HEAD"), cancellable = true)
	private void gorillapass$noNudging(double x, double z, CallbackInfo ci) {
		if (Gorilla.isGorilla((LocalPlayer) (Object) this)) {
			ci.cancel();
		}
	}
}
