package com.gorillapass.mixin.client;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.sprite.Material;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface LayerRenderStateAccessor {
	@Accessor("particleMaterial")
	Material.@Nullable Baked gorillapass$particleMaterial();

	@Accessor("tintLayers")
	@Nullable IntList gorillapass$tintLayers();
}
