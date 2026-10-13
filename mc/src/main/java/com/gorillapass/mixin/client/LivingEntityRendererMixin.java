package com.gorillapass.mixin.client;

import com.gorillapass.client.render.GorillaRenderState;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
	/** Tints the grey gorilla texture with the player's Gorilla Tag fur colour. */
	@Inject(method = "getModelTint", at = @At("HEAD"), cancellable = true)
	private void gorillapass$furTint(LivingEntityRenderState state, CallbackInfoReturnable<Integer> cir) {
		if (state instanceof GorillaRenderState gorilla && gorilla.gorillapass$getFurColor() != -1) {
			cir.setReturnValue(gorilla.gorillapass$getFurColor());
		}
	}
}
