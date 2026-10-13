package com.gorillapass.mixin;

import com.gorillapass.Gorilla;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	/**
	 * Minecraft hurts you for "suffocating in a wall" when a box 0.4 blocks wide round your eyes touches a solid block.
	 * A gorilla pressed against a wall has its head a hand's width from it, inside that box; Gorilla Tag's own physics
	 * already keeps the gorilla out of blocks, so gorillas never suffocate in walls.
	 */
	@Inject(method = "isInWall", at = @At("HEAD"), cancellable = true)
	private void gorillapass$neverInWall(CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof Player player && Gorilla.isGorilla(player)) {
			cir.setReturnValue(false);
		}
	}
}
