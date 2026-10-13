package com.gorillapass.client;

/**
 * The passthrough link between this Minecraft client and the Gorilla Tag plugin. Keep in sync with
 * gt/src/Protocol.cs.
 *
 * <p>Small messages go over TCP on 127.0.0.1:{@link #PORT} as [int length][byte type][payload], big-endian.
 * Minecraft's rendered frames (colour + a depth grid) go through Windows shared memory {@link #SHARED_MEMORY_NAME}.
 */
public final class Protocol {
	public static final int VERSION = 7;
	/** -Dgorillapass.port changes it, to test two linked Minecrafts on one PC */
	public static final int PORT = Integer.getInteger("gorillapass.port", 25600);

	// Minecraft -> Gorilla Tag
	public static final byte S_HELLO = 0x01;
	/** int sx, sy, sz; byte[512] full-block bitmask; short n; n x (short cell, 6 bytes box in 1/16 block). */
	public static final byte S_SECTION = 0x02;
	/** int sx, sy, sz */
	public static final byte S_UNLOAD_SECTION = 0x03;
	/** utf dimension: drop every collider (new world or dimension). */
	public static final byte S_CLEAR = 0x04;
	/** int id; double x, y, z (feet); float yaw: Minecraft moved the player, move the gorilla there. */
	public static final byte S_TELEPORT = 0x05;
	/** short n; n x (int id; float minX, minY, minZ, maxX, maxY, maxZ) */
	public static final byte S_ENTITIES = 0x06;
	/** float health, maxHealth; int food; int air; int selected slot; byte dead; byte hurt; byte light (0..15 at the gorilla) */
	public static final byte S_STATUS = 0x07;
	/** byte open; utf title: a Minecraft menu (inventory, chest, crafting table, death screen...) opened or closed. */
	public static final byte S_SCREEN = 0x08;
	/** byte kind (0 nothing, 1 flat item, 2 block); short width, height; width*height RGBA bytes, top row first */
	public static final byte S_HELD = 0x09;
	/** four times (helmet, chestplate, leggings, boots) the same as S_HELD's payload */
	public static final byte S_ARMOR = 0x0A;
	/** int entity; int fur colour (ARGB); the other gorilla's pose, as its Gorilla Tag made it (C_RIG's payload) */
	public static final byte S_REMOTE_RIG = 0x0B;
	/** int entity; five times the S_HELD format: held item, helmet, chestplate, leggings, boots */
	public static final byte S_REMOTE_EQUIPMENT = 0x0C;
	/** int entity: that gorilla is gone (left, out of sight, or its Gorilla Tag stopped) */
	public static final byte S_REMOTE_GONE = 0x0D;

	// Gorilla Tag -> Minecraft
	/** int version; float the headset's refresh rate (Hz), so Minecraft draws no faster than that */
	public static final byte C_HELLO = (byte) 0x81;
	/**
	 * int poseId, teleportAck; double headX, headY, headZ; float qx, qy, qz, qw (camera rotation, Minecraft axes);
	 * float tanH, tanV (half field of view tangents); float maxDistance; double feetX, feetY, feetZ; float yaw, pitch;
	 * byte flags
	 */
	public static final byte C_POSE = (byte) 0x82;
	/** int x, y, z; byte face: keep mining this block (sent ~20 times a second while held). */
	public static final byte C_MINE = (byte) 0x83;
	/** int x, y, z; byte face: a slap against a block chips at it. */
	public static final byte C_PUNCH = (byte) 0x84;
	/** int x, y, z; byte face; float hitX, hitY, hitZ */
	public static final byte C_USE_ON = (byte) 0x85;
	/** byte start(1)/stop(0) */
	public static final byte C_USE_ITEM = (byte) 0x86;
	/** int entityId */
	public static final byte C_ATTACK = (byte) 0x87;
	/** byte delta (+1 / -1) */
	public static final byte C_SLOT = (byte) 0x88;
	/** byte has; int x, y, z; byte face: the block a hand rests on, outlined in Minecraft. */
	public static final byte C_FOCUS = (byte) 0x89;
	/** byte 0: drop one item */
	public static final byte C_DROP = (byte) 0x8A;
	/** open the inventory, or close whatever menu is open */
	public static final byte C_INVENTORY = (byte) 0x8B;
	/** float u, v (0..1 across the menu, v from the bottom); byte buttons (1 = left, 2 = right); byte shift */
	public static final byte C_POINTER = (byte) 0x8C;
	/** this gorilla's pose for the other players (made and read by the plugin; Minecraft only passes it on) */
	public static final byte C_RIG = (byte) 0x8D;

	public static final int POSE_ON_GROUND = 1;

	// shared memory
	public static final String SHARED_MEMORY_NAME = System.getProperty("gorillapass.shm", "Local\\GorillaPassthroughFrames");
	public static final int MAGIC = 0x47505431; // "GPT1"
	public static final int SLOTS = 3;
	public static final int MAX_WIDTH = 2560;
	public static final int MAX_HEIGHT = 2560;
	public static final int MAX_GRID = 320 * 320;
	/** every grid point, plus at most one copy of each corner of each cell on a depth edge */
	public static final int MAX_VERTICES = MAX_GRID * 5;
	/** two triangles per cell, twice for a cell on a depth edge, and once more for background carried in behind one */
	public static final int MAX_INDICES = MAX_GRID * 18;
	/** position float x3, colour unorm8 x4, uv float x2: the order Unity wants vertex attributes in */
	public static final int VERTEX_BYTES = 24;
	public static final int PANEL_MAX_WIDTH = 1280;
	public static final int PANEL_MAX_HEIGHT = 720;

	public static final int HEADER_BYTES = 256;
	public static final int H_MAGIC = 0;
	public static final int H_VERSION = 4;
	public static final int H_SLOT_BYTES = 8;
	public static final int H_LATEST = 12;
	public static final int H_READER = 16;
	public static final int H_FRAME = 20;
	public static final int H_PANEL_OFFSET = 24;

	public static final int S_SEQ = 0;
	public static final int S_POSE_ID = 8;
	public static final int S_WIDTH = 12;
	public static final int S_HEIGHT = 16;
	public static final int S_GRID_W = 20;
	public static final int S_GRID_H = 24;
	public static final int S_TAN_H = 28;
	public static final int S_TAN_V = 32;
	/** float r, g, b (sRGB 0..1): the sky straight up, and at the horizon, for the backdrop behind holes */
	public static final int S_SKY_TOP = 36;
	public static final int S_SKY_HORIZON = 48;
	public static final int S_VERTEX_COUNT = 60;
	/** int counts of the three index lists, stored back to back: plain cells, edge cells' far copies, near copies */
	public static final int S_OPAQUE_INDICES = 64;
	public static final int S_BACK_INDICES = 68;
	public static final int S_FRONT_INDICES = 72;
	/** int width, height of the background picture */
	public static final int S_BG_WIDTH = 76;
	public static final int S_BG_HEIGHT = 80;
	public static final int S_VERTEX_DATA = 128;
	public static final int S_INDEX_DATA = S_VERTEX_DATA + MAX_VERTICES * VERTEX_BYTES;
	/** RGBA, bottom row first; in cells on a depth edge alpha says near (255) or far (0) */
	public static final int S_COLOR_DATA = S_INDEX_DATA + MAX_INDICES * 4;
	/**
	 * The background picture, RGBA, bottom row first, a quarter of the colour picture's size: what is behind near things,
	 * filled in from the background around them where Minecraft couldn't see it. Shown where your eyes see past an edge.
	 */
	public static final int S_BG_DATA = S_COLOR_DATA + MAX_WIDTH * MAX_HEIGHT * 4;
	public static final int BG_DIVISOR = 4;
	public static final int SLOT_BYTES = S_BG_DATA + (MAX_WIDTH / BG_DIVISOR) * (MAX_HEIGHT / BG_DIVISOR) * 4;

	public static final int P_SEQ = 0;
	public static final int P_WIDTH = 8;
	public static final int P_HEIGHT = 12;
	public static final int P_DATA = 16;
	public static final int PANEL_OFFSET = HEADER_BYTES + SLOTS * SLOT_BYTES;
	public static final long TOTAL_BYTES = (long) PANEL_OFFSET + P_DATA + PANEL_MAX_WIDTH * PANEL_MAX_HEIGHT * 4;

	private Protocol() {
	}
}
