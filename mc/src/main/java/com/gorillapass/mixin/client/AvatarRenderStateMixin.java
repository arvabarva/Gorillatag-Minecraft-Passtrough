package com.gorillapass.mixin.client;

import com.gorillapass.client.render.GorillaRenderState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AvatarRenderState.class)
public abstract class AvatarRenderStateMixin implements GorillaRenderState {
	@Unique
	private int gorillapass$furColor = -1;
	@Unique
	private Vector3f @Nullable [] gorillapass$hands;

	@Override
	public int gorillapass$getFurColor() {
		return this.gorillapass$furColor;
	}

	@Override
	public void gorillapass$setFurColor(int color) {
		this.gorillapass$furColor = color;
	}

	@Override
	public Vector3f @Nullable [] gorillapass$getHands() {
		return this.gorillapass$hands;
	}

	@Override
	public void gorillapass$setHands(Vector3f @Nullable [] hands) {
		this.gorillapass$hands = hands;
	}
}
