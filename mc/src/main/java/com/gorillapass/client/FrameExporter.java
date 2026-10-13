package com.gorillapass.client;

import static com.gorillapass.client.Protocol.*;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies what Minecraft drew from the gorilla's eyes (before the HUD) into shared memory for Gorilla Tag: the colour
 * image, plus a grid of 3D points read from the depth buffer. Gorilla Tag turns that grid into a mesh at the exact head
 * pose the frame was drawn from, so the world stays put in VR even though Minecraft's picture arrives a frame or two
 * late. The GPU copies are asynchronous (up to three in flight) so Minecraft never waits on them.
 */
public final class FrameExporter {
	private static final Logger LOGGER = LoggerFactory.getLogger("gorillapass");
	private static final int READBACKS = 3;
	private static final int PANEL_EVERY_FRAMES = 4;
	/** A menu you are pointing at needs a sharper, quicker picture than the wrist screen. */
	private static final int PANEL_EVERY_FRAMES_IN_MENU = 2;
	/** Grid columns; rows follow from the field of view so the cells are roughly square in angle. */
	private static final int GRID_COLUMNS = 320;
	private static final boolean DEBUG = Boolean.getBoolean("gorillapass.debug");

	private @Nullable SharedMemory memory;
	private boolean failed;
	private long failedAt;
	private final Readback[] readbacks = new Readback[READBACKS];
	private final PanelReadback[] panels = new PanelReadback[2];
	private int frameCounter;
	private int panelFrame;
	private float[] grid = new float[0];
	private final DepthMesh mesh = new DepthMesh();
	private final float[] skyTop = {0.47F, 0.65F, 1.0F};
	private final float[] skyHorizon = {0.75F, 0.85F, 1.0F};

	public static final FrameExporter INSTANCE = new FrameExporter();

	private FrameExporter() {
		for (int i = 0; i < READBACKS; i++) {
			this.readbacks[i] = new Readback();
		}
		for (int i = 0; i < this.panels.length; i++) {
			this.panels[i] = new PanelReadback();
		}
	}

	private static final class Readback {
		@Nullable GpuBuffer color;
		@Nullable GpuBuffer depth;
		int width;
		int height;
		boolean busy;
		int pending;
		int poseId;
		float tanH;
		float tanV;
		float maxDistance;
		final Matrix4f inverseProjection = new Matrix4f();
		final org.joml.Quaternionf rotation = new org.joml.Quaternionf();
		boolean zZeroToOne;
		/** Copied, waiting for the worker. */
		boolean ready;
	}

	/**
	 * A frame the worker thread is turning into a mesh. Its colour is already in its shared memory slot and its depth in
	 * memory of its own (both copied on the render thread, so nothing of the GPU's stays in use across frames). One at a
	 * time: while the worker is busy, newer frames are skipped.
	 */
	private static final class Job {
		java.lang.foreign.Arena arena = java.lang.foreign.Arena.ofShared();
		MemorySegment depth = MemorySegment.NULL;
		int width;
		int height;
		int step;
		int poseId;
		float tanH;
		float tanV;
		float maxDistance;
		final Matrix4f inverseProjection = new Matrix4f();
		final org.joml.Quaternionf rotation = new org.joml.Quaternionf();
		boolean zZeroToOne;
		int slot;
		long seq;
		volatile boolean busy;
	}

	private final Job job = new Job();
	// what happened lately, for the log every 10 seconds
	private int drawnCount;
	private int skippedCount;
	private volatile int sharedCount;
	private long statusAt;

	/** Builds the mesh and fills shared memory, one frame after the other, off Minecraft's render thread. */
	private static final java.util.concurrent.ExecutorService WORKER = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "Gorilla Passthrough frames");
		thread.setDaemon(true);
		return thread;
	});

	private static final class PanelReadback {
		@Nullable GpuBuffer color;
		int width;
		int height;
		boolean busy;
	}

	private boolean ensureMemory() {
		if (this.memory != null) {
			return true;
		}
		if (this.failed && System.currentTimeMillis() - this.failedAt < 5_000L) {
			return false; // try again every few seconds (something else may be holding a mapping of the same name)
		}
		try {
			SharedMemory created = SharedMemory.create(SHARED_MEMORY_NAME, TOTAL_BYTES);
			created.setIntVolatile(H_LATEST, -1);
			created.setIntVolatile(H_READER, -1);
			created.setIntVolatile(H_SLOT_BYTES, SLOT_BYTES);
			created.setIntVolatile(H_PANEL_OFFSET, PANEL_OFFSET);
			created.setIntVolatile(H_VERSION, VERSION);
			created.setIntVolatile(H_MAGIC, MAGIC);
			this.memory = created;
			LOGGER.info("Gorilla Passthrough: sharing frames through {} ({} MB)", SHARED_MEMORY_NAME, TOTAL_BYTES >> 20);
			return true;
		} catch (Throwable e) {
			if (!this.failed) {
				LOGGER.error("Gorilla Passthrough: couldn't create shared memory, Gorilla Tag won't see Minecraft (trying again every 5 s;"
					+ " an old Gorilla Tag still running from before may be holding it: close it)", e);
			}
			this.failed = true;
			this.failedAt = System.currentTimeMillis();
			return false;
		}
	}

	/** Called right after the world is drawn into the main target, before the HUD. */
	public void captureWorld(RenderTarget target, Matrix4fc projection, PassthroughClient.Pose pose) {
		if (!ensureMemory() || target.getColorTexture() == null || target.getDepthTexture() == null) {
			return;
		}
		this.logStatus();
		this.dispatch();
		Readback readback = null;
		for (Readback candidate : this.readbacks) {
			if (!candidate.busy) {
				readback = candidate;
				break;
			}
		}
		if (readback == null) {
			// the GPU is still copying the older frames; drop this one rather than stall Minecraft
			this.skippedCount++;
			return;
		}
		this.drawnCount++;

		int width = target.width;
		int height = target.height;
		GpuDevice device = RenderSystem.getDevice();
		if (readback.color == null || readback.width != width || readback.height != height) {
			if (readback.color != null) {
				readback.color.close();
				readback.depth.close();
			}
			long bytes = (long) width * height * 4;
			readback.color = device.createBuffer(() -> "Gorilla Passthrough colour", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
			readback.depth = device.createBuffer(() -> "Gorilla Passthrough depth", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, bytes);
			readback.width = width;
			readback.height = height;
		}
		readback.busy = true;
		readback.pending = 2;
		readback.poseId = pose.id();
		readback.tanH = pose.tanH();
		readback.tanV = pose.tanV();
		readback.maxDistance = pose.maxDistance();
		readback.inverseProjection.set(projection).invert();
		readback.rotation.set(pose.qx(), pose.qy(), pose.qz(), pose.qw()).normalize();
		readback.zZeroToOne = device.getDeviceInfo().isZZeroToOne();

		Readback finished = readback;
		CommandEncoder encoder = device.createCommandEncoder();
		encoder.copyTextureToBuffer(target.getColorTexture(), readback.color, 0L, () -> this.copied(finished), 0);
		encoder.copyTextureToBuffer(target.getDepthTexture(), readback.depth, 0L, () -> this.copied(finished), 0);
	}

	/**
	 * On the render thread, when the GPU finished copying a frame: its colour goes straight into a free shared memory slot
	 * and its depth into the job's own memory, the GPU copies are let go, and the worker thread builds the mesh from there
	 * while Minecraft goes on drawing.
	 */
	private void copied(Readback readback) {
		if (--readback.pending > 0) {
			return;
		}
		readback.ready = true;
		this.dispatch();
	}

	/**
	 * On the render thread: when the worker is free, the newest copied frame goes to it (older ones waiting are dropped).
	 * Called when a copy finishes and every frame, so a frame copied while the worker was busy goes as soon as it's free.
	 */
	private void dispatch() {
		if (this.job.busy) {
			return;
		}
		Readback newest = null;
		for (Readback candidate : this.readbacks) {
			if (candidate.ready && (newest == null || candidate.poseId > newest.poseId)) {
				newest = candidate;
			}
		}
		if (newest == null) {
			return;
		}
		for (Readback candidate : this.readbacks) {
			if (candidate.ready && candidate != newest) {
				candidate.ready = false;
				candidate.busy = false;
				this.skippedCount++;
			}
		}
		newest.ready = false;
		this.hand(newest);
	}

	/** Colour into a free shared memory slot, depth into the job's memory, the GPU copies let go; the worker goes on. */
	private void hand(Readback readback) {
		Job job = this.job;
		try {
			SharedMemory memory = this.memory;
			int latest = memory.getIntVolatile(H_LATEST);
			int reading = memory.getIntVolatile(H_READER);
			int slot = 0;
			while (slot == latest || slot == reading) {
				slot++;
			}
			long base = HEADER_BYTES + (long) slot * SLOT_BYTES;
			long seq = memory.getLongVolatile(base + S_SEQ);
			memory.setLongVolatile(base + S_SEQ, (seq | 1L) + 2L); // odd: being written

			int width = readback.width;
			int height = readback.height;
			int step = Math.max(1, Math.max(ceilDiv(width, MAX_WIDTH), ceilDiv(height, MAX_HEIGHT)));
			long depthBytes = (long) width * height * 4;
			if (job.depth.byteSize() < depthBytes) {
				job.arena.close();
				job.arena = java.lang.foreign.Arena.ofShared();
				job.depth = job.arena.allocate(depthBytes, 64);
			}
			try (GpuBufferSlice.MappedView colorView = readback.color.map(true, false);
				GpuBufferSlice.MappedView depthView = readback.depth.map(true, false)) {
				ByteBuffer color = colorView.data().order(ByteOrder.LITTLE_ENDIAN);
				// colour, bottom row first (the same order Unity textures use)
				MemorySegment segment = memory.segment;
				if (step == 1) {
					MemorySegment.copy(MemorySegment.ofBuffer(color), 0L, segment, base + S_COLOR_DATA, (long) width * height * 4);
				} else {
					long out = base + S_COLOR_DATA;
					for (int y = 0; y < height / step; y++) {
						int row = y * step * width;
						for (int x = 0; x < width / step; x++) {
							segment.set(ValueLayout.JAVA_INT_UNALIGNED, out, color.getInt((row + x * step) * 4));
							out += 4;
						}
					}
				}
				MemorySegment.copy(MemorySegment.ofBuffer(depthView.data()), 0L, job.depth, 0L, depthBytes);
			}
			job.width = width;
			job.height = height;
			job.step = step;
			job.poseId = readback.poseId;
			job.tanH = readback.tanH;
			job.tanV = readback.tanV;
			job.maxDistance = readback.maxDistance;
			job.inverseProjection.set(readback.inverseProjection);
			job.rotation.set(readback.rotation);
			job.zZeroToOne = readback.zZeroToOne;
			job.slot = slot;
			job.seq = seq;
			job.busy = true;
			WORKER.execute(() -> {
				try {
					this.publish(job);
				} catch (RuntimeException e) {
					LOGGER.warn("Gorilla Passthrough: couldn't share a frame", e);
				} finally {
					job.busy = false;
				}
			});
		} catch (RuntimeException e) {
			LOGGER.warn("Gorilla Passthrough: couldn't read a frame back", e);
		} finally {
			readback.busy = false;
		}
	}

	/** Every 10 seconds while Gorilla Tag is linked: how the frames are flowing (and a warning if they stopped). */
	private void logStatus() {
		long now = System.currentTimeMillis();
		if (this.statusAt == 0L) {
			this.statusAt = now;
			return;
		}
		if (now - this.statusAt < 10_000L) {
			return;
		}
		int shared = this.sharedCount;
		this.sharedCount = 0;
		String message = String.format("Gorilla Passthrough: last 10 s: %d frames copied, %d shared with Gorilla Tag, %d skipped (busy), worker %s",
			this.drawnCount, shared, this.skippedCount, this.job.busy ? "busy" : "idle");
		if (shared == 0 && this.drawnCount > 0) {
			LOGGER.warn(message);
		} else {
			LOGGER.info(message);
		}
		this.drawnCount = 0;
		this.skippedCount = 0;
		this.statusAt = now;
	}

	private void publish(Job job) {
		long started = System.nanoTime();
		this.publishShared(job);
		this.sharedCount++;
		if (DEBUG) {
			this.publishNanos += System.nanoTime() - started;
			if (this.frameCounter % 300 == 0) {
				LOGGER.info("Gorilla Passthrough: sharing a frame takes {} ms", String.format("%.2f", this.publishNanos / 300 / 1.0E6));
				this.publishNanos = 0L;
			}
		}
	}

	private long publishNanos;
	private final long[] stageNanos = new long[5];

	private void publishShared(Job readback) {
		SharedMemory memory = this.memory;
		int slot = readback.slot;
		long seq = readback.seq;
		long base = HEADER_BYTES + (long) slot * SLOT_BYTES;
		int width = readback.width;
		int height = readback.height;
		int step = readback.step;
		int outWidth = width / step;
		int outHeight = height / step;
		int gridW = GRID_COLUMNS;
		int gridH = Math.clamp(Math.round(GRID_COLUMNS * readback.tanV / readback.tanH), 16, MAX_GRID / GRID_COLUMNS);

		MemorySegment segment = memory.segment;
		ByteBuffer depth = readback.depth.asSlice(0L, (long) width * height * 4).asByteBuffer().order(ByteOrder.nativeOrder());
		// the colour is already in the slot (at the shared size)
		ByteBuffer color = segment.asSlice(base + S_COLOR_DATA, (long) outWidth * outHeight * 4).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
		long t0 = System.nanoTime();
		this.fillGrid(readback, depth, color, width, height, step, outWidth, gridW, gridH);
		long t1 = System.nanoTime();
		this.mesh.build(segment, base, this.grid, gridW, gridH);
		long t2 = System.nanoTime();
		this.mesh.classify(base + S_COLOR_DATA, depth, width, height, step, outWidth, outHeight, readback.inverseProjection,
			readback.zZeroToOne);
		long t3 = System.nanoTime();
		this.mesh.markSkirtNear(base + S_COLOR_DATA, outWidth, outHeight);
		long t4 = System.nanoTime();
		this.mesh.background(base + S_COLOR_DATA, outWidth, outHeight, base + S_BG_DATA, outWidth / BG_DIVISOR, outHeight / BG_DIVISOR);
		long t5 = System.nanoTime();
		if (DEBUG) {
			this.stageNanos[0] += t1 - t0;
			this.stageNanos[1] += t2 - t1;
			this.stageNanos[2] += t3 - t2;
			this.stageNanos[3] += t4 - t3;
			this.stageNanos[4] += t5 - t4;
			if ((this.frameCounter + 1) % 300 == 0) {
				LOGGER.info("Gorilla Passthrough: per frame grid {} build {} near/far {} skirt {} background {} ms",
					this.stageNanos[0] / 300 / 1.0E6, this.stageNanos[1] / 300 / 1.0E6, this.stageNanos[2] / 300 / 1.0E6,
					this.stageNanos[3] / 300 / 1.0E6, this.stageNanos[4] / 300 / 1.0E6);
				java.util.Arrays.fill(this.stageNanos, 0L);
			}
		}

		segment.set(ValueLayout.JAVA_INT_UNALIGNED, base + S_POSE_ID, readback.poseId);
		segment.set(ValueLayout.JAVA_INT_UNALIGNED, base + S_WIDTH, outWidth);
		segment.set(ValueLayout.JAVA_INT_UNALIGNED, base + S_HEIGHT, outHeight);
		segment.set(ValueLayout.JAVA_INT_UNALIGNED, base + S_GRID_W, gridW);
		segment.set(ValueLayout.JAVA_INT_UNALIGNED, base + S_GRID_H, gridH);
		segment.set(ValueLayout.JAVA_FLOAT_UNALIGNED, base + S_TAN_H, readback.tanH);
		segment.set(ValueLayout.JAVA_FLOAT_UNALIGNED, base + S_TAN_V, readback.tanV);
		segment.set(ValueLayout.JAVA_INT_UNALIGNED, base + S_VERTEX_COUNT, this.mesh.vertexCount);
		segment.set(ValueLayout.JAVA_INT_UNALIGNED, base + S_OPAQUE_INDICES, this.mesh.opaqueCount);
		segment.set(ValueLayout.JAVA_INT_UNALIGNED, base + S_BACK_INDICES, this.mesh.backCount);
		segment.set(ValueLayout.JAVA_INT_UNALIGNED, base + S_FRONT_INDICES, this.mesh.frontCount);
		segment.set(ValueLayout.JAVA_INT_UNALIGNED, base + S_BG_WIDTH, outWidth / BG_DIVISOR);
		segment.set(ValueLayout.JAVA_INT_UNALIGNED, base + S_BG_HEIGHT, outHeight / BG_DIVISOR);
		for (int c = 0; c < 3; c++) {
			segment.set(ValueLayout.JAVA_FLOAT_UNALIGNED, base + S_SKY_TOP + c * 4L, this.skyTop[c]);
			segment.set(ValueLayout.JAVA_FLOAT_UNALIGNED, base + S_SKY_HORIZON + c * 4L, this.skyHorizon[c]);
		}
		memory.setLongVolatile(base + S_SEQ, (seq | 1L) + 3L); // even: complete
		memory.setIntVolatile(H_LATEST, slot);
		memory.setIntVolatile(H_FRAME, ++this.frameCounter);
	}

	/**
	 * One point per grid vertex, in the camera's own space with Unity's axes (x right, y up, z forward), so Gorilla
	 * Tag can place the mesh with the head pose it sent. Sky and anything past the far limit sit at maxDistance.
	 */
	private void fillGrid(Job readback, ByteBuffer depth, ByteBuffer color, int width, int height, int step, int colorWidth, int gridW, int gridH) {
		int floats = gridW * gridH * 3;
		if (this.grid.length < floats) {
			this.grid = new float[floats];
		}
		float maxDistance = readback.maxDistance;
		Vector4f point = new Vector4f();
		org.joml.Vector3f direction = new org.joml.Vector3f();
		float[] top = new float[4];
		float[] horizon = new float[4];
		if (DEBUG && this.frameCounter % 300 == 0) {
			float min = Float.MAX_VALUE;
			float max = -Float.MAX_VALUE;
			for (int p = 0; p < width * height; p += 97) {
				float d = depth.getFloat(p * 4);
				min = Math.min(min, d);
				max = Math.max(max, d);
			}
			float center = depth.getFloat(((height / 2) * width + width / 2) * 4);
			readback.inverseProjection.transform(point.set(0.0F, 0.0F, readback.zZeroToOne ? center : center * 2.0F - 1.0F, 1.0F));
			LOGGER.info("Gorilla Passthrough depth: min {} max {} centre {} -> ({}, {}, {}) zZeroToOne {} inverse projection {}", min, max, center,
				point.x / point.w, point.y / point.w, point.z / point.w, readback.zZeroToOne, readback.inverseProjection);
		}
		int i = 0;
		for (int gy = 0; gy < gridH; gy++) {
			float v = gy / (float) (gridH - 1);
			int py = Math.min(height - 1, (int) (v * (height - 1) + 0.5F));
			float ndcY = v * 2.0F - 1.0F;
			for (int gx = 0; gx < gridW; gx++) {
				float u = gx / (float) (gridW - 1);
				int px = Math.min(width - 1, (int) (u * (width - 1) + 0.5F));
				float ndcX = u * 2.0F - 1.0F;
				float d = depth.getFloat((py * width + px) * 4);
				float x;
				float y;
				float z;
				boolean far = d <= 1.0E-7F;
				if (!far) {
					float ndcZ = readback.zZeroToOne ? d : d * 2.0F - 1.0F;
					readback.inverseProjection.transform(point.set(ndcX, ndcY, ndcZ, 1.0F));
					x = point.x / point.w;
					y = point.y / point.w;
					z = -point.z / point.w; // Minecraft looks down -z, Unity down +z
					far = z <= 0.0F || x * x + y * y + z * z > maxDistance * maxDistance;
				} else {
					x = y = z = 0.0F;
				}
				if (far && d <= 1.0E-7F) {
					// real sky: remember its colour, by height above the horizon, for the backdrop
					readback.rotation.transform(direction.set(ndcX * readback.tanH, ndcY * readback.tanV, -1.0F).normalize());
					float[] bucket = direction.y > 0.35F ? top : direction.y > -0.1F && direction.y < 0.2F ? horizon : null;
					if (bucket != null) {
						int c = color.getInt(((py / step) * colorWidth + px / step) * 4);
						bucket[0] += (c & 0xFF) / 255.0F;
						bucket[1] += (c >> 8 & 0xFF) / 255.0F;
						bucket[2] += (c >> 16 & 0xFF) / 255.0F;
						bucket[3]++;
					}
				}
				if (far) {
					x = ndcX * readback.tanH;
					y = ndcY * readback.tanV;
					z = 1.0F;
					float scale = maxDistance / (float) Math.sqrt(x * x + y * y + 1.0F);
					x *= scale;
					y *= scale;
					z *= scale;
				}
				this.grid[i++] = x;
				this.grid[i++] = y;
				this.grid[i++] = z;
			}
		}
		updateSky(this.skyTop, top, horizon);
		updateSky(this.skyHorizon, horizon, top);
	}

	/** The sky colours keep their last value while no sky is in view (in a cave). */
	private static void updateSky(float[] target, float[] samples, float[] fallback) {
		float[] source = samples[3] >= 20.0F ? samples : fallback[3] >= 20.0F ? fallback : null;
		if (source != null) {
			for (int c = 0; c < 3; c++) {
				target[c] = source[c] / source[3];
			}
		}
	}

	/** Called at the end of every frame: every few frames, the whole window (HUD and menus too) for the wrist screen. */
	public void capturePanel(RenderTarget target, boolean menuOpen) {
		if (++this.panelFrame < (menuOpen ? PANEL_EVERY_FRAMES_IN_MENU : PANEL_EVERY_FRAMES) || this.memory == null || target.getColorTexture() == null) {
			return;
		}
		PanelReadback panel = null;
		for (PanelReadback candidate : this.panels) {
			if (!candidate.busy) {
				panel = candidate;
				break;
			}
		}
		if (panel == null) {
			return;
		}
		this.panelFrame = 0;
		int width = target.width;
		int height = target.height;
		if (panel.color == null || panel.width != width || panel.height != height) {
			if (panel.color != null) {
				panel.color.close();
			}
			panel.color = RenderSystem.getDevice().createBuffer(() -> "Gorilla Passthrough wrist screen",
				GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) width * height * 4);
			panel.width = width;
			panel.height = height;
		}
		panel.busy = true;
		PanelReadback finished = panel;
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(target.getColorTexture(), panel.color, 0L, () -> {
			try {
				this.publishPanel(finished);
			} catch (RuntimeException e) {
				LOGGER.warn("Gorilla Passthrough: couldn't share the wrist screen", e);
			} finally {
				finished.busy = false;
			}
		}, 0);
	}

	private void publishPanel(PanelReadback panel) {
		SharedMemory memory = this.memory;
		int step = Math.max(1, Math.max(ceilDiv(panel.width, PANEL_MAX_WIDTH), ceilDiv(panel.height, PANEL_MAX_HEIGHT)));
		int outWidth = panel.width / step;
		int outHeight = panel.height / step;
		long base = PANEL_OFFSET;
		long seq = memory.getLongVolatile(base + P_SEQ);
		memory.setLongVolatile(base + P_SEQ, (seq | 1L) + 2L);
		MemorySegment segment = memory.segment;
		try (GpuBufferSlice.MappedView view = panel.color.map(true, false)) {
			ByteBuffer color = view.data().order(ByteOrder.LITTLE_ENDIAN);
			long out = base + P_DATA;
			for (int y = 0; y < outHeight; y++) {
				int row = y * step * panel.width;
				for (int x = 0; x < outWidth; x++) {
					// the HUD can leave alpha below 1; the wrist screen is opaque
					segment.set(ValueLayout.JAVA_INT_UNALIGNED, out, color.getInt((row + x * step) * 4) | 0xFF000000);
					out += 4;
				}
			}
		}
		segment.set(ValueLayout.JAVA_INT_UNALIGNED, base + P_WIDTH, outWidth);
		segment.set(ValueLayout.JAVA_INT_UNALIGNED, base + P_HEIGHT, outHeight);
		memory.setLongVolatile(base + P_SEQ, (seq | 1L) + 3L);
	}

	private static int ceilDiv(int a, int b) {
		return (a + b - 1) / b;
	}
}
