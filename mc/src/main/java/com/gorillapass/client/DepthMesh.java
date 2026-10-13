package com.gorillapass.client;

import static com.gorillapass.client.Protocol.*;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Turns the depth grid into the finished mesh Gorilla Tag draws, straight into shared memory: Gorilla Tag only has to
 * hand it to the graphics card. (Building it in Java is much quicker than in Gorilla Tag, which also has VR to draw.)
 * <p>
 * One grid cell is two triangles. A cell on a depth edge (a block corner in front of the far landscape, a leaf in front
 * of the sky) is drawn twice, each corner moved along its own line of sight so the picture from the head stays
 * exactly the same:
 * <ul>
 * <li>near: on the near thing, sharing its near corners with the near neighbours. Its pixels are marked in the
 * picture's alpha channel as near (255) or far (0) and Gorilla Tag only shows the near ones, so no strip of background
 * sticks to the near thing.</li>
 * <li>far: on the far surface, sharing its far corners with the far neighbours. Gorilla Tag shows the picture's far
 * pixels on it, over the background picture (see {@link #background}).</li>
 * </ul>
 * Your eyes are a few centimetres either side of where Minecraft drew from (and the frame is a little late), so they
 * see a little behind near things: background Minecraft never drew. The far surface is therefore carried on for a few
 * cells in behind near things (the "skirt"), showing the background picture: the background filled in from around.
 * Every point moved off the grid is moved once for all the cells around it, so the moved copies join up seamlessly;
 * otherwise cracks open between neighbouring cells as soon as you look from beside where Minecraft drew from, and the
 * sky shows through them as specks and lines along every edge.
 */
final class DepthMesh {
	static final float EDGE_RATIO = 1.25F;
	static final float EDGE_METERS = 0.15F;
	/** How many cells the background carries on behind a near thing (each cell is about a third of a degree). */
	private static final int SKIRT_CELLS = 8;
	/**
	 * How far into near things the background picture doesn't use their colour, filling in from what is behind them
	 * instead (wider than the skirt, or the near thing's own colour would be the nearest thing to fill from).
	 */
	private static final int FILL_CELLS = 24;
	private static final int WHITE = 0xFFFFFFFF;

	private float[] distances = new float[0];
	private float[] cellNear = new float[0];
	private float[] cellFar = new float[0];
	private float[] vertexBehind = new float[0];
	private float[] vertexNear = new float[0];
	private boolean[] farCorner = new boolean[0];
	private int[] backCopies = new int[0];
	private int[] frontCopies = new int[0];
	private float[] grid = new float[0];
	private boolean[] edge = new boolean[0];
	private int[] skirtStep = new int[0];
	private float[] skirtBehind = new float[0];
	private int[] queue = new int[0];
	private int[] opaque = new int[0];
	private int[] back = new int[0];
	private int[] front = new int[0];
	private int[] edgeCells = new int[0];
	private float[] edgeCuts = new float[0];
	private int edgeCount;
	private int gridW;
	private int gridH;
	private long vertexBase;
	private MemorySegment segment;
	private int next;

	int vertexCount;
	int opaqueCount;
	int backCount;
	int frontCount;

	void build(MemorySegment segment, long slot, float[] grid, int gridW, int gridH) {
		this.segment = segment;
		this.gridW = gridW;
		this.gridH = gridH;
		this.vertexBase = slot + S_VERTEX_DATA;
		this.grid = grid;
		int count = gridW * gridH;
		int cellsW = gridW - 1;
		int cells = cellsW * (gridH - 1);
		if (this.distances.length < count) {
			this.distances = new float[count];
			this.vertexBehind = new float[count];
			this.vertexNear = new float[count];
			this.farCorner = new boolean[count];
			this.backCopies = new int[count];
			this.frontCopies = new int[count];
		}
		if (this.opaque.length < cells * 6) {
			this.opaque = new int[cells * 6];
			this.back = new int[cells * 6];
			this.front = new int[cells * 6];
			this.edgeCells = new int[cells];
			this.edgeCuts = new float[cells];
			this.cellNear = new float[cells];
			this.cellFar = new float[cells];
			this.edge = new boolean[cells];
			this.skirtStep = new int[cells];
			this.skirtBehind = new float[cells];
			this.queue = new int[cells];
		}
		java.util.Arrays.fill(this.edge, 0, cells, false);
		java.util.Arrays.fill(this.skirtStep, 0, cells, 0);
		java.util.Arrays.fill(this.vertexBehind, 0, count, 0.0F);
		java.util.Arrays.fill(this.vertexNear, 0, count, Float.MAX_VALUE);
		java.util.Arrays.fill(this.farCorner, 0, count, false);
		java.util.Arrays.fill(this.backCopies, 0, count, -1);
		java.util.Arrays.fill(this.frontCopies, 0, count, -1);

		for (int i = 0; i < count; i++) {
			float x = grid[i * 3];
			float y = grid[i * 3 + 1];
			float z = grid[i * 3 + 2];
			this.distances[i] = (float) Math.sqrt(x * x + y * y + z * z);
			this.vertex(i, x, y, z, i);
		}
		this.next = count;
		int o = 0;
		int queued = 0;
		this.edgeCount = 0;
		float[] d = this.distances;

		// which cells are on an edge, and how near and far each side is
		for (int gy = 0; gy < gridH - 1; gy++) {
			for (int gx = 0; gx < cellsW; gx++) {
				int cell = gy * cellsW + gx;
				int a = gy * gridW + gx;
				int b = a + 1;
				int c = a + gridW;
				int e = c + 1;
				float near = Math.min(Math.min(d[a], d[b]), Math.min(d[c], d[e]));
				float far = Math.max(Math.max(d[a], d[b]), Math.max(d[c], d[e]));
				this.cellFar[cell] = far;
				if (far > near * EDGE_RATIO && far - near > EDGE_METERS && near > 0.0F) {
					float behind = far;
					for (int corner : new int[]{a, b, c, e}) {
						if (isFarSide(d[corner], near) && d[corner] <= behind) {
							behind = d[corner];
						}
					}
					for (int corner : new int[]{a, b, c, e}) {
						if (isFarSide(d[corner], near)) {
							this.farCorner[corner] = true;
							this.vertexNear[corner] = Math.min(this.vertexNear[corner], near);
						} else {
							this.vertexBehind[corner] = Math.max(this.vertexBehind[corner], behind);
						}
					}
					this.edge[cell] = true;
					this.cellNear[cell] = near;
					this.skirtBehind[cell] = behind;
					this.queue[queued++] = cell;
					this.edgeCells[this.edgeCount] = cell;
					this.edgeCuts[this.edgeCount] = (float) Math.sqrt(near * behind);
					this.edgeCount++;
				} else {
					o = quad(this.opaque, o, a, b, c, e);
				}
			}
		}

		// the skirt: from every edge, the background carries on in behind the near thing for a few cells
		for (int head = 0; head < queued; head++) {
			int cell = this.queue[head];
			int step = this.edge[cell] ? 0 : this.skirtStep[cell];
			if (step >= FILL_CELLS) {
				continue;
			}
			int gx = cell % cellsW;
			int gy = cell / cellsW;
			float behind = this.skirtBehind[cell];
			for (int k = 0; k < 4; k++) {
				int nx = gx + (k == 0 ? -1 : k == 1 ? 1 : 0);
				int ny = gy + (k == 2 ? -1 : k == 3 ? 1 : 0);
				if (nx < 0 || ny < 0 || nx >= cellsW || ny >= gridH - 1) {
					continue;
				}
				int neighbour = ny * cellsW + nx;
				// only in behind things clearly nearer than this background, and only once
				if (this.edge[neighbour] || this.skirtStep[neighbour] != 0 || this.cellFar[neighbour] * EDGE_RATIO > behind) {
					continue;
				}
				this.skirtStep[neighbour] = step + 1;
				this.skirtBehind[neighbour] = behind;
				this.queue[queued++] = neighbour;
				if (step + 1 > SKIRT_CELLS) {
					continue; // only left out of the background picture
				}
				int a = ny * gridW + nx;
				for (int corner : new int[]{a, a + 1, a + gridW, a + gridW + 1}) {
					this.vertexBehind[corner] = Math.max(this.vertexBehind[corner], behind);
				}
			}
		}

		// the far and near copies. Every point moved off the grid is moved once, for all the cells around it, so the
		// copies join up: no cracks open between neighbouring cells when you look from beside where Minecraft drew from
		int bk = 0;
		int fr = 0;
		for (int i = 0; i < queued; i++) {
			int cell = this.queue[i];
			if (!this.edge[cell] && this.skirtStep[cell] > SKIRT_CELLS) {
				continue;
			}
			int a = (cell / cellsW) * gridW + cell % cellsW;
			int[] corners = {a, a + 1, a + gridW, a + gridW + 1};
			int[] far = new int[4];
			if (this.edge[cell]) {
				float near = this.cellNear[cell];
				int[] close = new int[4];
				for (int k = 0; k < 4; k++) {
					boolean farSide = isFarSide(d[corners[k]], near);
					far[k] = farSide ? corners[k] : this.backCopy(corners[k]);
					close[k] = farSide ? this.frontCopy(corners[k]) : corners[k];
				}
				fr = quad(this.front, fr, close[0], close[1], close[2], close[3]);
			} else {
				for (int k = 0; k < 4; k++) {
					far[k] = this.backCopy(corners[k]);
				}
			}
			bk = quad(this.back, bk, far[0], far[1], far[2], far[3]);
		}

		this.vertexCount = this.next;
		this.opaqueCount = o;
		this.backCount = bk;
		this.frontCount = fr;
		long indices = slot + S_INDEX_DATA;
		MemorySegment.copy(this.opaque, 0, segment, ValueLayout.JAVA_INT_UNALIGNED, indices, o);
		MemorySegment.copy(this.back, 0, segment, ValueLayout.JAVA_INT_UNALIGNED, indices + o * 4L, bk);
		MemorySegment.copy(this.front, 0, segment, ValueLayout.JAVA_INT_UNALIGNED, indices + (o + bk) * 4L, fr);
	}

	/** The point's copy on the far surface behind it (one for all the cells around it). */
	private int backCopy(int corner) {
		if (this.farCorner[corner]) {
			return corner; // it is on the far surface itself
		}
		int copy = this.backCopies[corner];
		if (copy < 0) {
			float distance = this.distances[corner];
			float behind = this.vertexBehind[corner];
			copy = this.copy(corner, distance > 0.0F && behind > 0.0F ? behind / distance : 1.0F, this.grid, corner);
			this.backCopies[corner] = copy;
		}
		return copy;
	}

	/** The far point's copy pulled forward onto the near thing (one for all the edge cells around it). */
	private int frontCopy(int corner) {
		int copy = this.frontCopies[corner];
		if (copy < 0) {
			float distance = this.distances[corner];
			float near = this.vertexNear[corner];
			copy = this.copy(corner, distance > 0.0F && near < Float.MAX_VALUE ? near / distance : 1.0F, this.grid, corner);
			this.frontCopies[corner] = copy;
		}
		return copy;
	}

	/**
	 * Marks the pixels of every edge cell as near (alpha 255) or far (alpha 0), from the full-size depth buffer.
	 * The colour must already be in shared memory at {@code color}, {@code outWidth} x {@code outHeight}, bottom row first.
	 */
	void classify(long color, ByteBuffer depth, int width, int height, int step, int outWidth, int outHeight, Matrix4f inverseProjection,
		boolean zZeroToOne) {
		int cellsW = this.gridW - 1;
		// edge cells side by side on all cores (each writes only its own pixels)
		java.util.stream.IntStream.range(0, this.edgeCount).parallel().forEach(i -> {
			Vector4f point = new Vector4f();
			int cell = this.edgeCells[i];
			float cut = this.edgeCuts[i];
			int gx = cell % cellsW;
			int gy = cell / cellsW;
			int x0 = (int) Math.floor(gx / (float) cellsW * outWidth);
			int x1 = Math.min(outWidth - 1, (int) Math.ceil((gx + 1) / (float) cellsW * outWidth) - 1);
			int y0 = (int) Math.floor(gy / (float) (this.gridH - 1) * outHeight);
			int y1 = Math.min(outHeight - 1, (int) Math.ceil((gy + 1) / (float) (this.gridH - 1) * outHeight) - 1);
			for (int py = y0; py <= y1; py++) {
				float ndcY = (py + 0.5F) / outHeight * 2.0F - 1.0F;
				int sy = Math.min(height - 1, py * step);
				for (int px = x0; px <= x1; px++) {
					int sx = Math.min(width - 1, px * step);
					float z = depth.getFloat((sy * width + sx) * 4);
					boolean near = false;
					if (z > 1.0E-7F) {
						float ndcX = (px + 0.5F) / outWidth * 2.0F - 1.0F;
						inverseProjection.transform(point.set(ndcX, ndcY, zZeroToOne ? z : z * 2.0F - 1.0F, 1.0F));
						float vx = point.x / point.w;
						float vy = point.y / point.w;
						float vz = point.z / point.w;
						near = vx * vx + vy * vy + vz * vz < cut * cut;
					}
					this.segment.set(ValueLayout.JAVA_BYTE, color + ((long) py * outWidth + px) * 4 + 3, near ? (byte) 255 : (byte) 0);
				}
			}
		});
	}

	/**
	 * The near things the skirt carries the background in behind are near in the alpha too (Minecraft's own alpha isn't
	 * always 1, on water for one), so Gorilla Tag never shows them on the far surface.
	 */
	void markSkirtNear(long color, int outWidth, int outHeight) {
		int cellsW = this.gridW - 1;
		int cellsH = this.gridH - 1;
		MemorySegment s = this.segment;
		// rows of cells side by side on all cores (they only ever write 255)
		java.util.stream.IntStream.range(0, cellsH).parallel().forEach(gy -> {
			int y0 = (int) Math.floor(gy / (float) cellsH * outHeight);
			int y1 = Math.min(outHeight - 1, (int) Math.ceil((gy + 1) / (float) cellsH * outHeight) - 1);
			for (int gx = 0; gx < cellsW; gx++) {
				int cell = gy * cellsW + gx;
				if (this.edge[cell] || this.skirtStep[cell] == 0 || this.skirtStep[cell] > SKIRT_CELLS) {
					continue;
				}
				int x0 = (int) Math.floor(gx / (float) cellsW * outWidth);
				int x1 = Math.min(outWidth - 1, (int) Math.ceil((gx + 1) / (float) cellsW * outWidth) - 1);
				for (int py = y0; py <= y1; py++) {
					long row = color + (long) py * outWidth * 4 + 3;
					for (int px = x0; px <= x1; px++) {
						s.set(ValueLayout.JAVA_BYTE, row + px * 4L, (byte) 255);
					}
				}
			}
		});
	}

	private static boolean isFarSide(float distance, float near) {
		return distance > near * EDGE_RATIO && distance - near > EDGE_METERS;
	}



	// ---- the background picture ----

	private float[][] fillR = new float[0][];
	private float[][] fillG = new float[0][];
	private float[][] fillB = new float[0][];
	private float[][] fillQ = new float[0][];
	private float[][] fillW = new float[0][];
	private int[] fillWidth = new int[0];
	private int[] fillHeight = new int[0];

	/**
	 * The background picture: the colour picture at a quarter of its size, where everything near (the near side of
	 * edges, and the near things the skirt carries the background in behind) is filled in from what is around it
	 * (push-pull: averaged down to ever smaller pictures until every gap is covered, then filled back up), the farther
	 * the more. What your eyes see past an edge is then the background carrying on, softly, instead of smeared stripes
	 * or the near thing again. Call after {@link #classify}, whose alpha marks the near pixels of edge cells.
	 */
	void background(long color, int outWidth, int outHeight, long out, int bgWidth, int bgHeight) {
		this.ensurePyramid(bgWidth, bgHeight);
		MemorySegment s = this.segment;
		int cellsW = this.gridW - 1;
		int cellsH = this.gridH - 1;
		int div = Protocol.BG_DIVISOR;
		// what is known: colour (times its weight), the weight (coverage x distance^power), and the coverage alone
		float[] r0 = this.fillR[0];
		float[] g0 = this.fillG[0];
		float[] b0 = this.fillB[0];
		float[] q0 = this.fillQ[0];
		float[] w0 = this.fillW[0];
		// rows side by side on all cores
		java.util.stream.IntStream.range(0, bgHeight).parallel().forEach(by -> {
			int py0 = by * div;
			int gy = Math.min(cellsH - 1, (int) ((py0 + div * 0.5F) / outHeight * cellsH));
			for (int bx = 0; bx < bgWidth; bx++) {
				int px0 = bx * div;
				int gx = Math.min(cellsW - 1, (int) ((px0 + div * 0.5F) / outWidth * cellsW));
				int cell = gy * cellsW + gx;
				int i = by * bgWidth + bx;
				float r = 0.0F;
				float g = 0.0F;
				float b = 0.0F;
				float w = 0.0F;
				float distance = 0.0F;
				if (this.edge[cell]) {
					// only its far pixels
					for (int y = py0; y < Math.min(outHeight, py0 + div); y++) {
						long row = color + (long) y * outWidth * 4;
						for (int x = px0; x < Math.min(outWidth, px0 + div); x++) {
							int pixel = s.get(ValueLayout.JAVA_INT_UNALIGNED, row + x * 4L); // RGBA bytes: R lowest
							if (pixel >>> 24 == 0) {
								r += pixel & 0xFF;
								g += pixel >>> 8 & 0xFF;
								b += pixel >>> 16 & 0xFF;
								w++;
							}
						}
					}
					if (w > 0.0F) {
						r /= w;
						g /= w;
						b /= w;
						w /= div * div;
					}
					distance = this.skirtBehind[cell];
				} else if (this.skirtStep[cell] == 0) {
					// plain: the picture as it is
					long at = color + ((long) Math.min(outHeight - 1, py0 + div / 2) * outWidth + Math.min(outWidth - 1, px0 + div / 2)) * 4;
					int pixel = s.get(ValueLayout.JAVA_INT_UNALIGNED, at);
					r = pixel & 0xFF;
					g = pixel >>> 8 & 0xFF;
					b = pixel >>> 16 & 0xFF;
					w = 1.0F;
					distance = this.cellFar[cell];
				}
				// (a near thing with background carried in behind it: nothing of its own, all filled in)
				float dc = Math.clamp(distance, 0.1F, 64.0F);
				float q = w * dc * dc * dc * dc; // distance^4
				r0[i] = r * q;
				g0[i] = g * q;
				b0[i] = b * q;
				q0[i] = q;
				w0[i] = w;
			}
		});

		// push: ever smaller pictures, summing what is known
		int levels = this.fillWidth.length;
		for (int l = 1; l < levels; l++) {
			int w = this.fillWidth[l];
			int h = this.fillHeight[l];
			int fw = this.fillWidth[l - 1];
			int fh = this.fillHeight[l - 1];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					float r = 0.0F;
					float g = 0.0F;
					float b = 0.0F;
					float q = 0.0F;
					float cover = 0.0F;
					for (int dy = 0; dy < 2; dy++) {
						int fy = Math.min(fh - 1, y * 2 + dy);
						for (int dx = 0; dx < 2; dx++) {
							int f = fy * fw + Math.min(fw - 1, x * 2 + dx);
							r += this.fillR[l - 1][f];
							g += this.fillG[l - 1][f];
							b += this.fillB[l - 1][f];
							q += this.fillQ[l - 1][f];
							cover += this.fillW[l - 1][f];
						}
					}
					int i = y * w + x;
					this.fillR[l][i] = r;
					this.fillG[l][i] = g;
					this.fillB[l][i] = b;
					this.fillQ[l][i] = q;
					this.fillW[l][i] = Math.min(1.0F, cover);
				}
			}
		}
		// pull: from the smallest picture down, each pixel is its own colour as far as it is known, the rest filled
		// smoothly (bilinear) from the finished smaller picture. The R/G/B arrays then hold finished colours.
		int top = levels - 1;
		for (int i = 0; i < this.fillWidth[top] * this.fillHeight[top]; i++) {
			float q = this.fillQ[top][i];
			this.fillR[top][i] = q > 0.0F ? this.fillR[top][i] / q : 128.0F;
			this.fillG[top][i] = q > 0.0F ? this.fillG[top][i] / q : 128.0F;
			this.fillB[top][i] = q > 0.0F ? this.fillB[top][i] / q : 128.0F;
		}
		for (int l = top - 1; l >= 0; l--) {
			int w = this.fillWidth[l];
			int h = this.fillHeight[l];
			int cw = this.fillWidth[l + 1];
			int ch = this.fillHeight[l + 1];
			float[] cr = this.fillR[l + 1];
			float[] cg = this.fillG[l + 1];
			float[] cb = this.fillB[l + 1];
			float[] fr = this.fillR[l];
			float[] fg = this.fillG[l];
			float[] fb = this.fillB[l];
			float[] fw = this.fillW[l];
			float[] fq = this.fillQ[l];
			// rows side by side on all cores (the big pictures; the small ones aren't worth it)
			java.util.stream.IntStream rows = java.util.stream.IntStream.range(0, h);
			(w * h > 20_000 ? rows.parallel() : rows).forEach(y -> {
				float sy = Math.clamp((y + 0.5F) * 0.5F - 0.5F, 0.0F, ch - 1);
				int y0 = (int) sy;
				int y1 = Math.min(ch - 1, y0 + 1);
				float ty = sy - y0;
				for (int x = 0; x < w; x++) {
					int i = y * w + x;
					float own = fw[i];
					float q = fq[i];
					float r = q > 0.0F ? fr[i] / q : 0.0F;
					float g = q > 0.0F ? fg[i] / q : 0.0F;
					float b = q > 0.0F ? fb[i] / q : 0.0F;
					if (own < 1.0F) {
						float sx = Math.clamp((x + 0.5F) * 0.5F - 0.5F, 0.0F, cw - 1);
						int x0 = (int) sx;
						int x1 = Math.min(cw - 1, x0 + 1);
						float tx = sx - x0;
						int a00 = y0 * cw + x0;
						int a01 = y0 * cw + x1;
						int a10 = y1 * cw + x0;
						int a11 = y1 * cw + x1;
						float f00 = (1 - tx) * (1 - ty);
						float f01 = tx * (1 - ty);
						float f10 = (1 - tx) * ty;
						float f11 = tx * ty;
						float rest = 1.0F - own;
						r = r * own + rest * (cr[a00] * f00 + cr[a01] * f01 + cr[a10] * f10 + cr[a11] * f11);
						g = g * own + rest * (cg[a00] * f00 + cg[a01] * f01 + cg[a10] * f10 + cg[a11] * f11);
						b = b * own + rest * (cb[a00] * f00 + cb[a01] * f01 + cb[a10] * f10 + cb[a11] * f11);
					}
					fr[i] = r;
					fg[i] = g;
					fb[i] = b;
				}
			});
		}
		java.util.stream.IntStream.range(0, bgWidth * bgHeight).parallel().forEach(i -> {
			int r = Math.clamp(Math.round(r0[i]), 0, 255);
			int g = Math.clamp(Math.round(g0[i]), 0, 255);
			int b = Math.clamp(Math.round(b0[i]), 0, 255);
			s.set(ValueLayout.JAVA_INT_UNALIGNED, out + (long) i * 4, 0xFF000000 | b << 16 | g << 8 | r);
		});
	}

	private void ensurePyramid(int width, int height) {
		if (this.fillWidth.length > 0 && this.fillWidth[0] == width && this.fillHeight[0] == height) {
			return;
		}
		int levels = 1;
		for (int w = width, h = height; w > 1 || h > 1; w = (w + 1) / 2, h = (h + 1) / 2) {
			levels++;
		}
		this.fillR = new float[levels][];
		this.fillG = new float[levels][];
		this.fillB = new float[levels][];
		this.fillQ = new float[levels][];
		this.fillW = new float[levels][];
		this.fillWidth = new int[levels];
		this.fillHeight = new int[levels];
		for (int l = 0, w = width, h = height; l < levels; l++, w = (w + 1) / 2, h = (h + 1) / 2) {
			this.fillWidth[l] = w;
			this.fillHeight[l] = h;
			this.fillR[l] = new float[w * h];
			this.fillG[l] = new float[w * h];
			this.fillB[l] = new float[w * h];
			this.fillQ[l] = new float[w * h];
			this.fillW[l] = new float[w * h];
		}
	}

	private int copy(int corner, float scale, float[] grid, int colourFrom) {
		int index = this.next++;
		this.vertex(index, grid[corner * 3] * scale, grid[corner * 3 + 1] * scale, grid[corner * 3 + 2] * scale, colourFrom);
		return index;
	}

	private void vertex(int index, float x, float y, float z, int colourFrom) {
		long at = this.vertexBase + (long) index * VERTEX_BYTES;
		MemorySegment s = this.segment;
		s.set(ValueLayout.JAVA_FLOAT_UNALIGNED, at, x);
		s.set(ValueLayout.JAVA_FLOAT_UNALIGNED, at + 4, y);
		s.set(ValueLayout.JAVA_FLOAT_UNALIGNED, at + 8, z);
		s.set(ValueLayout.JAVA_INT_UNALIGNED, at + 12, WHITE);
		s.set(ValueLayout.JAVA_FLOAT_UNALIGNED, at + 16, (colourFrom % this.gridW) / (float) (this.gridW - 1));
		s.set(ValueLayout.JAVA_FLOAT_UNALIGNED, at + 20, (colourFrom / this.gridW) / (float) (this.gridH - 1));
	}

	/** Two triangles, clockwise as seen from the camera (Unity's front faces). */
	private static int quad(int[] list, int at, int a, int b, int c, int e) {
		list[at] = a;
		list[at + 1] = c;
		list[at + 2] = b;
		list[at + 3] = b;
		list[at + 4] = c;
		list[at + 5] = e;
		return at + 6;
	}
}
