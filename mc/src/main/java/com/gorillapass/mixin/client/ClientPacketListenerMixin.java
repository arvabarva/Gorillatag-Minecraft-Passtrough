package com.gorillapass.mixin.client;

import com.gorillapass.client.PassthroughClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handleMovePlayer", at = @At("TAIL"))
	private void gorillapass$moved(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
		if (PassthroughClient.INSTANCE != null) {
			PassthroughClient.INSTANCE.onServerMovedPlayer();
		}
	}

	@Inject(method = {"handleLogin(Lnet/minecraft/network/protocol/game/ClientboundLoginPacket;)V"}, at = @At("TAIL"))
	private void gorillapass$joined(ClientboundLoginPacket packet, CallbackInfo ci) {
		if (PassthroughClient.INSTANCE != null) {
			PassthroughClient.INSTANCE.onWorldChanged();
		}
	}

	@Inject(method = "handleRespawn", at = @At("TAIL"))
	private void gorillapass$respawned(ClientboundRespawnPacket packet, CallbackInfo ci) {
		if (PassthroughClient.INSTANCE != null) {
			PassthroughClient.INSTANCE.onWorldChanged();
		}
	}
}
