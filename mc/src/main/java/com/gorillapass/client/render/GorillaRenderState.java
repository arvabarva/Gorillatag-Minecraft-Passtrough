package com.gorillapass.client.render;

import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/** Added to AvatarRenderState by AvatarRenderStateMixin. */
public interface GorillaRenderState {
	int gorillapass$getFurColor();

	void gorillapass$setFurColor(int color);

	/** Where each hand of this gorilla should reach, in model pixels (null = walking on its knuckles). */
	Vector3f @Nullable [] gorillapass$getHands();

	void gorillapass$setHands(Vector3f @Nullable [] hands);
}
