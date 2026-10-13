package com.gorillapass.client;

import com.gorillapass.client.WorldStreamer.Payload;
import com.gorillapass.mixin.client.ItemStackRenderStateAccessor;
import com.gorillapass.mixin.client.LayerRenderStateAccessor;
import com.gorillapass.mixin.client.SpriteContentsAccessor;
import com.mojang.blaze3d.platform.NativeImage;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Tells Gorilla Tag what the gorilla holds: the held item's own picture, as Minecraft has it loaded right now (its first
 * animation frame, with grass and leaf tints applied), so Gorilla Tag can build it as a little 3D model right in the
 * gorilla's fist. It isn't drawn in Minecraft's picture: something that close to your eyes would leave a hole in the
 * world behind it as soon as your eyes see it from a slightly different spot, and it would lag behind the hand.
 */
final class HeldItemSender {
	private static final int MAX_SIZE = 64;

	private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	private @Nullable ItemStack sent;
	private final ItemStack[] sentArmor = new ItemStack[ARMOR.length];

	void reset() {
		this.sent = null;
		java.util.Arrays.fill(this.sentArmor, null);
	}

	void tick(Minecraft minecraft, LocalPlayer player, HostLink link) {
		ItemStack held = player.getMainHandItem();
		if (this.sent == null || !ItemStack.isSameItemSameComponents(held, this.sent)) {
			this.sent = held.copy();
			link.send(Protocol.S_HELD, encode(minecraft, player, held).bytes);
		}

		// what the gorilla wears: Gorilla Tag dresses it in the colours of each piece
		boolean changed = false;
		for (int i = 0; i < ARMOR.length; i++) {
			ItemStack worn = player.getItemBySlot(ARMOR[i]);
			if (this.sentArmor[i] == null || !ItemStack.isSameItemSameComponents(worn, this.sentArmor[i])) {
				this.sentArmor[i] = worn.copy();
				changed = true;
			}
		}
		if (changed) {
			Payload armor = new Payload();
			for (int i = 0; i < ARMOR.length; i++) {
				armor.raw(encodeArmor(minecraft, player, this.sentArmor[i], ARMOR[i]).bytes.toByteArray());
			}
			link.send(Protocol.S_ARMOR, armor.bytes);
		}
	}

	private static final java.util.Map<net.minecraft.resources.Identifier, int[]> ARMOR_TEXTURES = new java.util.HashMap<>();
	private static final int LEATHER_UNDYED = -6265536;

	/**
	 * A piece of armour: Minecraft's own armour texture (the one drawn on players, humanoid layout, 64 x 32) as kind 3,
	 * in the held item's format, so Gorilla Tag dresses the gorilla in the real thing. Leather comes in its dye colour.
	 * Anything worn without such a texture (a pumpkin, a head) goes as its item picture.
	 */
	static Payload encodeArmor(Minecraft minecraft, LocalPlayer player, ItemStack worn, EquipmentSlot slot) {
		if (worn.isEmpty()) {
			return new Payload().b(0);
		}
		net.minecraft.world.item.equipment.Equippable equippable = worn.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);
		if (equippable == null || equippable.assetId().isEmpty()) {
			return encode(minecraft, player, worn);
		}
		net.minecraft.resources.Identifier asset = equippable.assetId().get().identifier();
		String layer = slot == EquipmentSlot.LEGS ? "humanoid_leggings" : "humanoid";
		int[] base = armorTexture(minecraft, asset.withPath(p -> "textures/entity/equipment/" + layer + "/" + p + ".png"));
		if (base == null) {
			return encode(minecraft, player, worn);
		}
		int width = base[0];
		int height = base[1];
		int[] pixels = java.util.Arrays.copyOfRange(base, 2, base.length);
		if (asset.getPath().equals("leather")) {
			// dyed (or the undyed brown), with the undyed overlay on top
			int dye = net.minecraft.world.item.component.DyedItemColor.getOrDefault(worn, LEATHER_UNDYED);
			for (int i = 0; i < pixels.length; i++) {
				int argb = pixels[i];
				int r = (argb >> 16 & 0xFF) * (dye >> 16 & 0xFF) / 255;
				int g = (argb >> 8 & 0xFF) * (dye >> 8 & 0xFF) / 255;
				int b = (argb & 0xFF) * (dye & 0xFF) / 255;
				pixels[i] = argb & 0xFF000000 | r << 16 | g << 8 | b;
			}
			int[] overlay = armorTexture(minecraft, asset.withPath(p -> "textures/entity/equipment/" + layer + "/leather_overlay.png"));
			if (overlay != null && overlay[0] == width && overlay[1] == height) {
				for (int i = 0; i < pixels.length; i++) {
					if (overlay[i + 2] >>> 24 >= 128) {
						pixels[i] = overlay[i + 2];
					}
				}
			}
		}
		Payload payload = new Payload().b(3).s(width).s(height);
		byte[] bytes = new byte[width * height * 4];
		for (int i = 0, o = 0; i < pixels.length; i++) {
			int argb = pixels[i];
			bytes[o++] = (byte) (argb >> 16);
			bytes[o++] = (byte) (argb >> 8);
			bytes[o++] = (byte) argb;
			bytes[o++] = (byte) (argb >>> 24);
		}
		return payload.raw(bytes);
	}

	/** {width, height, ARGB pixels top row first...}, or null if the resource packs don't have it. */
	private static int @Nullable [] armorTexture(Minecraft minecraft, net.minecraft.resources.Identifier id) {
		if (ARMOR_TEXTURES.containsKey(id)) {
			return ARMOR_TEXTURES.get(id);
		}
		int[] result = null;
		java.util.Optional<net.minecraft.server.packs.resources.Resource> resource = minecraft.getResourceManager().getResource(id);
		if (resource.isPresent()) {
			try (java.io.InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
				int width = Math.min(128, image.getWidth());
				int height = Math.min(128, image.getHeight());
				result = new int[2 + width * height];
				result[0] = width;
				result[1] = height;
				for (int y = 0; y < height; y++) {
					for (int x = 0; x < width; x++) {
						result[2 + y * width + x] = image.getPixel(x, y);
					}
				}
			} catch (java.io.IOException e) {
				result = null;
			}
		}
		ARMOR_TEXTURES.put(id, result);
		return result;
	}

	/** byte kind (0 nothing, 1 flat item, 2 block); short width, height; RGBA pixels, top row first. */
	static Payload encode(Minecraft minecraft, LocalPlayer player, ItemStack held) {
		if (held.isEmpty()) {
			return new Payload().b(0);
		}
		ItemStackRenderState state = new ItemStackRenderState();
		minecraft.getItemModelResolver().updateForTopItem(state, held, ItemDisplayContext.GUI, minecraft.level, player, 0);
		ItemStackRenderStateAccessor resolved = (ItemStackRenderStateAccessor) state;
		if (resolved.gorillapass$activeLayerCount() == 0) {
			return new Payload().b(0);
		}
		LayerRenderStateAccessor layer = (LayerRenderStateAccessor) resolved.gorillapass$layers()[0];
		Material.Baked material = layer.gorillapass$particleMaterial();
		if (material == null) {
			return new Payload().b(0);
		}
		SpriteContents contents = material.sprite().contents();
		NativeImage image = ((SpriteContentsAccessor) contents).gorillapass$originalImage();
		int width = Math.min(MAX_SIZE, contents.width());
		int height = Math.min(MAX_SIZE, contents.height());
		IntList tints = layer.gorillapass$tintLayers();
		int tint = tints != null && !tints.isEmpty() ? tints.getInt(0) : -1;

		Payload payload = new Payload().b(held.getItem() instanceof BlockItem ? 2 : 1).s(width).s(height);
		byte[] pixels = new byte[width * height * 4];
		int i = 0;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int argb = image.getPixel(x, y); // the first animation frame is the top-left square
				int r = argb >> 16 & 0xFF;
				int g = argb >> 8 & 0xFF;
				int b = argb & 0xFF;
				if (tint != -1) {
					r = r * (tint >> 16 & 0xFF) / 255;
					g = g * (tint >> 8 & 0xFF) / 255;
					b = b * (tint & 0xFF) / 255;
				}
				pixels[i++] = (byte) r;
				pixels[i++] = (byte) g;
				pixels[i++] = (byte) b;
				pixels[i++] = (byte) (argb >>> 24);
			}
		}
		return payload.raw(pixels);
	}
}
