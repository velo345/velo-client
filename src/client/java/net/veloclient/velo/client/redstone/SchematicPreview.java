package net.veloclient.velo.client.redstone;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A preview picture of a schematic for sharing: an isometric view from above the south-east corner,
 * every visible block drawn in its map colour with lit top and shaded sides. Big builds are sampled
 * down so it stays quick. Works without a world (then colours are guessed from the block id).
 */
public final class SchematicPreview {

	private static final int WIDTH = 512;
	private static final int HEIGHT = 384;
	private static final int MAX_CELLS = 160;

	private SchematicPreview() {
	}

	/** Loads a .veloschem / .litematic and renders it. Background thread. */
	public static byte[] render(Path file) throws IOException {
		String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
		Clip clip = name.endsWith(".litematic") ? SchematicIO.loadLitematic(file) : SchematicIO.loadVelo(file);
		return render(clip);
	}

	public static byte[] render(Clip clip) throws IOException {
		int step = Math.max(1, (int) Math.ceil(Math.max(clip.sx, Math.max(clip.sy, clip.sz)) / (double) MAX_CELLS));
		int nx = (clip.sx + step - 1) / step;
		int ny = (clip.sy + step - 1) / step;
		int nz = (clip.sz + step - 1) / step;
		Object[] cells = new Object[nx * ny * nz];
		for (int x = 0; x < nx; x++) {
			for (int y = 0; y < ny; y++) {
				for (int z = 0; z < nz; z++) {
					cells[(x * ny + y) * nz + z] = sample(clip, x * step, y * step, z * step, step);
				}
			}
		}
		// Isometric: screen x = (x - z), screen y = (x + z) / 2 - y; fit the whole box.
		double spanX = nx + nz;
		double spanY = (nx + nz) / 2.0 + ny;
		double c = Math.min((WIDTH - 40) / spanX, (HEIGHT - 40) / spanY);
		double ox = WIDTH / 2.0 - (nx - nz) * c / 2;
		double oy = (HEIGHT - spanY * c) / 2 + ny * c;

		BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		g.setPaint(new java.awt.GradientPaint(0, 0, new Color(0x2A2B38), 0, HEIGHT, new Color(0x16161E)));
		g.fillRect(0, 0, WIDTH, HEIGHT);
		Map<Object, Integer> colors = new HashMap<>();
		Object level = RmWorld.clientLevel();
		// Far to near: ascending x + z, then y.
		List<int[]> order = new ArrayList<>();
		for (int x = 0; x < nx; x++) {
			for (int y = 0; y < ny; y++) {
				for (int z = 0; z < nz; z++) {
					Object state = cells[(x * ny + y) * nz + z];
					if (state != null && visible(cells, nx, ny, nz, x, y, z)) {
						order.add(new int[] {x, y, z});
					}
				}
			}
		}
		order.sort((a, b) -> a[0] + a[2] != b[0] + b[2] ? Integer.compare(a[0] + a[2], b[0] + b[2]) : Integer.compare(a[1], b[1]));
		for (int[] v : order) {
			Object state = cells[(v[0] * ny + v[1]) * nz + v[2]];
			int rgb = colors.computeIfAbsent(state, s -> color(level, s));
			double sx = ox + (v[0] - v[2]) * c;
			double sy = oy + (v[0] + v[2]) * c / 2 - v[1] * c;
			// Top, left (+z side) and right (+x side) faces of the cube whose top-back corner is (sx, sy).
			face(g, rgb, 1.0, sx, sy, sx + c, sy + c / 2, sx, sy + c, sx - c, sy + c / 2);
			face(g, rgb, 0.78, sx - c, sy + c / 2, sx, sy + c, sx, sy + 2 * c, sx - c, sy + 1.5 * c);
			face(g, rgb, 0.62, sx, sy + c, sx + c, sy + c / 2, sx + c, sy + 1.5 * c, sx, sy + 2 * c);
		}
		g.dispose();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	/** The first non-air block in a step³ cell (so thin redstone lines don't vanish when sampled down). */
	private static Object sample(Clip clip, int x0, int y0, int z0, int step) {
		for (int y = Math.min(clip.sy - 1, y0 + step - 1); y >= y0; y--) {
			for (int x = x0; x < Math.min(clip.sx, x0 + step); x++) {
				for (int z = z0; z < Math.min(clip.sz, z0 + step); z++) {
					Object state = clip.states[clip.index(x, y, z)];
					if (state != null && !RmWorld.isAir(state)) {
						return state;
					}
				}
			}
		}
		return null;
	}

	/** Skips cells hidden behind others on all three seen sides (top, +x, +z). */
	private static boolean visible(Object[] cells, int nx, int ny, int nz, int x, int y, int z) {
		return x == nx - 1 || y == ny - 1 || z == nz - 1 || cells[((x + 1) * ny + y) * nz + z] == null
				|| cells[(x * ny + y + 1) * nz + z] == null || cells[(x * ny + y) * nz + z + 1] == null;
	}

	private static void face(Graphics2D g, int rgb, double shade, double... xy) {
		int r = (int) Math.min(255, ((rgb >> 16) & 0xFF) * shade);
		int gr = (int) Math.min(255, ((rgb >> 8) & 0xFF) * shade);
		int b = (int) Math.min(255, (rgb & 0xFF) * shade);
		g.setColor(new Color(r, gr, b));
		Polygon p = new Polygon();
		for (int i = 0; i < xy.length; i += 2) {
			p.addPoint((int) Math.round(xy[i]), (int) Math.round(xy[i + 1]));
		}
		g.fillPolygon(p);
		g.drawPolygon(p);
	}

	private static int color(Object level, Object state) {
		String id = RmWorld.id(state);
		int guess = guess(id);
		if (guess >= 0) {
			return guess;
		}
		if (level != null) {
			int rgb = RmWorld.mapColor(level, state, 0, 64, 0);
			if (rgb != 0) {
				return rgb;
			}
		}
		int h = id.hashCode();
		return 0x606060 | (h & 0x3F3F3F);
	}

	/** Recognisable colours for redstone parts (their map colours are mostly plain stone grey). */
	private static int guess(String id) {
		if (id.contains("redstone_wire") || id.contains("redstone_block") || id.contains("redstone_torch")) {
			return 0xC81E1E;
		}
		if (id.contains("repeater") || id.contains("comparator")) {
			return 0xB0A090;
		}
		if (id.contains("observer")) {
			return 0x7A7A82;
		}
		if (id.contains("sticky_piston")) {
			return 0x8FB060;
		}
		if (id.contains("piston")) {
			return 0xB59B6A;
		}
		if (id.contains("hopper")) {
			return 0x4A4A50;
		}
		if (id.contains("slime_block")) {
			return 0x7CC46A;
		}
		if (id.contains("honey_block")) {
			return 0xE8A23A;
		}
		if (id.equals("minecraft:water") || id.contains("bubble_column")) {
			return 0x3A6FE0;
		}
		return -1;
	}
}
