package net.veloclient.velo.client.gui.packs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * One texture being edited: its frames (a still texture is one frame; an animated one is a vertical strip
 * with a {@code .png.mcmeta} "animation" section), animation timing, the selection and undo history.
 * Plain Java - loads and saves PNGs through ImageIO.
 */
public final class TexDoc {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final long UNDO_BUDGET_BYTES = 48L << 20;

	public int width;
	public int height;
	public final List<int[]> frames = new ArrayList<>();
	/** Default frame time in game ticks. */
	public int frametime = 2;
	public boolean interpolate;
	/** Per-frame time overrides (0 = default), parallel to {@link #frames}. */
	public final List<Integer> frameTimes = new ArrayList<>();
	/** A custom play order from the mcmeta ({index...}); null = frames in order. */
	public List<Integer> order;
	/** Everything else in the .mcmeta (texture blur/clamp, villager hats, gui scaling...) - kept as it was. */
	private JsonObject meta = new JsonObject();
	public boolean[] selection;
	public int current;
	private boolean animatedOnDisk;

	private final Deque<Snapshot> undo = new ArrayDeque<>();
	private final Deque<Snapshot> redo = new ArrayDeque<>();
	public boolean dirty;
	/** Bumped on every change - previews re-render when it moves. */
	public int revision;

	private record Snapshot(int width, int height, List<int[]> frames, List<Integer> times, boolean[] selection, int current, String label) {
		long bytes() {
			return (long) width * height * 4 * frames.size();
		}
	}

	public static TexDoc blank(int w, int h) {
		TexDoc doc = new TexDoc();
		doc.width = w;
		doc.height = h;
		doc.frames.add(new int[w * h]);
		doc.frameTimes.add(0);
		return doc;
	}

	/** Reads a PNG (+ its .mcmeta if {@code meta} isn't null). */
	public static TexDoc read(byte[] png, byte[] meta) throws IOException {
		BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
		if (image == null) {
			throw new IOException("Not a PNG image");
		}
		int w = image.getWidth();
		int h = image.getHeight();
		int[] all = image.getRGB(0, 0, w, h, null, 0, w);
		TexDoc doc = new TexDoc();
		doc.width = w;
		JsonObject animation = null;
		if (meta != null) {
			try {
				doc.meta = JsonParser.parseString(new String(meta, StandardCharsets.UTF_8)).getAsJsonObject();
				if (doc.meta.has("animation") && doc.meta.get("animation").isJsonObject()) {
					animation = doc.meta.getAsJsonObject("animation");
				}
			} catch (RuntimeException ignored) {
				doc.meta = new JsonObject();
			}
		}
		int frameW = w;
		int frameH = h;
		if (animation != null) {
			doc.animatedOnDisk = true;
			if (animation.has("width") || animation.has("height")) {
				frameW = animation.has("width") ? animation.get("width").getAsInt() : w;
				frameH = animation.has("height") ? animation.get("height").getAsInt() : h;
			} else {
				frameW = Math.min(w, h);
				frameH = frameW;
			}
			doc.frametime = animation.has("frametime") ? Math.max(1, animation.get("frametime").getAsInt()) : 1;
			doc.interpolate = animation.has("interpolate") && animation.get("interpolate").getAsBoolean();
		}
		// Frames are read across then down (the game allows grids; nearly every pack uses a vertical strip).
		int columns = Math.max(1, w / Math.max(1, frameW));
		int rows = Math.max(1, h / Math.max(1, frameH));
		doc.width = frameW;
		doc.height = frameH;
		for (int row = 0; row < rows; row++) {
			for (int col = 0; col < columns; col++) {
				int[] frame = new int[frameW * frameH];
				for (int y = 0; y < frameH; y++) {
					System.arraycopy(all, (row * frameH + y) * w + col * frameW, frame, y * frameW, frameW);
				}
				doc.frames.add(frame);
				doc.frameTimes.add(0);
			}
		}
		if (animation != null && animation.has("frames") && animation.get("frames").isJsonArray()) {
			List<Integer> order = new ArrayList<>();
			List<Integer> times = new ArrayList<>();
			for (JsonElement element : animation.getAsJsonArray("frames")) {
				if (element.isJsonObject()) {
					JsonObject f = element.getAsJsonObject();
					order.add(f.get("index").getAsInt());
					times.add(f.has("time") ? f.get("time").getAsInt() : 0);
				} else {
					order.add(element.getAsInt());
					times.add(0);
				}
			}
			boolean linear = order.size() == doc.frames.size();
			for (int i = 0; linear && i < order.size(); i++) {
				linear = order.get(i) == i;
			}
			if (linear) {
				for (int i = 0; i < times.size(); i++) {
					doc.frameTimes.set(i, times.get(i));
				}
			} else {
				doc.order = order;
				doc.orderTimes = times;
			}
		}
		return doc;
	}

	/** Play-order frame times for a custom {@link #order}. */
	public List<Integer> orderTimes;

	public static TexDoc read(Path png) throws IOException {
		Path meta = png.resolveSibling(png.getFileName() + ".mcmeta");
		return read(Files.readAllBytes(png), Files.exists(meta) ? Files.readAllBytes(meta) : null);
	}

	public boolean animated() {
		return frames.size() > 1;
	}

	public int[] frame() {
		return frames.get(Math.max(0, Math.min(current, frames.size() - 1)));
	}

	/** Writes the PNG and its .mcmeta (written for animations or extra settings, removed when neither is left). */
	public void save(Path png) throws IOException {
		Files.createDirectories(png.getParent());
		BufferedImage image = new BufferedImage(width, height * frames.size(), BufferedImage.TYPE_INT_ARGB);
		for (int i = 0; i < frames.size(); i++) {
			image.setRGB(0, i * height, width, height, frames.get(i), 0, width);
		}
		Path tmp = png.resolveSibling(png.getFileName() + ".tmp");
		ImageIO.write(image, "png", tmp.toFile());
		Files.move(tmp, png, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		Path metaFile = png.resolveSibling(png.getFileName() + ".mcmeta");
		JsonObject out = meta.deepCopy();
		if (animated()) {
			JsonObject animation = out.has("animation") && out.get("animation").isJsonObject() ? out.getAsJsonObject("animation") : new JsonObject();
			animation.remove("width");
			animation.remove("height");
			if (width != height) {
				animation.addProperty("width", width);
				animation.addProperty("height", height);
			}
			animation.addProperty("frametime", frametime);
			if (interpolate) {
				animation.addProperty("interpolate", true);
			} else {
				animation.remove("interpolate");
			}
			animation.remove("frames");
			if (order != null) {
				JsonArray list = new JsonArray();
				for (int i = 0; i < order.size(); i++) {
					int t = orderTimes != null && i < orderTimes.size() ? orderTimes.get(i) : 0;
					list.add(frameRef(order.get(i), t));
				}
				animation.add("frames", list);
			} else if (frameTimes.stream().anyMatch(t -> t > 0)) {
				JsonArray list = new JsonArray();
				for (int i = 0; i < frames.size(); i++) {
					list.add(frameRef(i, frameTimes.get(i)));
				}
				animation.add("frames", list);
			}
			out.add("animation", animation);
		} else {
			out.remove("animation");
		}
		if (out.size() > 0) {
			Files.writeString(metaFile, GSON.toJson(out), StandardCharsets.UTF_8);
		} else if (animatedOnDisk || Files.exists(metaFile)) {
			Files.deleteIfExists(metaFile);
		}
		animatedOnDisk = animated();
		dirty = false;
	}

	/** Writes one frame as a plain PNG (no .mcmeta) - for exporting. */
	public void exportFrame(int index, Path png) throws IOException {
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, width, height, frames.get(index), 0, width);
		ImageIO.write(image, "png", png.toFile());
	}

	/** Exports everything: the PNG (all frames stacked) plus the .mcmeta next to it - without marking this saved. */
	public void exportAll(Path png) throws IOException {
		boolean wasDirty = dirty;
		boolean wasAnimated = animatedOnDisk;
		save(png);
		dirty = wasDirty;
		animatedOnDisk = wasAnimated;
	}

	private static JsonElement frameRef(int index, int time) {
		if (time <= 0) {
			return new com.google.gson.JsonPrimitive(index);
		}
		JsonObject f = new JsonObject();
		f.addProperty("index", index);
		f.addProperty("time", time);
		return f;
	}

	// ---- Playback ----

	/** Which frame (and how far towards the next, for interpolation) shows {@code ticks} into the animation. */
	public float[] frameAt(double ticks) {
		int count = order != null ? order.size() : frames.size();
		if (count <= 1) {
			return new float[] {0, 0, 0};
		}
		int total = 0;
		for (int i = 0; i < count; i++) {
			total += timeOf(i);
		}
		double t = ticks % Math.max(1, total);
		for (int i = 0; i < count; i++) {
			int time = timeOf(i);
			if (t < time) {
				int index = order != null ? order.get(i) : i;
				int next = order != null ? order.get((i + 1) % count) : (i + 1) % count;
				return new float[] {clampFrame(index), clampFrame(next), (float) (t / time)};
			}
			t -= time;
		}
		return new float[] {0, 0, 0};
	}

	private int clampFrame(int i) {
		return Math.max(0, Math.min(frames.size() - 1, i));
	}

	private int timeOf(int playIndex) {
		int t;
		if (order != null) {
			t = orderTimes != null && playIndex < orderTimes.size() ? orderTimes.get(playIndex) : 0;
		} else {
			t = frameTimes.get(playIndex);
		}
		return Math.max(1, t > 0 ? t : frametime);
	}

	/** The pixels shown {@code ticks} into the animation (blended when interpolating). */
	public int[] pixelsAt(double ticks) {
		float[] f = frameAt(ticks);
		int[] a = frames.get((int) f[0]);
		if (!interpolate || f[0] == f[1]) {
			return a;
		}
		int[] b = frames.get((int) f[1]);
		int[] out = new int[a.length];
		for (int i = 0; i < a.length; i++) {
			out[i] = PixelOps.lerp(a[i], b[i], f[2]);
		}
		return out;
	}

	// ---- Frames ----

	public void addFrame(boolean duplicate) {
		checkpoint(duplicate ? "Duplicate frame" : "New frame");
		int[] frame = duplicate ? frame().clone() : new int[width * height];
		frames.add(current + 1, frame);
		frameTimes.add(current + 1, 0);
		current++;
		order = null;
		changed();
	}

	public void deleteFrame() {
		if (frames.size() <= 1) {
			return;
		}
		checkpoint("Delete frame");
		frames.remove(current);
		frameTimes.remove(current);
		current = Math.min(current, frames.size() - 1);
		order = null;
		changed();
	}

	public void moveFrame(int by) {
		int to = current + by;
		if (to < 0 || to >= frames.size()) {
			return;
		}
		checkpoint("Move frame");
		frames.add(to, frames.remove(current));
		frameTimes.add(to, frameTimes.remove(current));
		current = to;
		order = null;
		changed();
	}

	/** Changes the canvas size, scaling every frame (nearest neighbour) or keeping pixels where they are. */
	public void resize(int nw, int nh, boolean scale) {
		checkpoint("Resize");
		for (int i = 0; i < frames.size(); i++) {
			int[] src = frames.get(i);
			if (scale) {
				frames.set(i, PixelOps.resample(src, width, height, nw, nh));
			} else {
				int[] out = new int[nw * nh];
				for (int y = 0; y < Math.min(height, nh); y++) {
					System.arraycopy(src, y * width, out, y * nw, Math.min(width, nw));
				}
				frames.set(i, out);
			}
		}
		width = nw;
		height = nh;
		selection = null;
		changed();
	}

	// ---- History ----

	/** Remembers the current state before a change ({@code label} names it in the status line). */
	public void checkpoint(String label) {
		List<int[]> copy = new ArrayList<>();
		for (int[] f : frames) {
			copy.add(f.clone());
		}
		undo.push(new Snapshot(width, height, copy, new ArrayList<>(frameTimes), selection == null ? null : selection.clone(), current, label));
		redo.clear();
		long total = 0;
		for (Snapshot s : undo) {
			total += s.bytes();
		}
		while (undo.size() > 2 && (total > UNDO_BUDGET_BYTES || undo.size() > 200)) {
			total -= undo.removeLast().bytes();
		}
	}

	/** Marks the pixels changed (dirty, previews refresh). */
	public void changed() {
		dirty = true;
		revision++;
	}

	public String undo() {
		return swap(undo, redo);
	}

	public String redo() {
		return swap(redo, undo);
	}

	private String swap(Deque<Snapshot> from, Deque<Snapshot> to) {
		if (from.isEmpty()) {
			return null;
		}
		Snapshot s = from.pop();
		List<int[]> copy = new ArrayList<>();
		for (int[] f : frames) {
			copy.add(f.clone());
		}
		to.push(new Snapshot(width, height, copy, new ArrayList<>(frameTimes), selection == null ? null : selection.clone(), current, s.label()));
		width = s.width();
		height = s.height();
		frames.clear();
		frames.addAll(s.frames());
		frameTimes.clear();
		frameTimes.addAll(s.times());
		selection = s.selection();
		current = Math.min(s.current(), frames.size() - 1);
		changed();
		return s.label();
	}

	public boolean canUndo() {
		return !undo.isEmpty();
	}

	public int undoCount() {
		return undo.size();
	}

	public int redoCount() {
		return redo.size();
	}

	public boolean canRedo() {
		return !redo.isEmpty();
	}
}
