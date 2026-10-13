package com.gorillapass;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Multiplayer: gorillas on one Minecraft server (a world opened to LAN, or a server with this mod). Each player's
 * Minecraft says when it becomes a gorilla (and its Gorilla Tag fur colour) and where its hands are; the server trusts
 * gorillas' movement, tells everyone who the gorillas are, and passes each gorilla's hands on to the players who can see
 * it, so their Minecraft draws its arms reaching there.
 */
public final class GorillaNet implements ModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("gorillapass");

	/** Client -> server: I'm a gorilla now (fur colour ARGB), or not any more (hello with gorilla = false). */
	public record Hello(boolean gorilla, int furColor) implements CustomPacketPayload {
		public static final Type<Hello> TYPE = new Type<>(id("hello"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Hello> CODEC = CustomPacketPayload.codec(
			(hello, buf) -> buf.writeBoolean(hello.gorilla).writeInt(hello.furColor),
			buf -> new Hello(buf.readBoolean(), buf.readInt()));

		@Override
		public Type<Hello> type() {
			return TYPE;
		}
	}

	/** Client -> server: my hands, relative to my feet. Server -> client: entity's hands (entity = the gorilla's id). */
	public record Hands(int entity, float leftX, float leftY, float leftZ, float rightX, float rightY, float rightZ) implements CustomPacketPayload {
		public static final Type<Hands> TYPE = new Type<>(id("hands"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Hands> CODEC = CustomPacketPayload.codec(
			(hands, buf) -> {
				buf.writeVarInt(hands.entity);
				buf.writeFloat(hands.leftX).writeFloat(hands.leftY).writeFloat(hands.leftZ);
				buf.writeFloat(hands.rightX).writeFloat(hands.rightY).writeFloat(hands.rightZ);
			},
			buf -> new Hands(buf.readVarInt(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat()));

		@Override
		public Type<Hands> type() {
			return TYPE;
		}
	}

	/**
	 * Client -> server: my Gorilla Tag gorilla's pose (made and read by the Gorilla Tag plugin, opaque here). Server ->
	 * client: entity's pose. Lets the other players' Gorilla Tag draw a real Gorilla Tag gorilla in exactly that pose.
	 */
	public record Rig(int entity, byte[] data) implements CustomPacketPayload {
		public static final int MAX_BYTES = 2048;
		public static final Type<Rig> TYPE = new Type<>(id("rig"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Rig> CODEC = CustomPacketPayload.codec(
			(rig, buf) -> {
				buf.writeVarInt(rig.entity);
				buf.writeVarInt(rig.data.length);
				buf.writeBytes(rig.data);
			},
			buf -> {
				int entity = buf.readVarInt();
				int length = buf.readVarInt();
				if (length < 0 || length > MAX_BYTES) {
					throw new IllegalArgumentException("gorilla pose too big: " + length);
				}
				byte[] data = new byte[length];
				buf.readBytes(data);
				return new Rig(entity, data);
			});

		@Override
		public Type<Rig> type() {
			return TYPE;
		}
	}

	/** Server -> client: every gorilla on the server, with its fur colour. */
	public record Gorillas(List<UUID> players, List<Integer> colors) implements CustomPacketPayload {
		public static final Type<Gorillas> TYPE = new Type<>(id("gorillas"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Gorillas> CODEC = CustomPacketPayload.codec(
			(gorillas, buf) -> {
				buf.writeVarInt(gorillas.players.size());
				for (int i = 0; i < gorillas.players.size(); i++) {
					UUIDUtil.STREAM_CODEC.encode(buf, gorillas.players.get(i));
					buf.writeInt(gorillas.colors.get(i));
				}
			},
			buf -> {
				int count = buf.readVarInt();
				List<UUID> players = new ArrayList<>(count);
				List<Integer> colors = new ArrayList<>(count);
				for (int i = 0; i < count; i++) {
					players.add(UUIDUtil.STREAM_CODEC.decode(buf));
					colors.add(buf.readInt());
				}
				return new Gorillas(players, colors);
			});

		@Override
		public Type<Gorillas> type() {
			return TYPE;
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath("gorillapass", path);
	}

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.serverboundPlay().register(Hello.TYPE, Hello.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Hands.TYPE, Hands.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Gorillas.TYPE, Gorillas.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Hands.TYPE, Hands.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Rig.TYPE, Rig.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Rig.TYPE, Rig.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(Hello.TYPE, (hello, context) -> {
			ServerPlayer player = context.player();
			if (hello.gorilla()) {
				Gorilla.SERVER.put(player.getUUID(), hello.furColor());
				LOGGER.info("Gorilla Passthrough: {} is a gorilla", player.getPlainTextName());
			} else {
				Gorilla.SERVER.remove(player.getUUID());
			}
			player.refreshDimensions();
			broadcast(context.server());
		});
		ServerPlayNetworking.registerGlobalReceiver(Hands.TYPE, (hands, context) -> {
			ServerPlayer player = context.player();
			if (!Gorilla.SERVER.containsKey(player.getUUID())) {
				return;
			}
			Hands relayed = new Hands(player.getId(), hands.leftX(), hands.leftY(), hands.leftZ(), hands.rightX(), hands.rightY(), hands.rightZ());
			for (ServerPlayer watcher : PlayerLookup.tracking(player)) {
				if (watcher != player && ServerPlayNetworking.canSend(watcher, Hands.TYPE)) {
					ServerPlayNetworking.send(watcher, relayed);
				}
			}
		});
		ServerPlayNetworking.registerGlobalReceiver(Rig.TYPE, (rig, context) -> {
			ServerPlayer player = context.player();
			if (!Gorilla.SERVER.containsKey(player.getUUID())) {
				return;
			}
			Rig relayed = new Rig(player.getId(), rig.data());
			for (ServerPlayer watcher : PlayerLookup.tracking(player)) {
				if (watcher != player && ServerPlayNetworking.canSend(watcher, Rig.TYPE)) {
					ServerPlayNetworking.send(watcher, relayed);
				}
			}
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> broadcast(server));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			if (Gorilla.SERVER.remove(handler.player.getUUID()) != null) {
				server.execute(() -> broadcast(server));
			}
		});
	}

	private static void broadcast(MinecraftServer server) {
		List<UUID> players = new ArrayList<>();
		List<Integer> colors = new ArrayList<>();
		for (Map.Entry<UUID, Integer> entry : Gorilla.SERVER.entrySet()) {
			players.add(entry.getKey());
			colors.add(entry.getValue());
		}
		Gorillas gorillas = new Gorillas(players, colors);
		for (ServerPlayer player : PlayerLookup.all(server)) {
			if (ServerPlayNetworking.canSend(player, Gorillas.TYPE)) {
				ServerPlayNetworking.send(player, gorillas);
			}
		}
	}
}
