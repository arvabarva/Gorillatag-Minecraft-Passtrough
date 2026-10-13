package com.gorillapass.mixin.client;

import com.gorillapass.client.render.GorillaModelMesh;
import com.gorillapass.client.render.GorillaRenderState;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.util.Mth;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerModel.class)
public abstract class PlayerModelMixin {
	/** Every player model (the body, armour and hands) is built from this mesh: everyone is a gorilla. */
	@Inject(method = "createMesh", at = @At("HEAD"), cancellable = true)
	private static void gorillapass$gorillaMesh(CubeDeformation scale, boolean slim, CallbackInfoReturnable<MeshDefinition> cir) {
		cir.setReturnValue(GorillaModelMesh.createBodyMesh(scale));
	}

	@Inject(method = "createArmorMeshSet", at = @At("HEAD"), cancellable = true)
	private static void gorillapass$gorillaArmor(
		CubeDeformation innerDeformation, CubeDeformation outerDeformation, CallbackInfoReturnable<ArmorModelSet<MeshDefinition>> cir
	) {
		cir.setReturnValue(GorillaModelMesh.createArmorMeshSet(innerDeformation, outerDeformation));
	}

	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("TAIL"))
	private void gorillapass$pose(AvatarRenderState state, CallbackInfo ci) {
		PlayerModel model = (PlayerModel) (Object) this;
		Vector3f[] hands = ((GorillaRenderState) state).gorillapass$getHands();
		if (hands != null) {
			// a gorilla in Gorilla Tag: its arms reach for its real hands
			reach(model.leftArm, hands[0]);
			reach(model.rightArm, hands[1]);
			return;
		}
		// otherwise it walks on its knuckles: arms reach forward and down to the ground
		model.rightArm.xRot -= 0.9F;
		model.leftArm.xRot -= 0.9F;
		model.rightArm.zRot += 0.15F;
		model.leftArm.zRot -= 0.15F;
	}

	/** Points an arm from its shoulder at the hand and stretches it so the fist lands there. */
	private static void reach(ModelPart arm, Vector3f hand) {
		Vector3f toHand = new Vector3f(hand).sub(arm.x, arm.y, arm.z);
		float length = toHand.length();
		if (length < 1.0E-3F) {
			return;
		}
		toHand.div(length);
		// ModelPart rotates Z, then Y, then X; the arm hangs along +y
		arm.xRot = (float) Math.asin(Mth.clamp(toHand.z, -1.0F, 1.0F));
		arm.yRot = 0.0F;
		arm.zRot = (float) Math.atan2(-toHand.x, toHand.y);
		arm.yScale = Mth.clamp(length / GorillaModelMesh.FIST_CENTER_Y, 0.3F, 3.0F);
	}
}
