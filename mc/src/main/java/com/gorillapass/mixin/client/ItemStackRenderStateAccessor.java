package com.gorillapass.mixin.client;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The resolved layers of an item's model, to read the held item's picture and tint (see HeldItemSender). */
@Mixin(ItemStackRenderState.class)
public interface ItemStackRenderStateAccessor {
	@Accessor("layers")
	ItemStackRenderState.LayerRenderState[] gorillapass$layers();

	@Accessor("activeLayerCount")
	int gorillapass$activeLayerCount();
}
