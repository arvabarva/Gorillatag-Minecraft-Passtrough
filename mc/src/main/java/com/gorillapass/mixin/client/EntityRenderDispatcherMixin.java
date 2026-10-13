package com.gorillapass.mixin.client;

import com.gorillapass.client.PassthroughClient;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherMixin {
	/** Another gorilla drawn by our Gorilla Tag (a real Gorilla Tag gorilla) isn't drawn by Minecraft as well. */
	@Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
	private void gorillapass$drawnByGorillaTag(Entity entity, Frustum culler, double camX, double camY, double camZ, float partialTicks,
		CallbackInfoReturnable<Boolean> cir) {
		if (PassthroughClient.INSTANCE != null && PassthroughClient.INSTANCE.drawnByGorillaTag(entity)) {
			cir.setReturnValue(false);
		}
	}
}
