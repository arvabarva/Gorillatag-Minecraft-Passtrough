package com.gorillapass.client;

import com.gorillapass.Gorilla;
import com.gorillapass.client.WorldStreamer.Payload;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import com.gorillapass.GorillaNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Minecraft half of the passthrough. Minecraft keeps running normally (its own world, mobs, crafting, saving); while
 * Gorilla Tag is linked:
 * <ul>
 * <li>Gorilla Tag is the body: every frame it sends where the gorilla's head is, and Minecraft draws the world from
 * exactly there ({@code CameraMixin}); every tick the Minecraft player is put where the gorilla's feet are.</li>
 * <li>Minecraft is the world: its picture goes back through shared memory ({@link FrameExporter}), the solid blocks
 * near the gorilla go back as collision ({@link WorldStreamer}), and Gorilla Tag's hands mine, place and punch.</li>
 * </ul>
 * Single-player only: the built-in server is told to trust the gorilla's movement.
 */
public final class PassthroughClient implements ClientModInitializer {
	private static final Logger LOGGER = LoggerFactory.getLogger("gorillapass");
	private static final int AUTO_RESPAWN_TICKS = 40;
	private static final boolean TEST_LAN = Boolean.getBoolean("gorillapass.testLan");

	public static PassthroughClient INSTANCE;

	public record Pose(int id, int teleportAck, double headX, double headY, double headZ, float qx, float qy, float qz, float qw,
		float tanH, float tanV, float maxDistance, double feetX, double feetY, double feetZ, float yaw, float pitch, int flags,
		double leftX, double leftY, double leftZ, double rightX, double rightY, double rightZ, int renderWidth, int renderHeight) {
	}

	private final HostLink link = new HostLink(this::immediate);
	private final WorldStreamer streamer = new WorldStreamer();
	private final MenuPointer pointer = new MenuPointer();
	private final HeldItemSender heldItem = new HeldItemSender();
	private final RemoteGorillas remote = new RemoteGorillas();
	private boolean screenOpen;
	private int frameLimit = 90;
	private int furColor = Gorilla.DEFAULT_FUR;
	private boolean holdingUse;
	private volatile @Nullable Pose latestPose;
	private @Nullable Pose renderedPose;

	private @Nullable LocalPlayer linkedPlayer;
	private int teleportId;
	private boolean teleportRequested;
	private boolean clearRequested;
	private int tick;
	private int deadTicks;
	private int lastHurtTime;

	private @Nullable BlockPos minePos;
	private Direction mineFace = Direction.UP;
	private int mineUntil;
	private @Nullable BlockHitResult focus;
	private int focusUntil;

	@Override
	public void onInitializeClient() {
		INSTANCE = this;
		this.link.start();
		ClientTickEvents.START_CLIENT_TICK.register(this::tick);
		registerMultiplayer();
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> this.streamer.chunkLoaded(chunk.getPos().x(), chunk.getPos().z()));
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> this.link.close());
	}

	// ---- called from the mixins ----

	/** True while Gorilla Tag plays this single-player world. */
	public boolean isLinked() {
		return this.linkedPlayer != null && this.link.isConnected();
	}

	/** The head pose to draw this frame from, or null to draw normally. */
	public @Nullable Pose cameraPose() {
		return this.isLinked() ? this.latestPose : null;
	}

	public void markRendered(Pose pose) {
		this.renderedPose = pose;
	}

	/** The pose the world was just drawn from (once per frame). */
	public @Nullable Pose takeRenderedPose() {
		Pose pose = this.renderedPose;
		this.renderedPose = null;
		return pose;
	}

	/** Minecraft moved the player itself (spawning, respawning, portals, ender pearls, /tp): the gorilla must follow. */
	public void onServerMovedPlayer() {
		this.teleportRequested = true;
	}

	/** A new world or dimension: Gorilla Tag drops every block collider it has. */
	public void onWorldChanged() {
		this.clearRequested = true;
		this.teleportRequested = true;
	}

	public void onBlockChanged(BlockPos pos) {
		if (this.isLinked()) {
			this.streamer.markBlock(pos);
		}
	}

	/** The outline Minecraft draws is around the block a gorilla hand rests on, not where the head looks. */
	public void overridePick(Minecraft minecraft) {
		if (!this.isLinked() || minecraft.player == null) {
			return;
		}
		minecraft.crosshairPickEntity = null;
		if (this.focus != null && this.tick <= this.focusUntil) {
			minecraft.hitResult = this.focus;
		} else {
			minecraft.hitResult = BlockHitResult.miss(minecraft.player.getEyePosition(), Direction.UP, minecraft.player.blockPosition());
		}
	}

	private static int channel(float value) {
		return Math.clamp(Math.round(value * 255.0F), 0, 255);
	}

	/** Minecraft's frame limit while linked: the headset's refresh rate. */
	public int frameLimit() {
		return this.frameLimit;
	}

	/** While a gorilla hand mines, Minecraft's own (unpressed) mouse button must not cancel it. */
	public boolean isMining() {
		return this.isLinked() && this.minePos != null;
	}

	/** Every frame, on the render thread: the menu pointer follows the hand without waiting for a tick. */
	public void onFrame(Minecraft minecraft) {
		if (this.isLinked() && minecraft.gui.screen() != null) {
			this.pointer.apply(minecraft);
		}
	}

	// ---- network ----

	/** On the link's reader thread: messages that are used every frame instead of every tick. */
	private boolean immediate(byte type, byte[] payload) {
		if (type == Protocol.C_POSE) {
			this.readPose(new DataInputStream(new ByteArrayInputStream(payload)));
			return true;
		}
		if (type == Protocol.C_POINTER) {
			this.pointer.queue(payload);
			return true;
		}
		if (type == Protocol.C_RIG) {
			this.remote.ownPose(payload);
			return true;
		}
		return false;
	}

	private void readPose(DataInputStream in) {
		try {
			this.latestPose = new Pose(in.readInt(), in.readInt(), in.readDouble(), in.readDouble(), in.readDouble(),
				in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(),
				in.readDouble(), in.readDouble(), in.readDouble(), in.readFloat(), in.readFloat(), in.readByte(),
				in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(), in.readDouble(),
				in.available() >= 8 ? in.readInt() : 0, in.available() >= 4 ? in.readInt() : 0);
		} catch (IOException e) {
			LOGGER.warn("Gorilla Passthrough: bad pose message", e);
		}
	}

	// ---- every tick ----

	private void tick(Minecraft minecraft) {
		this.tick++;
		LocalPlayer player = minecraft.player;
		ClientLevel level = minecraft.level;
		boolean canLink = this.link.isConnected() && player != null && level != null;

		// testing multiplayer on one PC: open this single-player world to LAN by itself
		if (TEST_LAN && minecraft.getSingleplayerServer() != null && !minecraft.getSingleplayerServer().isPublished() && player != null) {
			// the test accounts are offline ones, which a LAN world (it checks Microsoft accounts) would turn away
			minecraft.getSingleplayerServer().setUsesAuthentication(false);
			minecraft.getSingleplayerServer().publishServer(net.minecraft.server.MinecraftServer.MultiplayerScope.LAN, true, 25570);
			LOGGER.info("Gorilla Passthrough: test world opened to LAN on port 25570");
			// every test run is a new test player, which Minecraft may put in a cave: on the surface by spawn instead
			net.minecraft.server.MinecraftServer server = minecraft.getSingleplayerServer();
			server.execute(() -> {
				for (String command : new String[]{"spreadplayers 3.5 -9.5 0 1 false @a", "item replace entity @a armor.head with iron_helmet",
					"item replace entity @a armor.chest with diamond_chestplate", "item replace entity @a armor.legs with golden_leggings",
					"item replace entity @a armor.feet with leather_boots"}) {
					server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
				}
			});
		}

		if (this.link.takeNewConnection()) {
			this.linkedPlayer = null; // start over with the new Gorilla Tag
			this.screenOpen = false;
			this.heldItem.reset();
			this.remote.reset();
			this.latestPose = null;
			this.link.send(Protocol.S_HELLO, new Payload().i(Protocol.VERSION)
				.utf(minecraft.getSingleplayerServer() != null ? minecraft.getSingleplayerServer().getWorldData().getLevelName() : "")
				.utf(Protocol.SHARED_MEMORY_NAME).bytes);
		}

		if (!canLink) {
			if (this.linkedPlayer != null) {
				this.unlink(minecraft);
			}
			return;
		}
		if (this.linkedPlayer != player) {
			// first link, or Minecraft made a new player object (respawn, dimension change)
			boolean first = this.linkedPlayer == null;
			this.linkedPlayer = player;
			this.setGorilla(minecraft, player.getUUID());
			this.teleportRequested = true;
			if (first) {
				this.clearRequested = true;
				LOGGER.info("Gorilla Passthrough: the gorilla is playing");
			}
		}

		for (HostLink.Message message; (message = this.link.poll()) != null; ) {
			try {
				this.handle(minecraft, player, message);
			} catch (IOException | RuntimeException e) {
				LOGGER.warn("Gorilla Passthrough: bad message {}", message.type(), e);
			}
		}

		if (this.clearRequested) {
			this.clearRequested = false;
			this.streamer.reset();
			this.link.send(Protocol.S_CLEAR, new Payload().utf(level.dimension().identifier().toString()).bytes);
		}
		if (this.teleportRequested) {
			this.teleportRequested = false;
			this.teleportId++;
			this.link.send(Protocol.S_TELEPORT, new Payload().i(this.teleportId)
				.d(player.getX()).d(player.getY()).d(player.getZ()).f(player.getYRot()).bytes);
		}

		this.keepRunning(minecraft);
		this.reportScreen(minecraft);

		Pose pose = this.latestPose;
		if (pose != null && pose.teleportAck() >= this.teleportId && !player.isDeadOrDying()) {
			this.followGorilla(player, pose);
		}

		if (this.holdingUse) {
			minecraft.options.keyUse.setDown(true);
		}
		this.heldItem.tick(minecraft, player, this.link);
		this.remote.tick(minecraft, player, level, this.link);
		if (this.tick % 2 == 1) {
			this.sendHands(player, this.latestPose);
		}
		this.mine(minecraft);
		this.streamer.tick(level, player.blockPosition(), this.link);
		if (this.tick % 2 == 0) {
			this.sendEntities(level, player);
		}
		if (this.tick % 5 == 0) {
			this.sendStatus(player);
		}
		this.autoRespawn(minecraft, player);
	}

	/**
	 * Minecraft opens its pause menu (and freezes the single-player world: mobs, block breaking, everything) as soon as
	 * its window loses focus, which happens the moment you put the headset on, often before Gorilla Tag connects.
	 * While you play from the headset the menu is closed again; at the monitor (window focused) you can still pause.
	 */
	private void keepRunning(Minecraft minecraft) {
		if (minecraft.gui.screen() instanceof PauseScreen && !minecraft.getWindow().isFocused()) {
			minecraft.gui.setScreen(null);
			LOGGER.info("Gorilla Passthrough: closed the pause menu so the world keeps running while you play in VR");
		}
	}

	/** Gorilla Tag shows a big pointable panel while any Minecraft menu is open. */
	private void reportScreen(Minecraft minecraft) {
		Screen screen = minecraft.gui.screen();
		boolean open = screen != null;
		if (open != this.screenOpen) {
			this.screenOpen = open;
			if (!open) {
				this.pointer.reset(minecraft);
			}
			this.link.send(Protocol.S_SCREEN, new Payload().b(open ? 1 : 0).utf(open ? screen.getTitle().getString() : "").bytes);
		}
	}

	/** Y on the controller: the inventory, like the E key; or close whatever menu is open, like Escape. */
	private void toggleInventory(Minecraft minecraft, LocalPlayer player) {
		Screen screen = minecraft.gui.screen();
		if (screen != null) {
			screen.onClose();
		} else if (minecraft.gameMode.isServerControlledInventory()) {
			player.sendOpenInventory();
		} else {
			minecraft.getTutorial().onOpenInventory();
			minecraft.gui.setScreen(new InventoryScreen(player));
		}
	}

	private void unlink(Minecraft minecraft) {
		LOGGER.info("Gorilla Passthrough: Gorilla Tag left, Minecraft plays normally again");
		this.heldItem.reset();
		this.pointer.reset(minecraft);
		if (this.holdingUse) {
			this.holdingUse = false;
			minecraft.options.keyUse.setDown(false);
		}
		this.screenOpen = false;
		this.linkedPlayer = null;
		this.minePos = null;
		this.focus = null;
		this.setGorilla(minecraft, null);
	}

	/**
	 * Gorilla-sized (0.5 x 0.8 blocks), here and on the server, so one-block holes fit; the server (built-in or a
	 * friend's) also starts trusting the gorilla's movement and tells the other players.
	 */
	private void setGorilla(Minecraft minecraft, @Nullable UUID uuid) {
		Gorilla.setLocal(uuid);
		if (minecraft.player != null) {
			minecraft.player.refreshDimensions();
		}
		if (ClientPlayNetworking.canSend(GorillaNet.Hello.TYPE)) {
			ClientPlayNetworking.send(new GorillaNet.Hello(uuid != null, this.furColor));
		} else if (uuid != null) {
			LOGGER.warn("Gorilla Passthrough: this server doesn't have the Gorilla Passthrough mod; it may pull the gorilla back now and then");
		}
	}

	/** Every other tick: where the hands are (relative to the feet), so other players see the gorilla's arms move. */
	private void sendHands(LocalPlayer player, @Nullable Pose pose) {
		if (pose == null || !ClientPlayNetworking.canSend(GorillaNet.Hands.TYPE)) {
			return;
		}
		ClientPlayNetworking.send(new GorillaNet.Hands(player.getId(),
			(float) (pose.leftX() - player.getX()), (float) (pose.leftY() - player.getY()), (float) (pose.leftZ() - player.getZ()),
			(float) (pose.rightX() - player.getX()), (float) (pose.rightY() - player.getY()), (float) (pose.rightZ() - player.getZ())));
	}

	/** Other gorillas' hands, by entity id, with when they arrived. */
	public static final java.util.Map<Integer, GorillaNet.Hands> REMOTE_HANDS = new java.util.concurrent.ConcurrentHashMap<>();
	public static final java.util.Map<Integer, Long> REMOTE_HANDS_TIME = new java.util.concurrent.ConcurrentHashMap<>();

	/** Another player drawn by our Gorilla Tag as a real Gorilla Tag gorilla (so Minecraft doesn't draw it too). */
	public boolean drawnByGorillaTag(Entity entity) {
		return this.isLinked() && RemoteGorillas.drawnByGorillaTag(entity);
	}

	private static void registerMultiplayer() {
		RemoteGorillas.register();
		ClientPlayNetworking.registerGlobalReceiver(GorillaNet.Gorillas.TYPE, (gorillas, context) -> {
			Gorilla.CLIENT.clear();
			for (int i = 0; i < gorillas.players().size(); i++) {
				Gorilla.CLIENT.put(gorillas.players().get(i), gorillas.colors().get(i));
			}
			if (context.client().level != null) {
				for (net.minecraft.world.entity.player.Player other : context.client().level.players()) {
					other.refreshDimensions();
				}
			}
		});
		ClientPlayNetworking.registerGlobalReceiver(GorillaNet.Hands.TYPE, (hands, context) -> {
			REMOTE_HANDS.put(hands.entity(), hands);
			REMOTE_HANDS_TIME.put(hands.entity(), net.minecraft.util.Util.getMillis());
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			Gorilla.CLIENT.clear();
			RemoteGorillas.clear();
			REMOTE_HANDS.clear();
			REMOTE_HANDS_TIME.clear();
		});
	}

	/** Puts the Minecraft player where Gorilla Tag's physics put the gorilla. */
	private void followGorilla(LocalPlayer player, Pose pose) {
		Vec3 motion = new Vec3(pose.feetX() - player.getX(), pose.feetY() - player.getY(), pose.feetZ() - player.getZ());
		if (motion.lengthSqr() > 64.0) {
			motion = Vec3.ZERO; // a jump that big is a teleport, not movement
		}
		player.setPos(pose.feetX(), pose.feetY(), pose.feetZ());
		player.setDeltaMovement(motion);
		player.setYRot(pose.yaw());
		player.setXRot(pose.pitch());
		player.setYHeadRot(pose.yaw());
		player.setYBodyRot(pose.yaw());
		player.setOnGround((pose.flags() & Protocol.POSE_ON_GROUND) != 0);
		player.resetFallDistance();
	}

	private void handle(Minecraft minecraft, LocalPlayer player, HostLink.Message message) throws IOException {
		DataInputStream in = new DataInputStream(new ByteArrayInputStream(message.payload()));
		MultiPlayerGameMode gameMode = minecraft.gameMode;
		if (gameMode == null) {
			return;
		}
		switch (message.type()) {
			case Protocol.C_HELLO -> {
				int version = in.readInt();
				float refresh = in.available() >= 4 ? in.readFloat() : 0.0F;
				if (in.available() >= 12) {
					// Gorilla Tag's fur colour, so other players see your gorilla in it
					this.furColor = 0xFF000000 | channel(in.readFloat()) << 16 | channel(in.readFloat()) << 8 | channel(in.readFloat());
					if (this.linkedPlayer != null && ClientPlayNetworking.canSend(GorillaNet.Hello.TYPE)) {
						ClientPlayNetworking.send(new GorillaNet.Hello(true, this.furColor));
					}
				}
				// the headset shows at most this many pictures a second; drawing more only takes the graphics card from VR
				this.frameLimit = refresh >= 30.0F ? Math.round(Math.min(refresh, 144.0F)) : 90;
				LOGGER.info("Gorilla Passthrough: Gorilla Tag plugin protocol {}, headset at {} Hz: Minecraft draws up to {} frames a second",
					version, refresh, this.frameLimit);
			}
			case Protocol.C_MINE, Protocol.C_PUNCH -> {
				BlockPos pos = new BlockPos(in.readInt(), in.readInt(), in.readInt());
				Direction face = Direction.from3DDataValue(in.readByte());
				if (!pos.equals(this.minePos)) {
					gameMode.stopDestroyBlock();
				}
				this.minePos = pos;
				this.mineFace = face;
				// holding the trigger keeps it going; a punch is a few ticks of mining
				this.mineUntil = Math.max(this.mineUntil, this.tick + (message.type() == Protocol.C_PUNCH ? 6 : 3));
			}
			case Protocol.C_USE_ON -> {
				BlockPos pos = new BlockPos(in.readInt(), in.readInt(), in.readInt());
				Direction face = Direction.from3DDataValue(in.readByte());
				Vec3 hit = new Vec3(in.readFloat(), in.readFloat(), in.readFloat());
				var result = gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false));
				if (!result.consumesAction()) {
					gameMode.useItem(player, InteractionHand.MAIN_HAND);
				}
			}
			case Protocol.C_USE_ITEM -> {
				// holding grip in the air = holding Minecraft's use button: eating and drinking take a moment, bows draw,
				// shields stay up; Minecraft lets go of the item the moment the button isn't down
				this.holdingUse = in.readByte() != 0;
				minecraft.options.keyUse.setDown(this.holdingUse);
				if (this.holdingUse && !player.isUsingItem()) {
					gameMode.useItem(player, InteractionHand.MAIN_HAND);
				}
			}
			case Protocol.C_ATTACK -> {
				Entity entity = minecraft.level.getEntity(in.readInt());
				if (entity != null && entity != player && entity.isAlive()) {
					gameMode.attack(player, entity);
				}
			}
			case Protocol.C_SLOT -> {
				int delta = in.readByte();
				var inventory = player.getInventory();
				inventory.setSelectedSlot(Math.floorMod(inventory.getSelectedSlot() + delta, 9));
			}
			case Protocol.C_FOCUS -> {
				if (in.readByte() != 0) {
					BlockPos pos = new BlockPos(in.readInt(), in.readInt(), in.readInt());
					Direction face = Direction.from3DDataValue(in.readByte());
					this.focus = new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
					this.focusUntil = this.tick + 5;
				} else {
					this.focus = null;
				}
			}
			case Protocol.C_DROP -> gameMode.dropItem(player, in.readByte() != 0);
			case Protocol.C_INVENTORY -> this.toggleInventory(minecraft, player);
			default -> LOGGER.debug("Gorilla Passthrough: unknown message {}", message.type());
		}
	}

	private void mine(Minecraft minecraft) {
		if (this.minePos == null || minecraft.gameMode == null) {
			return;
		}
		if (this.tick > this.mineUntil) {
			minecraft.gameMode.stopDestroyBlock();
			this.minePos = null;
			return;
		}
		// continueDestroyBlock starts on a new block by itself and keeps chipping at the current one
		minecraft.gameMode.continueDestroyBlock(this.minePos, this.mineFace);
	}

	private void sendEntities(ClientLevel level, LocalPlayer player) {
		Payload entities = new Payload();
		int count = 0;
		for (Entity entity : level.entitiesForRendering()) {
			if (entity == player || !entity.isAlive() || !entity.isAttackable() || entity.distanceToSqr(player) > 24.0 * 24.0) {
				continue;
			}
			AABB box = entity.getBoundingBox();
			entities.i(entity.getId()).f((float) box.minX).f((float) box.minY).f((float) box.minZ)
				.f((float) box.maxX).f((float) box.maxY).f((float) box.maxZ);
			if (++count >= 64) {
				break;
			}
		}
		Payload message = new Payload().s(count);
		message.raw(entities.bytes.toByteArray());
		this.link.send(Protocol.S_ENTITIES, message.bytes);
	}

	private void sendStatus(LocalPlayer player) {
		boolean hurt = player.hurtTime > this.lastHurtTime;
		this.lastHurtTime = player.hurtTime;
		this.link.send(Protocol.S_STATUS, new Payload().f(player.getHealth()).f(player.getMaxHealth())
			.i(player.getFoodData().getFoodLevel()).i(player.getAirSupply()).i(player.getInventory().getSelectedSlot())
			.b(player.isDeadOrDying() ? 1 : 0).b(hurt ? 1 : 0)
			.b(player.level().getMaxLocalRawBrightness(player.blockPosition().above())).bytes);
	}

	/** Nobody can click "Respawn" on the monitor from inside the headset. */
	private void autoRespawn(Minecraft minecraft, LocalPlayer player) {
		if (!player.isDeadOrDying()) {
			this.deadTicks = 0;
			return;
		}
		if (++this.deadTicks == AUTO_RESPAWN_TICKS) {
			player.respawn();
			if (minecraft.gui.screen() instanceof DeathScreen) {
				minecraft.gui.setScreen(null);
			}
		}
	}
}
