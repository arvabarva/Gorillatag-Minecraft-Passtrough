package com.gorillapass.mixin;

import com.gorillapass.Gorilla;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Avatar.class)
public abstract class AvatarMixin {
	/** The gorilla is gorilla-sized in Minecraft too, so it fits wherever it fits in Gorilla Tag. */
	@Inject(method = "getDefaultDimensions", at = @At("HEAD"), cancellable = true)
	private void gorillapass$gorillaSize(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
		if ((Object) this instanceof Player player && Gorilla.isGorilla(player)) {
			EntityDimensions dimensions = Gorilla.dimensionsFor(pose);
			if (dimensions != null) {
				cir.setReturnValue(dimensions);
			}
		}
	}
}
