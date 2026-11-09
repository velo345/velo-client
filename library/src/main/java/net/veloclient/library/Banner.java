package net.veloclient.library;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Server banners and server-list (MOTD) pictures. A banner is built from layers - background (gradient, block
 * texture, pattern, glow), icon, title (MiniMessage, Minecraft or Inter font), MOTD lines, live player count, ping bars,
 * version and address - and can be animated (the gradient and title shimmer) as a looping GIF.
 */
final class Banner {

	static final Map<String, int[]> THEMES = Map.of(
			"dark", new int[] {0x0e0e12, 0x1e1e26, 0xe0262e},
			"velo", new int[] {0x3a0a0e, 0xe0262e, 0xffffff},
			"neon", new int[] {0x1a0f2a, 0x0f2a2a, 0xff55ff},
			"sunset", new int[] {0x2a0f1a, 0xf4802e, 0xffd27a},
			"ocean", new int[] {0x061a33, 0x1f6fb2, 0x55ffff},
			"forest", new int[] {0x0b1f0e, 0x2f7a3a, 0x9be36a},
			"gold", new int[] {0x1f1606, 0x8a6414, 0xffd34a},
			"mono", new int[] {0x050505, 0x2a2a2a, 0xffffff});

	private final Assets assets;
	private final Text.McFont mcFont;
	private final Font inter;
	private final Font interBold;

	Banner(Assets assets, Text.McFont mcFont, Font inter, Font interBold) {
		this.assets = assets;
		this.mcFont = mcFont;
		this.inter = inter;
		this.interBold = interBold;
	}

	/** Everything a banner can be told; the API reads these from query parameters. */
	record Spec(int w, int h, String theme, int c1, int c2, int accent, double angle, String texture, String pattern, boolean glow, int radius,
			String title, String titleFont, String lines, String icon, boolean showPlayers, boolean showPing, boolean showVersion,
			boolean showAddress, String address, String layout, boolean animate, int frames) {
	}

	static Spec spec(Http h) {
		int[] preset = switch (h.q("size", "728x90")) {
			case "468x60" -> new int[] {468, 60};
			case "1200x300" -> new int[] {1200, 300};
			case "1920x480" -> new int[] {1920, 480};
			case "1000x200" -> new int[] {1000, 200};
			case "600x100" -> new int[] {600, 100};
			default -> new int[] {728, 90};
		};
		int w = h.qi("w", preset[0], 200, 1920), height = h.qi("h", preset[1], 40, 600);
		if ((long) w * height > 1920L * 480) {
			throw new Http.Fail(400, "Banner too large (max 1920x480 pixels)");
		}
		String theme = h.q("theme", "dark");
		int[] t = THEMES.getOrDefault(theme, THEMES.get("dark"));
		boolean anim = h.qb("animate", false);
		return new Spec(w, height, theme, hex(h.q("c1", null), t[0]), hex(h.q("c2", null), t[1]), hex(h.q("accent", null), t[2]),
				h.qd("angle", 0, -360, 360), h.q("texture", null), h.q("pattern", "none"), h.qb("glow", true), h.qi("radius", 12, 0, 64),
				h.q("title", null), h.q("font", "minecraft"), h.q("lines", null), h.q("icon", "server"), h.qb("players", true), h.qb("ping", true),
				h.qb("version", false), h.qb("address", true), null, h.q("layout", "left"), anim, anim ? h.qi("frames", 16, 4, 24) : 1);
	}

	private static int hex(String v, int fallback) {
		if (v == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(v.replace("#", ""), 16) & 0xffffff;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	byte[] render(Spec s, ServerPing.Result live) throws IOException {
		if (!s.animate()) {
			return png(frame(s, live, 0));
		}
		List<BufferedImage> frames = new ArrayList<>();
		for (int i = 0; i < s.frames(); i++) {
			frames.add(frame(s, live, i / (double) s.frames()));
		}
		return gif(frames, 80);
	}

	private BufferedImage frame(Spec s, ServerPing.Result live, double t) throws IOException {
		BufferedImage img = new BufferedImage(s.w(), s.h(), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
		var shape = new RoundRectangle2D.Double(0, 0, s.w(), s.h(), s.radius() * 2, s.radius() * 2);
		g.setClip(shape);

		// background
		double ang = Math.toRadians(s.angle() + t * 360 * (s.animate() ? 1 : 0) * 0.25);
		double cx = s.w() / 2.0, cy = s.h() / 2.0, r = Math.hypot(s.w(), s.h()) / 2;
		Point2D p1 = new Point2D.Double(cx - Math.cos(ang) * r, cy - Math.sin(ang) * r);
		Point2D p2 = new Point2D.Double(cx + Math.cos(ang) * r, cy + Math.sin(ang) * r);
		g.setPaint(new LinearGradientPaint(p1, p2, new float[] {0f, 1f}, new Color[] {new Color(s.c1()), new Color(s.c2())}));
		g.fill(shape);
		if (s.texture() != null && assets.ready()) {
			String id = assets.resolve(s.texture());
			BufferedImage tex = id == null ? null : assets.texture("block/" + id);
			if (tex == null && id != null) {
				tex = assets.icon(id, 16);
			}
			if (tex != null) {
				int tile = Math.max(16, s.h() / 3);
				double shift = s.animate() ? t * tile : 0;
				g.setComposite(AlphaComposite.SrcOver.derive(0.55f));
				BufferedImage scaled = scale(tex, tile);
				g.setPaint(new TexturePaint(scaled, new java.awt.Rectangle((int) shift, 0, tile, tile)));
				g.fill(shape);
				g.setComposite(AlphaComposite.SrcOver);
				g.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 170), s.w(), 0, new Color(0, 0, 0, 70)));
				g.fill(shape);
			}
		}
		pattern(g, s, t);
		if (s.glow()) {
			float gx = (float) (s.layout().equals("center") ? s.w() / 2.0 : s.w() * (0.18 + 0.1 * Math.sin(t * Math.PI * 2)));
			g.setPaint(new RadialGradientPaint(gx, s.h() * 0.5f, (float) Math.max(s.h() * 1.4, 40), new float[] {0f, 1f},
					new Color[] {new Color(s.accent() & 0xffffff | 0x55000000, true), new Color(s.accent() & 0xffffff, true)}));
			g.fill(shape);
		}

		// content
		int pad = Math.max(8, s.h() / 7);
		int iconSize = s.h() - pad * 2;
		int x = pad;
		BufferedImage icon = icon(s, live, iconSize);
		boolean center = s.layout().equals("center");
		if (icon != null && !center) {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			g.drawImage(icon, x, pad, iconSize, iconSize, null);
			x += iconSize + pad;
		}
		int right = s.w() - pad;

		// right column: players + ping
		if (s.showPlayers() || s.showPing()) {
			String count = live == null ? "" : live.online() ? live.online_players() + (s.h() >= 80 ? " / " + live.max() : "") : "offline";
			Font f = interBold.deriveFont((float) Math.max(12, s.h() * 0.26));
			g.setFont(f);
			int cw = g.getFontMetrics().stringWidth(count);
			int top = pad + g.getFontMetrics().getAscent();
			if (s.showPing() && live != null) {
				int bars = !live.online() ? 0 : live.pingMs() < 0 ? 3 : live.pingMs() < 80 ? 5 : live.pingMs() < 150 ? 4 : live.pingMs() < 300 ? 3 : 2;
				int bw = Math.max(2, s.h() / 28), bh = Math.max(8, (int) (s.h() * 0.2));
				int bx = right - (bw + bw / 2 + 1) * 5;
				for (int i = 0; i < 5; i++) {
					int hh = bh * (i + 1) / 5;
					g.setColor(i < bars ? new Color(0x55FF55) : new Color(255, 255, 255, 50));
					g.fillRect(bx + i * (bw + bw / 2 + 1), pad + bh - hh, bw, hh);
				}
				top = pad + bh + g.getFontMetrics().getAscent() + Math.max(2, s.h() / 30);
			}
			if (s.showPlayers() && live != null) {
				g.setColor(live.online() ? Color.WHITE : new Color(0xff6b73));
				g.drawString(count, right - cw, top);
				if (s.h() >= 70) {
					Font small = inter.deriveFont((float) Math.max(10, s.h() * 0.13));
					g.setFont(small);
					g.setColor(new Color(255, 255, 255, 170));
					String lbl = live.online() ? "online" : "";
					g.drawString(lbl, right - g.getFontMetrics().stringWidth(lbl), top + g.getFontMetrics().getHeight());
				}
				right -= cw + pad;
			}
		}

		// title + lines
		String titleSrc = s.title() != null ? s.title() : live != null ? "<white><bold>" + live.host() : "<white><bold>My Server";
		List<Text.Run> title = Text.fromMiniMessage(shimmer(titleSrc, s, t));
		List<List<Text.Run>> lines = new ArrayList<>();
		if (s.lines() != null) {
			for (String l : s.lines().split("\\\\n|<newline>|\n", 3)) {
				lines.add(Text.fromMiniMessage(l));
			}
		} else if (live != null && live.online()) {
			lines.addAll(splitLines(live.motd()));
		}
		List<Text.Run> meta = new ArrayList<>();
		if (s.showAddress() && live != null) {
			meta.add(new Text.Run(live.host(), 0xBBBBBB, false, false, false, false, false));
		}
		if (s.showVersion() && live != null && live.online() && live.version() != null) {
			meta.add(new Text.Run((meta.isEmpty() ? "" : "  ·  ") + live.version().replaceAll("§.", ""), 0x999999, false, false, false, false, false));
		}
		int avail = right - x;
		int titleScale = Math.max(1, Math.min((int) (s.h() * 0.30 / 8), avail / Math.max(1, mcFont.width(title))));
		int rows = lines.size() + (meta.isEmpty() ? 0 : 1);
		int spareH = s.h() - pad - 9 * titleScale;
		while (rows > 1 && spareH / (10 * rows) < 1) {
			if (!meta.isEmpty()) {
				meta.clear();
			} else {
				lines.remove(lines.size() - 1);
			}
			rows--;
		}
		int lineScale = Math.max(1, Math.min(Math.max(1, (int) Math.ceil(titleScale * 0.6)), Math.min(spareH / Math.max(1, 10 * rows), avail / Math.max(1, maxWidth(lines)))));
		int blockH = 9 * titleScale + (lines.size() + (meta.isEmpty() ? 0 : 1)) * (10 * lineScale);
		int y = Math.max(pad / 2, (s.h() - blockH) / 2);
		boolean mc = !s.titleFont().equals("inter");
		if (center) {
			int tw = mc ? mcFont.width(title) * titleScale : 0;
			x = (s.w() - (mc ? tw : avail)) / 2;
		}
		if (mc) {
			mcFont.draw(g, title, x, y, titleScale, true);
		} else {
			g.setFont(interBold.deriveFont((float) (9 * titleScale)));
			int tx = x;
			for (Text.Run run : title) {
				g.setColor(new Color(run.rgb()));
				g.drawString(run.text(), tx, y + 8 * titleScale);
				tx += g.getFontMetrics().stringWidth(run.text());
			}
		}
		y += 9 * titleScale + 2 * lineScale;
		for (List<Text.Run> line : lines) {
			int lx = center ? (s.w() - mcFont.width(line) * lineScale) / 2 : x;
			mcFont.draw(g, line, lx, y, lineScale, true);
			y += 10 * lineScale;
		}
		if (!meta.isEmpty() && y + 8 * lineScale <= s.h()) {
			int lx = center ? (s.w() - mcFont.width(meta) * lineScale) / 2 : x;
			mcFont.draw(g, meta, lx, y, lineScale, true);
		}
		g.setClip(null);
		g.setStroke(new BasicStroke(Math.max(1, s.h() / 90f)));
		g.setColor(new Color(255, 255, 255, 40));
		g.draw(new RoundRectangle2D.Double(0.5, 0.5, s.w() - 1, s.h() - 1, s.radius() * 2, s.radius() * 2));
		g.dispose();
		return img;
	}

	/** Animated banners: a gradient title's colours roll across the text (stops resampled from a looped gradient). */
	private static String shimmer(String src, Spec s, double t) {
		if (!s.animate()) {
			return src;
		}
		java.util.regex.Matcher m = java.util.regex.Pattern.compile("<gradient((?::#[0-9a-fA-F]{6})+)>").matcher(src);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String[] hexes = m.group(1).substring(1).split(":");
			int[] c = new int[hexes.length + 1];
			for (int i = 0; i < hexes.length; i++) {
				c[i] = Integer.parseInt(hexes[i].substring(1), 16);
			}
			c[hexes.length] = c[0];
			StringBuilder tag = new StringBuilder("<gradient");
			for (int k = 0; k < 6; k++) {
				double pos = ((k / 5.0) * 0.5 + t) % 1.0 * (c.length - 1);
				int i = (int) Math.floor(pos);
				double f = pos - i;
				int a = c[i], b = c[Math.min(i + 1, c.length - 1)];
				int r = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * f);
				int g = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * f);
				int bl = (int) ((a & 255) + ((b & 255) - (a & 255)) * f);
				tag.append(String.format(":#%02x%02x%02x", r, g, bl));
			}
			m.appendReplacement(out, tag.append('>').toString());
		}
		m.appendTail(out);
		return out.toString();
	}

	private int maxWidth(List<List<Text.Run>> lines) {
		int m = 1;
		for (List<Text.Run> l : lines) {
			m = Math.max(m, mcFont.width(l));
		}
		return m;
	}

	private static List<List<Text.Run>> splitLines(List<Text.Run> runs) {
		List<List<Text.Run>> out = new ArrayList<>();
		List<Text.Run> cur = new ArrayList<>();
		for (Text.Run r : runs) {
			String[] parts = r.text().split("\n", -1);
			for (int i = 0; i < parts.length; i++) {
				if (i > 0) {
					out.add(cur);
					cur = new ArrayList<>();
				}
				if (!parts[i].isEmpty()) {
					cur.add(new Text.Run(cur.isEmpty() ? parts[i].stripLeading() : parts[i], r.rgb(), r.bold(), r.italic(), r.underline(), r.strike(), false));
				}
			}
		}
		out.add(cur);
		out.removeIf(List::isEmpty);
		return out.size() > 2 ? out.subList(0, 2) : out;
	}

	private void pattern(Graphics2D g, Spec s, double t) {
		switch (s.pattern()) {
			case "grid" -> {
				g.setColor(new Color(255, 255, 255, 14));
				int step = Math.max(10, s.h() / 6);
				int off = (int) (s.animate() ? t * step : 0);
				for (int x = -step + off; x < s.w(); x += step) {
					g.drawLine(x, 0, x, s.h());
				}
				for (int y = 0; y < s.h(); y += step) {
					g.drawLine(0, y, s.w(), y);
				}
			}
			case "dots" -> {
				g.setColor(new Color(255, 255, 255, 22));
				int step = Math.max(8, s.h() / 8);
				for (int x = 0; x < s.w(); x += step) {
					for (int y = 0; y < s.h(); y += step) {
						g.fillOval(x, y, 2, 2);
					}
				}
			}
			case "stripes" -> {
				g.setColor(new Color(255, 255, 255, 12));
				int step = Math.max(14, s.h() / 4);
				int off = (int) (s.animate() ? t * step * 2 : 0);
				for (int x = -s.h() - step * 2 + off; x < s.w(); x += step * 2) {
					g.fillPolygon(new int[] {x, x + step, x + step + s.h(), x + s.h()}, new int[] {0, 0, s.h(), s.h()}, 4);
				}
			}
			case "pixels" -> {
				java.util.Random rnd = new java.util.Random(s.c1() * 31L + s.c2());
				int px = Math.max(4, s.h() / 12);
				for (int x = 0; x < s.w(); x += px) {
					for (int y = 0; y < s.h(); y += px) {
						int a = rnd.nextInt(26);
						double tw = s.animate() ? (Math.sin((t + rnd.nextDouble()) * Math.PI * 2) + 1) / 2 : 1;
						g.setColor(new Color(255, 255, 255, (int) (a * tw)));
						g.fillRect(x, y, px, px);
					}
				}
			}
			default -> {
			}
		}
	}

	private BufferedImage icon(Spec s, ServerPing.Result live, int size) {
		try {
			if (s.icon().equals("none")) {
				return null;
			}
			if (s.icon().equals("server")) {
				if (live != null && live.favicon() != null) {
					return ImageIO.read(new ByteArrayInputStream(live.favicon()));
				}
				return assets.ready() ? assets.icon("grass_block", size) : null;
			}
			String id = assets.ready() ? assets.resolve(s.icon()) : null;
			return id == null ? null : assets.icon(id, Math.max(16, size));
		} catch (IOException e) {
			return null;
		}
	}

	private static BufferedImage scale(BufferedImage src, int size) {
		BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g.drawImage(src, 0, 0, size, size, null);
		g.dispose();
		return out;
	}

	/** The server list entry exactly like the game draws it (scale 2): icon, name, two MOTD lines, count, ping. */
	byte[] motd(String name, List<Text.Run> motd, BufferedImage icon, String players, int scale) throws IOException {
		int w = 305 * scale, h = 36 * scale;
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		BufferedImage dirt = assets.ready() ? assets.texture("block/dirt") : null;
		if (dirt != null) {
			g.setPaint(new TexturePaint(scale(dirt, 32 * scale), new java.awt.Rectangle(0, 0, 32 * scale, 32 * scale)));
			g.fillRect(0, 0, w, h);
			g.setColor(new Color(0, 0, 0, 160));
		} else {
			g.setColor(new Color(0x101010));
		}
		g.fillRect(0, 0, w, h);
		g.setColor(new Color(255, 255, 255, 140));
		g.drawRect(0, 0, w - 1, h - 1);
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		if (icon != null) {
			g.drawImage(icon, 2 * scale, 2 * scale, 32 * scale, 32 * scale, null);
		}
		int tx = 37 * scale;
		mcFont.draw(g, List.of(new Text.Run(name, 0xFFFFFF, false, false, false, false, false)), tx, 3 * scale, scale, true);
		List<List<Text.Run>> lines = splitLines(motd);
		int y = 15 * scale;
		for (List<Text.Run> l : lines) {
			mcFont.draw(g, l, tx, y, scale, true);
			y += 9 * scale;
		}
		if (players != null) {
			List<Text.Run> pr = List.of(new Text.Run(players, 0xAAAAAA, false, false, false, false, false));
			int pw = mcFont.width(pr) * scale;
			mcFont.draw(g, pr, w - pw - 14 * scale, 3 * scale, scale, true);
			for (int i = 0; i < 5; i++) {
				g.setColor(new Color(0x55FF55));
				g.fillRect(w - 12 * scale + i * 2 * scale, 3 * scale + (4 - i) * scale + scale, scale, (i + 1) * scale + scale);
			}
		}
		g.dispose();
		return png(img);
	}

	static byte[] png(BufferedImage img) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(img, "png", out);
		return out.toByteArray();
	}

	/** A looping GIF (frames delayed {@code delayMs}); pixels with alpha below half become transparent. */
	static byte[] gif(List<BufferedImage> frames, int delayMs) throws IOException {
		ImageWriter writer = ImageIO.getImageWritersBySuffix("gif").next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
			writer.setOutput(out);
			writer.prepareWriteSequence(null);
			for (int i = 0; i < frames.size(); i++) {
				BufferedImage f = frames.get(i);
				ImageTypeSpecifier type = ImageTypeSpecifier.createFromRenderedImage(f);
				IIOMetadata meta = writer.getDefaultImageMetadata(type, null);
				String fmt = meta.getNativeMetadataFormatName();
				IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(fmt);
				IIOMetadataNode gce = child(root, "GraphicControlExtension");
				gce.setAttribute("disposalMethod", "restoreToBackgroundColor");
				gce.setAttribute("userInputFlag", "FALSE");
				gce.setAttribute("transparentColorFlag", "FALSE");
				gce.setAttribute("delayTime", String.valueOf(delayMs / 10));
				gce.setAttribute("transparentColorIndex", "0");
				if (i == 0) {
					IIOMetadataNode apps = child(root, "ApplicationExtensions");
					IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
					app.setAttribute("applicationID", "NETSCAPE");
					app.setAttribute("authenticationCode", "2.0");
					app.setUserObject(new byte[] {1, 0, 0});
					apps.appendChild(app);
				}
				meta.setFromTree(fmt, root);
				writer.writeToSequence(new IIOImage(f, null, meta), null);
			}
			writer.endWriteSequence();
		} finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

	private static IIOMetadataNode child(IIOMetadataNode root, String name) {
		for (int i = 0; i < root.getLength(); i++) {
			if (root.item(i).getNodeName().equalsIgnoreCase(name)) {
				return (IIOMetadataNode) root.item(i);
			}
		}
		IIOMetadataNode n = new IIOMetadataNode(name);
		root.appendChild(n);
		return n;
	}
}
