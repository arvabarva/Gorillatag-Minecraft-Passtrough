package com.gorillapass.mixin;

import com.gorillapass.Gorilla;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In the passthrough Gorilla Tag's physics decides where the gorilla is (hanging off a wall by one hand, squeezing
 * through gaps), so the built-in single-player server accepts the gorilla's position instead of snapping it back
 * with "moved wrongly".
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@org.spongepowered.asm.mixin.Shadow
	public ServerPlayer player;

	@Redirect(method = "handlePlayerPositionChange", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isInPostImpulseGraceTime()Z"))
	private boolean gorillapass$trustGorilla(ServerPlayer player) {
		return Gorilla.isGorilla(player) || player.isInPostImpulseGraceTime();
	}

	/** Gorilla Tag flings and swings gorillas faster than Minecraft players can move: no "moved too quickly". */
	@Inject(method = "shouldCheckPlayerMovement", at = @At("HEAD"), cancellable = true)
	private void gorillapass$gorillaSpeed(boolean isFallFlying, CallbackInfoReturnable<Boolean> cir) {
		if (Gorilla.isGorilla(this.player)) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "isEntityCollidingWithAnythingNew", at = @At("HEAD"), cancellable = true)
	private void gorillapass$gorillaFits(LevelReader level, Entity entity, AABB oldAABB, double x, double y, double z, CallbackInfoReturnable<Boolean> cir) {
		if (entity instanceof Player player && Gorilla.isGorilla(player)) {
			cir.setReturnValue(false);
		}
	}
}
