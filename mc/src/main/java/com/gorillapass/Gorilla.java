package com.gorillapass;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntityAttachments;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Which players are Gorilla Tag gorillas. The server keeps its own list (players whose Minecraft said so, see
 * GorillaNet); every client gets a copy from the server; and the local player is a gorilla while Gorilla Tag is
 * linked. In single player the client and the built-in server run in the same Java process, so they keep separate lists.
 */
public final class Gorilla {
	// Gorilla Tag's rig is a 0.15 m head sphere on a body capsule that reaches 0.64 m below it,
	// so the gorilla stands about 0.8 blocks tall and fits through one-block holes.
	public static final EntityDimensions STANDING = EntityDimensions.scalable(0.5F, 0.8F)
		.withEyeHeight(0.62F)
		.withAttachments(EntityAttachments.builder().attach(EntityAttachment.VEHICLE, new Vec3(0.0, 0.3, 0.0)));
	public static final EntityDimensions CROUCHING = EntityDimensions.scalable(0.5F, 0.7F)
		.withEyeHeight(0.52F)
		.withAttachments(EntityAttachments.builder().attach(EntityAttachment.VEHICLE, new Vec3(0.0, 0.3, 0.0)));
	/** Gorilla Tag's default fur, for gorillas that haven't said their colour. */
	public static final int DEFAULT_FUR = 0xFF8C5A3C;

	/** On the server: gorilla player -> fur colour. */
	public static final Map<UUID, Integer> SERVER = new ConcurrentHashMap<>();
	/** On a client: what the server said. */
	public static final Map<UUID, Integer> CLIENT = new ConcurrentHashMap<>();
	private static volatile @Nullable UUID local;

	private Gorilla() {
	}

	public static boolean isGorilla(Player player) {
		UUID uuid = player.getUUID();
		if (player.level().isClientSide()) {
			return uuid.equals(local) || CLIENT.containsKey(uuid);
		}
		return SERVER.containsKey(uuid);
	}

	public static int furColor(Player player) {
		return CLIENT.getOrDefault(player.getUUID(), DEFAULT_FUR);
	}

	/** True while this Minecraft's own player is a gorilla (Gorilla Tag is linked). */
	public static boolean isActive() {
		return local != null;
	}

	public static void setLocal(@Nullable UUID player) {
		local = player;
	}

	public static @Nullable EntityDimensions dimensionsFor(Pose pose) {
		return switch (pose) {
			case STANDING -> STANDING;
			case CROUCHING -> CROUCHING;
			default -> null;
		};
	}
}
