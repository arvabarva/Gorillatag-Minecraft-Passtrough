package com.gorillapass.client;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Tells Gorilla Tag which blocks around the gorilla are solid, one 16x16x16 section at a time, so it can build the
 * (invisible) colliders the gorilla climbs on. What you see is Minecraft's own picture; this is only the physics.
 * Sections are re-sent when a block in them changes and dropped when the gorilla moves away.
 */
final class WorldStreamer {
	private static final int RADIUS_CHUNKS = 3;
	private static final int RADIUS_SECTIONS_BELOW = 2;
	private static final int RADIUS_SECTIONS_ABOVE = 2;
	private static final int SECTIONS_PER_TICK = 24;

	/** Per block state: null = no collision, FULL = whole block, otherwise the collision shape's bounds. */
	private static final byte[] FULL = new byte[0];
	private static final byte[] EMPTY = new byte[0];

	private final Map<BlockState, byte[]> shapes = new IdentityHashMap<>();
	private final Map<Long, Integer> sent = new HashMap<>();
	private final Set<Long> dirty = new HashSet<>();
	private final ArrayDeque<Long> queue = new ArrayDeque<>();
	private final Set<Long> queued = new HashSet<>();

	void reset() {
		this.sent.clear();
		this.dirty.clear();
		this.queue.clear();
		this.queued.clear();
	}

	void markBlock(BlockPos pos) {
		this.dirty.add(SectionPos.asLong(pos));
	}

	void chunkLoaded(int chunkX, int chunkZ) {
		// a newly arrived chunk may replace an empty one we already told Gorilla Tag about
		this.sent.keySet().removeIf(key -> SectionPos.x(key) == chunkX && SectionPos.z(key) == chunkZ);
	}

	void tick(ClientLevel level, BlockPos feet, HostLink link) {
		int centerX = SectionPos.blockToSectionCoord(feet.getX());
		int centerY = SectionPos.blockToSectionCoord(feet.getY());
		int centerZ = SectionPos.blockToSectionCoord(feet.getZ());
		int minY = Math.max(level.getMinSectionY(), centerY - RADIUS_SECTIONS_BELOW);
		int maxY = Math.min(level.getMaxSectionY(), centerY + RADIUS_SECTIONS_ABOVE);

		// forget sections the gorilla has moved away from
		Iterator<Long> it = this.sent.keySet().iterator();
		while (it.hasNext()) {
			long key = it.next();
			int sx = SectionPos.x(key);
			int sy = SectionPos.y(key);
			int sz = SectionPos.z(key);
			if (Math.abs(sx - centerX) > RADIUS_CHUNKS + 1 || Math.abs(sz - centerZ) > RADIUS_CHUNKS + 1 || sy < minY - 1 || sy > maxY + 1) {
				it.remove();
				link.send(Protocol.S_UNLOAD_SECTION, new Payload().i(sx).i(sy).i(sz).bytes);
			}
		}

		// nearest sections first, so the ground under the gorilla arrives before anything else
		for (int r = 0; r <= RADIUS_CHUNKS; r++) {
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
						continue;
					}
					for (int sy = minY; sy <= maxY; sy++) {
						long key = SectionPos.asLong(centerX + dx, sy, centerZ + dz);
						if ((!this.sent.containsKey(key) || this.dirty.contains(key)) && this.queued.add(key)) {
							this.queue.add(key);
						}
					}
				}
			}
		}

		int budget = SECTIONS_PER_TICK;
		while (budget > 0 && !this.queue.isEmpty()) {
			long key = this.queue.poll();
			this.queued.remove(key);
			int sx = SectionPos.x(key);
			int sz = SectionPos.z(key);
			LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, false);
			if (chunk == null) {
				continue; // not loaded yet; it is queued again once it arrives
			}
			this.dirty.remove(key);
			budget--;
			Payload payload = this.encode(level, chunk, sx, SectionPos.y(key), sz);
			Integer hash = payload.hash;
			if (hash.equals(this.sent.get(key))) {
				continue;
			}
			this.sent.put(key, hash);
			link.send(Protocol.S_SECTION, payload.bytes);
		}
	}

	private Payload encode(ClientLevel level, LevelChunk chunk, int sx, int sy, int sz) {
		byte[] mask = new byte[512];
		Payload partials = new Payload();
		int partialCount = 0;
		int index = level.getSectionIndexFromSectionY(sy);
		if (index >= 0 && index < chunk.getSections().length) {
			LevelChunkSection section = chunk.getSection(index);
			if (!section.hasOnlyAir()) {
				for (int y = 0; y < 16; y++) {
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							byte[] shape = this.shapeOf(section.getBlockState(x, y, z));
							if (shape == EMPTY) {
								continue;
							}
							int cell = (y * 16 + z) * 16 + x;
							if (shape == FULL) {
								mask[cell >> 3] |= (byte) (1 << (cell & 7));
							} else {
								partials.s(cell).raw(shape);
								partialCount++;
							}
						}
					}
				}
			}
		}
		Payload payload = new Payload().i(sx).i(sy).i(sz).raw(mask).s(partialCount);
		payload.raw(partials.bytes.toByteArray());
		payload.hash = java.util.Arrays.hashCode(payload.bytes.toByteArray());
		return payload;
	}

	private byte[] shapeOf(BlockState state) {
		byte[] shape = this.shapes.get(state);
		if (shape == null) {
			VoxelShape collision = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
			if (collision.isEmpty()) {
				shape = EMPTY;
			} else if (state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
				shape = FULL;
			} else {
				AABB box = collision.bounds();
				shape = new byte[]{
					sixteenths(box.minX), sixteenths(box.minY), sixteenths(box.minZ),
					sixteenths(box.maxX), sixteenths(box.maxY), sixteenths(box.maxZ)
				};
			}
			this.shapes.put(state, shape);
		}
		return shape;
	}

	private static byte sixteenths(double value) {
		return (byte) Math.clamp(Math.round(value * 16.0), 0, 32);
	}

	/** A small big-endian writer for message payloads. */
	static final class Payload {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		private final DataOutputStream out = new DataOutputStream(this.bytes);
		Integer hash;

		Payload i(int v) {
			try {
				this.out.writeInt(v);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			return this;
		}

		Payload s(int v) {
			try {
				this.out.writeShort(v);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			return this;
		}

		Payload b(int v) {
			try {
				this.out.writeByte(v);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			return this;
		}

		Payload f(float v) {
			try {
				this.out.writeFloat(v);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			return this;
		}

		Payload d(double v) {
			try {
				this.out.writeDouble(v);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			return this;
		}

		Payload utf(String v) {
			try {
				this.out.writeUTF(v);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			return this;
		}

		Payload raw(byte[] v) {
			try {
				this.out.write(v);
			} catch (IOException e) {
				throw new IllegalStateException(e);
			}
			return this;
		}
	}
}
