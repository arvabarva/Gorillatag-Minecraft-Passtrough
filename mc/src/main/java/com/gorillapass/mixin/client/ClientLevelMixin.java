package com.gorillapass.mixin.client;

import com.gorillapass.client.PassthroughClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
	/** A block changed: Gorilla Tag's collider for that spot has to change with it. */
	@Inject(method = "sendBlockUpdated", at = @At("TAIL"))
	private void gorillapass$blockChanged(BlockPos pos, BlockState old, BlockState current, int updateFlags, CallbackInfo ci) {
		if (PassthroughClient.INSTANCE != null && old != current) {
			PassthroughClient.INSTANCE.onBlockChanged(pos);
		}
	}
}
