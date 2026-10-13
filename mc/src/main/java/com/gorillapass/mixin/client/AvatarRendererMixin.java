package com.gorillapass.mixin.client;

import com.gorillapass.Gorilla;
import com.gorillapass.GorillaNet;
import com.gorillapass.client.PassthroughClient;
import com.gorillapass.client.render.GorillaModelMesh;
import com.gorillapass.client.render.GorillaRenderState;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Everyone is drawn as a gorilla, in their Gorilla Tag fur colour, with arms reaching for their real hands. */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {

	@Inject(method = "getTextureLocation(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)Lnet/minecraft/resources/Identifier;",
		at = @At("HEAD"), cancellable = true)
	private void gorillapass$furTexture(AvatarRenderState state, CallbackInfoReturnable<Identifier> cir) {
		cir.setReturnValue(GorillaModelMesh.FUR_TEXTURE);
	}

	@Inject(method = "extractRenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V",
		at = @At("TAIL"))
	private void gorillapass$gorillaState(Avatar entity, AvatarRenderState state, float partialTicks, CallbackInfo ci) {
		GorillaRenderState gorilla = (GorillaRenderState) state;
		gorilla.gorillapass$setFurColor(entity instanceof Player player ? Gorilla.furColor(player) : Gorilla.DEFAULT_FUR);
		// capes hang where a human's back would be, far above a gorilla
		state.showCape = false;
		gorilla.gorillapass$setHands(hands(entity, state));
	}

	/** Where this gorilla's hands are, in the model's own pixel space (undoing LivingEntityRenderer's transforms). */
	private static Vector3f[] hands(Avatar entity, AvatarRenderState state) {
		GorillaNet.Hands hands = PassthroughClient.REMOTE_HANDS.get(entity.getId());
		Long time = PassthroughClient.REMOTE_HANDS_TIME.get(entity.getId());
		if (hands == null || time == null || Util.getMillis() - time > GorillaModelMesh.HANDS_FRESH_MILLIS) {
			return null;
		}
		return new Vector3f[]{
			toModel(state, hands.leftX(), hands.leftY(), hands.leftZ()),
			toModel(state, hands.rightX(), hands.rightY(), hands.rightZ())
		};
	}

	private static Vector3f toModel(AvatarRenderState state, float x, float y, float z) {
		// relative to the feet -> scale, turn to the body's direction, flip, player scale, lift
		Vector3f v = new Vector3f(x, y, z).div(state.scale);
		v.rotateY((float) Math.toRadians(-(180.0F - state.bodyRot)));
		return v.set(-v.x, -v.y, v.z).div(0.9375F).add(0.0F, 1.501F, 0.0F).mul(16.0F);
	}
}
