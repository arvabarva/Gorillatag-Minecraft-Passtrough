package com.gorillapass.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import java.util.List;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** What PassthroughRenderer needs to draw the world into its own, headset-shaped picture (see GameRenderer.render). */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Accessor("mainRenderTarget")
	RenderTarget gorillapass$getMainRenderTarget();

	@Mutable
	@Accessor("mainRenderTarget")
	void gorillapass$setMainRenderTarget(RenderTarget target);

	@Accessor("hud3DTarget")
	RenderTarget gorillapass$getHud3DTarget();

	@Accessor("globalSettingsUniform")
	GlobalSettingsUniform gorillapass$getGlobalSettingsUniform();

	@Accessor("lightmap")
	Lightmap gorillapass$getLightmap();

	@Accessor("fogRenderer")
	FogRenderer gorillapass$getFogRenderer();

	@Accessor("guiRenderer")
	GuiRenderer gorillapass$getGuiRenderer();

	@Accessor("resourcePool")
	CrossFrameResourcePool gorillapass$getResourcePool();

	@Accessor("useUiLightmap")
	void gorillapass$setUseUiLightmap(boolean value);

	@Invoker("preparePostEffects")
	void gorillapass$preparePostEffects(List<Identifier> requestedPostEffects);

	@Invoker("applyPostEffects")
	void gorillapass$applyPostEffects();

	@Invoker("tryTakeScreenshotIfNeeded")
	void gorillapass$tryTakeScreenshotIfNeeded();
}
