package com.gorillapass.mixin;

import com.gorillapass.Gorilla;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Player.class)
public abstract class PlayerMixin {
	/**
	 * Gorilla Tag's physics moves the gorilla: Minecraft's own walking, gravity and keyboard controls stay out of it
	 * (only on the client; the built-in server never moves players by itself).
	 */
	@Inject(method = "travel", at = @At("HEAD"), cancellable = true)
	private void gorillapass$gorillaTagMoves(Vec3 input, CallbackInfo ci) {
		Player self = (Player) (Object) this;
		if (self.level().isClientSide() && Gorilla.isGorilla(self)) {
			ci.cancel();
		}
	}

	/**
	 * Minecraft mines 5x slower while you're not standing on the ground. A gorilla mines hanging off walls and trees
	 * by its arms, so it always mines at full (tool) speed. Water still slows it down, as in vanilla.
	 */
	@Redirect(method = "getDestroySpeed", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;onGround()Z"))
	private boolean gorillapass$minesWhileClimbing(Player player) {
		return Gorilla.isGorilla(player) || player.onGround();
	}

	/** Gorillas catch themselves with their arms: no fall damage, like in Gorilla Tag. */
	@Inject(method = "causeFallDamage", at = @At("HEAD"), cancellable = true)
	private void gorillapass$noFallDamage(double fallDistance, float damageModifier, DamageSource damageSource, CallbackInfoReturnable<Boolean> cir) {
		if (Gorilla.isGorilla((Player) (Object) this)) {
			cir.setReturnValue(false);
		}
	}
}
