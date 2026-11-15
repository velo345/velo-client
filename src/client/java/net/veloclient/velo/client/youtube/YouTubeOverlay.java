package net.veloclient.velo.client.youtube;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.VeloClient;
import net.veloclient.velo.client.clips.core.ClipTools;
import net.veloclient.velo.client.clips.core.VideoPlayer;
import net.veloclient.velo.client.gui.RemoteTextureLoader;
import net.veloclient.velo.client.gui.media.MediaTextures;
import net.veloclient.velo.client.gui.media.VideoView;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The YouTube overlay: one floating video window over the game. The controls only appear while the mouse is on it
 * (fading in over soft shades, like YouTube's own player); drag the video to move it, any edge or corner to resize it, a
 * click pauses. Minimize it to a pill, maximize it, or go fullscreen. With {@link YouTubeScreen} open it's interactive (search, controls);
 * closed, it keeps playing as picture-in-picture on the HUD. Videos are found and resolved by {@link YtDlp} and played by
 * Velo's own FFmpeg player (picture + sound), so it works the same on OpenGL and Vulkan.
 */
public final class YouTubeOverlay {

	public enum Mode { NORMAL, MINIMIZED, MAXIMIZED, FULLSCREEN }

	private static final int MIN_W = 170;
	private static final int MIN_H = 110;
	private static final int ROW_H = 40;
	private static final int PILL_W = 210;
	private static final int PILL_H = 22;

	// window as fractions of the screen, so it survives GUI scale and window size changes
	static float fx = 0.60f;
	static float fy = 0.06f;
	static float fw = 0.38f;
	static float fh = 0.42f;
	static Mode mode = Mode.NORMAL;
	static int quality = 720;
	static float pipOpacity = 1f;
	/** Where the search box looks: false = YouTube, true = Twitch (channel name -> live stream + latest videos). */
	static boolean twitch;
	/** Shorts: a vertical window and an endless feed - next/previous with the wheel, arrow keys or the Shorts keys. */
	static boolean shorts;
	/** Files: videos from this PC. */
	static boolean files;
	private static volatile List<YtDlp.Result> feed = List.of();
	private static volatile int feedIndex = -1;
	private static String feedQuery = "";
	private static volatile boolean feedLoading;
	private static boolean shortPlaying;
	private static long lastFlick;
	private static int failedShorts;
	private static int feedSkip;

	private static VideoView view;
	private static String title;
	private static String channel;
	private static volatile List<YtDlp.Result> results = List.of();
	private static volatile boolean searching;
	private static volatile String searchStatus;
	static boolean showResults;
	private static int scroll;

	// interaction
	private static int dragKind; // 0 none, 1 move, 2 resize, 3 seek, 4 volume, 5 click-or-move, 6 click
	private static double grabX;
	private static double grabY;
	private static int[] grabRect = {0, 0, 0, 0};
	private static int grabEdges;
	// hover animation (0 hidden .. 1 shown)
	private static float chrome;
	private static float lineHover;
	private static float volHover;
	private static float hoverPill;
	private static long lastNanos;
	private static final List<VeloUi.Hit> HITS = new ArrayList<>();
	// where things were drawn last frame (for input)
	private static int lineX;
	private static int lineW;
	private static int volX;
	private static int volW;
	private static int barY;
	private static int[] lastRect = {0, 0, 0, 0};
	/** Where the search field goes this frame (the screen moves its text field there); null = hidden. */
	static int[] searchFieldRect;

	private YouTubeOverlay() {
	}

	public static boolean active() {
		return view != null;
	}

	public static Mode mode() {
		return mode;
	}

	// ---------------------------------------------------------------- playback

	/** Plays a video by id or link. */
	public static void play(String idOrLink, String knownTitle, String knownChannel) {
		shortPlaying = false;
		String id = YtDlp.videoId(idOrLink);
		if (id == null && idOrLink != null && idOrLink.contains("twitch.tv")) {
			id = YtDlp.twitchLink(idOrLink);
		}
		if (id == null && idOrLink != null && idOrLink.trim().matches("(?i)https?://\\S+")) {
			// any other site yt-dlp knows (Vimeo, TikTok, X, Reddit, ...)
			id = idOrLink.trim();
		}
		if (id == null) {
			search(idOrLink);
			return;
		}
		stop();
		title = knownTitle != null ? knownTitle : "Loading...";
		channel = knownChannel;
		showResults = false;
		int maxHeight = quality;
		String target = id;
		view = new VideoView(ffmpeg -> {
			try {
				YtDlp.Stream s = YtDlp.resolve(target, maxHeight);
				MinecraftClient.getInstance().execute(() -> {
					title = s.title();
					channel = s.channel();
				});
				double seconds = s.live() || s.seconds() <= 0 ? 10_000_000 : s.seconds();
				ClipTools.Info info = new ClipTools.Info(seconds, s.width(), s.height(), s.fps(), true);
				// through the local relay: YouTube refuses FFmpeg's open-ended range requests
				String video = YtProxy.local(s.video(), s.userAgent());
				String audio = s.audio() == null ? null : YtProxy.local(s.audio(), s.userAgent());
				return VideoPlayer.openStreams(ffmpeg, video, audio, s.userAgent(), info, Math.min(1920, Math.max(640, s.width())),
						VideoPlayer.Pixels.RGBA);
			} catch (java.io.IOException e) {
				throw new IllegalStateException(e.getMessage(), e);
			}
		}, true, "Loading video...");
	}

	public static void stop() {
		shortPlaying = false;
		if (view != null) {
			view.release();
			view = null;
		}
		title = null;
		channel = null;
	}

	// ---------------------------------------------------------------- shorts

	/** Starts a Shorts feed for {@code query} ("" = the general feed) and plays the first one. */
	static void loadShorts(String query) {
		feedQuery = query == null ? "" : query.trim();
		feedSkip = 0;
		feed = List.of();
		feedIndex = -1;
		feedLoading = true;
		searching = true;
		searchStatus = "Loading Shorts...";
		showResults = true;
		Thread.ofVirtual().name("velo-shorts").start(() -> {
			try {
				List<YtDlp.Result> found = YtDlp.shorts(feedQuery, 0, 30);
				feed = found;
				if (found.isEmpty()) {
					searchStatus = "No Shorts found";
					return;
				}
				searchStatus = null;
				MinecraftClient.getInstance().execute(() -> playShort(0));
			} catch (Exception e) {
				searchStatus = "Couldn't load Shorts: " + e.getMessage();
				VeloClient.LOGGER.warn("Shorts failed", e);
			} finally {
				feedLoading = false;
				searching = false;
			}
		});
	}

	private static void playShort(int index) {
		List<YtDlp.Result> list = feed;
		if (index < 0 || index >= list.size()) {
			return;
		}
		feedIndex = index;
		YtDlp.Result r = list.get(index);
		play(r.id(), r.title(), r.channel());
		shortPlaying = true;
		// the next two resolve in the background: scrolling on starts at once
		for (int k = 1; k <= 2 && index + k < list.size(); k++) {
			YtDlp.prefetch(list.get(index + k).id(), quality);
		}
		showResults = false;
		// near the end of what's loaded: fetch the next page
		if (index >= list.size() - 4 && !feedLoading) {
			feedLoading = true;
			int have = list.size();
			Thread.ofVirtual().name("velo-shorts-more").start(() -> {
				try {
					List<YtDlp.Result> more = YtDlp.shorts(feedQuery, feedSkip += 90, 30);
					List<YtDlp.Result> merged = new ArrayList<>(feed);
					for (YtDlp.Result m : more) {
						if (merged.stream().noneMatch(x -> x.id().equals(m.id()))) {
							merged.add(m);
						}
					}
					feed = List.copyOf(merged);
				} catch (Exception ignored) {
					// the feed just ends here
				} finally {
					feedLoading = false;
				}
			});
		}
	}

	/** Next (+1) or previous (-1) short. True when there was one. */
	public static boolean flick(int direction) {
		if (!shortPlaying || System.currentTimeMillis() - lastFlick < 220) {
			return shortPlaying;
		}
		int next = feedIndex + direction;
		if (next < 0 || next >= feed.size()) {
			return true;
		}
		lastFlick = System.currentTimeMillis();
		playShort(next);
		return true;
	}

	/** Dev tour: switch to Shorts and load the feed. */
	public static void devShorts() {
		setSource(1);
	}

	/** Dev tour: pick a source (0 YouTube, 1 Shorts, 2 Twitch, 3 Files). */
	public static void devSource(int source) {
		setSource(source);
	}

	public static boolean shortPlaying() {
		return shortPlaying && view != null;
	}

	public static void togglePause() {
		VideoPlayer p = view == null ? null : view.player();
		if (p != null) {
			p.toggle();
		}
	}

	public static void search(String query) {
		if (query == null || query.isBlank()) {
			return;
		}
		String q = query.trim();
		// a file on this PC
		if (q.startsWith("/") || q.startsWith("~") || q.matches("(?i)^[a-z]:[\\\\/].*") || q.startsWith("file:")) {
			String path = q.startsWith("file:") ? URI.create(q).getPath() : q.startsWith("~") ? System.getProperty("user.home") + q.substring(1) : q;
			java.nio.file.Path f = java.nio.file.Path.of(path.replaceAll("^\"|\"$", ""));
			if (java.nio.file.Files.isRegularFile(f)) {
				playFile(f);
				return;
			}
		}
		// a link straight to a media file / stream
		if (q.matches("(?i)https?://\\S+") && directMedia(q)) {
			playDirect(q);
			return;
		}
		// a pasted link plays exactly that: a YouTube video / short / live, a Twitch video or clip, or a Twitch
		// channel's live stream (its latest videos when it isn't live). On Twitch an 11-letter channel name must not be
		// read as a YouTube video id.
		boolean youtubeLink = q.contains("youtu");
		String id = twitch && !youtubeLink ? null : YtDlp.videoId(q);
		String twitchLink = q.contains("twitch.tv") ? YtDlp.twitchLink(q) : null;
		if (id != null && twitchLink == null) {
			play(id, null, null);
			return;
		}
		if (YtDlp.twitchVideo(twitchLink)) {
			play(twitchLink, null, null);
			return;
		}
		if (twitchLink == null && id == null && q.matches("(?i)https?://\\S+")) {
			play(q, null, null); // any other video site, through yt-dlp
			return;
		}
		if (shorts && twitchLink == null) {
			loadShorts(q);
			return;
		}
		boolean onTwitch = twitch || twitchLink != null;
		boolean channelLink = twitchLink != null;
		searching = true;
		searchStatus = channelLink ? "Opening the stream..." : "Searching...";
		showResults = true;
		scroll = 0;
		results = List.of();
		Thread.ofVirtual().name("velo-youtube-search").start(() -> {
			try {
				List<YtDlp.Result> found = onTwitch ? YtDlp.twitch(channelLink ? twitchLink : q, 20) : YtDlp.search(q, 20);
				if (channelLink && !found.isEmpty() && found.get(0).live()) {
					YtDlp.Result live = found.get(0);
					MinecraftClient.getInstance().execute(() -> play(live.id(), live.title(), live.channel()));
					return;
				}
				results = found;
				searchStatus = found.isEmpty() ? (onTwitch ? "No live stream or videos on this channel" : "No videos found") : null;
			} catch (Exception e) {
				searchStatus = "Search failed: " + e.getMessage();
				VeloClient.LOGGER.warn("YouTube search failed", e);
			} finally {
				searching = false;
			}
		});
	}


	/** A result's thumbnail from the player's own cache (stays loaded while a video plays as picture-in-picture). */
	private static Identifier thumb(YtDlp.Result r) {
		return YtThumbs.get(r.id(), r.thumbnail());
	}

	// ---------------------------------------------------------------- geometry

	/** The window (or pill) rectangle for this screen size and mode. */
	static int[] rect(int sw, int sh) {
		return switch (mode) {
			case FULLSCREEN -> new int[] {0, 0, sw, sh};
			case MAXIMIZED -> new int[] {Math.round(sw * 0.06f), Math.round(sh * 0.06f), Math.round(sw * 0.88f), Math.round(sh * 0.88f)};
			case MINIMIZED -> {
				int x = clamp(Math.round(fx * sw), 0, sw - PILL_W);
				int y = clamp(Math.round(fy * sh), 0, sh - PILL_H);
				yield new int[] {x, y, PILL_W, PILL_H};
			}
			default -> {
				boolean shortWindow = shortPlaying && view != null && !showResults;
				// a short plays in a tall 9:16 window (at least 70 % of the screen height)
				int h = shortWindow ? clamp(Math.max(Math.round(fh * sh), Math.round(sh * 0.7f)), MIN_H, sh - 8) : clamp(Math.round(fh * sh), MIN_H, sh);
				int w = shortWindow ? clamp(Math.round(h * 9 / 16f), 120, sw) : clamp(Math.round(fw * sw), MIN_W, sw);
				if (browsing && (showResults || view == null)) {
					// browsing needs room: at least about 380 x 270, back to the video's size when it plays
					w = Math.max(w, Math.min(sw - 16, 380));
					h = Math.max(h, Math.min(sh - 16, 270));
				}
				int x = clamp(Math.round(fx * sw), 0, sw - w);
				int y = clamp(Math.round(fy * sh), 0, sh - h);
				yield new int[] {x, y, w, h};
			}
		};
	}

	private static int clamp(int v, int lo, int hi) {
		return v < lo ? lo : Math.min(v, Math.max(lo, hi));
	}

	// ---------------------------------------------------------------- drawing

	private static final int BG = 0xFF07070A;
	private static final int SURFACE = 0xFF111117;
	private static final int SURFACE_2 = 0xFF1A1A22;
	private static final int SURFACE_3 = 0xFF24242E;
	private static final int HAIR = 0x1EFFFFFF;
	private static final int TEXT = 0xFFF2F2F5;
	private static final int MUTED = 0xFF8C8C99;
	private static final String[] SOURCES = {"YouTube", "Shorts", "Twitch", "Files"};

	/** Set while the player window is open (rect() then gives the browser its room). */
	private static boolean browsing;

	private static int source() {
		return files ? 3 : twitch ? 2 : shorts ? 1 : 0;
	}

	/** The colour of what's playing / picked: YouTube red, Twitch purple, the Velo accent for files. */
	private static int accent() {
		return switch (source()) {
			case 2 -> 0xFF9146FF;
			case 3 -> MenuKit.accent();
			default -> 0xFFFF2D3C;
		};
	}

	private static TextRenderer tr() {
		return MinecraftClient.getInstance().textRenderer;
	}

	private static void text(DrawContext context, Text t, int x, int y, int color) {
		MenuKit.text(context, tr(), t, x, y, color);
	}

	private static int width(Text t) {
		return MenuKit.width(tr(), t);
	}

	private static Text fit(Text t, int maxWidth) {
		return MenuKit.fit(tr(), t, Math.max(8, maxWidth));
	}

	private static int alpha(int color, float a) {
		return VeloUi.withAlpha(color, Math.round(((color >>> 24) & 0xFF) * Math.max(0f, Math.min(1f, a))));
	}

	/**
	 * Draws the player. {@code interactive}: its window is open - just the video, with the title bar and controls
	 * fading in while the mouse is on it (or paused / nothing plays). Otherwise the quiet picture-in-picture on the HUD.
	 */
	public static void render(DrawContext context, int sw, int sh, int mouseX, int mouseY, boolean interactive) {
		HITS.clear();
		searchFieldRect = null;
		browsing = interactive;
		if (!interactive && view == null) {
			return;
		}
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0 : Math.min(0.1f, (now - lastNanos) / 1e9f);
		lastNanos = now;
		int[] r = rect(sw, sh);
		lastRect = r;
		int x = r[0];
		int y = r[1];
		int w = r[2];
		int h = r[3];
		VideoPlayer p = view == null ? null : view.player();
		if (shortPlaying && p != null && p.position() > 0.5) {
			failedShorts = 0;
		}
		// a short that finished: on to the next one (also in picture-in-picture)
		if (shortPlaying && p != null && p.duration() > 1 && p.duration() < 9_000_000 && p.position() >= p.duration() - 0.3) {
			flick(1);
		}
		MediaTextures.Tex tex = view == null ? null : view.texture();
		float pip = interactive ? 1f : pipOpacity;
		boolean inside = VeloUi.inside(mouseX, mouseY, x, y, w, h) || dragKind != 0;

		if (mode == Mode.MINIMIZED) {
			drawPill(context, x, y, w, h, p, mouseX, mouseY, interactive, inside, dt, pip);
			return;
		}

		boolean full = mode == Mode.FULLSCREEN;
		int radius = full ? 0 : 12;
		boolean panel = interactive && (showResults || view == null);
		boolean wantChrome = interactive && !panel && (inside || p == null || !p.playing());
		chrome = approach(chrome, wantChrome ? 1 : 0, dt * (wantChrome ? 9 : 4));
		float ease = chrome * chrome * (3 - 2 * chrome);

		if (!full) {
			VeloDraw.shadow(context, x, y, w, h, radius, 20, 6, alpha(0x80000000, pip));
		}
		VeloDraw.fillRounded(context, x, y, w, h, radius, alpha(BG, pip));
		if (tex != null && !panel) {
			MediaTextures.drawFit(context, tex, x, y, w, h);
		}
		String error = view == null ? null : view.error();
		if (error != null && shortPlaying) {
			// a short that can't play here (blocked in this country, private, removed): straight on to the next one
			if (++failedShorts <= 8) {
				flick(1);
			} else {
				shortPlaying = false;
				searchStatus = "These Shorts can't play here - try another topic";
				showResults = true;
			}
		}
		if (!panel) {
			if (error != null) {
				drawMessage(context, x, y, w, h, "!", readable(error), 0xFFFF6B6B);
			} else if (view != null && tex == null) {
				drawLoader(context, x + w / 2, y + h / 2 - 8, now, accent());
				Text status = MenuKit.small(view.status() == null ? "Loading" : view.status());
				text(context, fit(status, w - 24), x + (w - Math.min(width(status), w - 24)) / 2, y + h / 2 + 14, MUTED);
			}
		}

		if (!interactive) {
			// picture-in-picture: the video, a thin progress line, a hairline edge
			if (p != null && p.duration() > 0 && p.duration() < 9_000_000) {
				float f = (float) Math.min(1, p.position() / p.duration());
				VeloDraw.fillRounded(context, x + 6, y + h - 4, Math.round((w - 12) * f), 2, 1, alpha(accent(), pip));
			}
			if (!full) {
				VeloDraw.strokeRounded(context, x, y, w, h, radius, alpha(0x26FFFFFF, pip));
			}
			return;
		}

		if (panel) {
			drawBrowser(context, x, y, w, h, mouseX, mouseY, now);
		} else if (ease > 0.01f) {
			drawChrome(context, x, y, w, h, p, mouseX, mouseY, ease, full);
		}
		if (!full) {
			VeloDraw.strokeRounded(context, x, y, w, h, radius, HAIR);
			if (mode == Mode.NORMAL && inside && dragKind == 0) {
				// where the window can be grabbed to resize: a soft corner mark
				int c = alpha(0xFFFFFFFF, 0.35f * Math.max(ease, panel ? 1f : 0.5f));
				for (int i = 0; i < 3; i++) {
					context.fill(x + w - 5 - i * 3, y + h - 3, x + w - 4 - i * 3, y + h - 2, c);
					context.fill(x + w - 3, y + h - 5 - i * 3, x + w - 2, y + h - 4 - i * 3, c);
				}
			}
		}
	}

	/** Minimised: a pill with the title, a progress line and play / restore / close. */
	private static void drawPill(DrawContext context, int x, int y, int w, int h, VideoPlayer p, int mouseX, int mouseY, boolean interactive,
			boolean inside, float dt, float pip) {
		hoverPill = approach(hoverPill, interactive && inside ? 1 : 0, dt * 10);
		VeloDraw.shadow(context, x, y, w, h, h / 2, 14, 4, alpha(0x70000000, pip));
		VeloDraw.fillRounded(context, x, y, w, h, h / 2, alpha(0xF2121218, pip));
		VeloDraw.strokeRounded(context, x, y, w, h, h / 2, alpha(0xFFFFFFFF, 0.12f + 0.12f * hoverPill));
		drawSourceGlyph(context, source(), x + 9, y + (h - 12) / 2, 1f);
		Text t = MenuKit.uiBold(title == null ? "Video Player" : title);
		text(context, fit(t, w - (interactive ? 108 : 44)), x + 27, y + (h - 8) / 2, TEXT);
		if (p != null && p.duration() > 0 && p.duration() < 9_000_000) {
			float f = (float) Math.min(1, p.position() / p.duration());
			VeloDraw.fillRounded(context, x + 14, y + h - 3, Math.round((w - 28) * f), 2, 1, accent());
		}
		if (interactive) {
			int cy = y + h / 2;
			HITS.add(roundButton(context, x + w - 70, cy, 9, mouseX, mouseY, 1f, false, (cx, cyy, c) -> MenuKit.icon(context,
					p != null && p.playing() ? "pause" : "play", cx - 5, cyy - 5, 10, c), YouTubeOverlay::togglePause));
		HITS.add(roundButton(context, x + w - 48, cy, 9, mouseX, mouseY, 1f, false, (cx, cyy, c) -> drawWindowGlyph(context, cx, cyy, 2, c),
					() -> mode = Mode.NORMAL));
			HITS.add(roundButton(context, x + w - 26, cy, 9, mouseX, mouseY, 1f, true, (cx, cyy, c) -> MenuKit.icon(context, "close", cx - 5, cyy - 5, 10, c),
					YouTubeOverlay::stop));
		}
	}

	/** Over the video while hovered: title bar on top, timeline and controls at the bottom (Shorts get their own layout). */
	private static void drawChrome(DrawContext context, int x, int y, int w, int h, VideoPlayer p, int mouseX, int mouseY, float ease, boolean full) {
		boolean live = ease > 0.6f;
		// top: source, title, channel, window buttons
		int top = Math.min(60, h / 3);
		VeloDraw.fillGradient(context, x, y, w, top, alpha(0xD8000000, ease), 0x00000000);
		int bx = x + w - 16;
		int by = y + 16;
		boolean narrow = w < 300;
		add(live, roundButton(context, bx, by, 10, mouseX, mouseY, ease, true, (cx, cy, c) -> MenuKit.icon(context, "close", cx - 5, cy - 5, 10, c), () -> {
			stop();
			showResults = true;
		}));
		add(live, roundButton(context, bx - 24, by, 10, mouseX, mouseY, ease, false, (cx, cy, c) -> MenuKit.icon(context, "expand", cx - 5, cy - 5, 10, c),
				() -> mode = full ? Mode.NORMAL : Mode.FULLSCREEN));
		int nextBx = bx - 48;
		if (!narrow) {
			add(live, roundButton(context, nextBx, by, 10, mouseX, mouseY, ease, false,
					(cx, cy, c) -> drawWindowGlyph(context, cx, cy, mode == Mode.MAXIMIZED ? 1 : 2, c), () -> mode = mode == Mode.MAXIMIZED ? Mode.NORMAL : Mode.MAXIMIZED));
			add(live, roundButton(context, nextBx - 24, by, 10, mouseX, mouseY, ease, false, (cx, cy, c) -> drawWindowGlyph(context, cx, cy, 0, c),
					() -> mode = Mode.MINIMIZED));
			nextBx -= 48;
		}
		add(live, roundButton(context, nextBx, by, 10, mouseX, mouseY, ease, false, (cx, cy, c) -> drawSearchGlyph(context, cx, cy, c),
				() -> showResults = true));
		int textRight = nextBx - 16;
		boolean titleBelow = !shortPlaying && textRight - x < 150;
		if (!shortPlaying && !titleBelow) {
			drawSourceGlyph(context, source(), x + 12, y + 10, ease);
			Text t = MenuKit.uiBold(title == null ? "Video Player" : title);
			text(context, fit(t, textRight - x - 32), x + 30, y + 10, alpha(TEXT, ease));
			if (channel != null && !channel.isBlank()) {
				text(context, fit(MenuKit.small(channel), textRight - x - 32), x + 30, y + 22, alpha(0xFFB4B4C0, ease));
			}
		} else if (shortPlaying) {
			Text count = MenuKit.small((feedIndex + 1) + " / " + feed.size());
			int cw = width(count) + 12;
			VeloDraw.fillRounded(context, x + 10, y + 9, cw, 13, 6, alpha(0x90000000, ease));
			text(context, count, x + 16, y + 12, alpha(TEXT, ease));
		}

		// a big play button in the middle while paused
		if (p != null && !p.playing() && view.error() == null) {
			int cx = x + w / 2;
			int cy = y + h / 2;
			boolean hot = VeloUi.inside(mouseX, mouseY, cx - 22, cy - 22, 44, 44);
			VeloDraw.fillCircle(context, cx, cy, 22f, alpha(hot ? 0xD0000000 : 0xA0000000, ease));
			VeloDraw.strokeRounded(context, cx - 22, cy - 22, 44, 44, 22, alpha(0x40FFFFFF, ease));
			MenuKit.icon(context, "play", cx - 8, cy - 8, 16, alpha(0xFFFFFFFF, ease));
		}

		// bottom
		int shade = Math.min(shortPlaying ? 110 : 78, h / 2);
		VeloDraw.fillGradient(context, x, y + h - shade, w, shade, 0x00000000, alpha(0xE0000000, ease));
		if (shortPlaying) {
			// title and channel bottom left, previous / next on the right
			int rx = x + w - 20;
			int my = y + h / 2;
			add(live, roundButton(context, rx, my - 14, 11, mouseX, mouseY, ease, false, (cx, cy, c) -> drawChevron(context, cx, cy, true, c), () -> flick(-1)));
			add(live, roundButton(context, rx, my + 14, 11, mouseX, mouseY, ease, false, (cx, cy, c) -> drawChevron(context, cx, cy, false, c), () -> flick(1)));
			int ty = y + h - 62;
			if (channel != null && !channel.isBlank()) {
				text(context, fit(MenuKit.uiBold("@" + channel.replace(" ", "")), w - 24), x + 12, ty, alpha(TEXT, ease));
			}
			List<Text> lines = MenuKit.wrap(tr(), title == null ? "" : title, MenuKit::ui, w - 24);
			for (int i = 0; i < Math.min(2, lines.size()); i++) {
				text(context, lines.get(i), x + 12, ty + 13 + i * 10, alpha(0xFFE0E0E8, ease));
			}
		}
		if (titleBelow) {
			// a small window: the title gets its own row under the buttons
			Text t = MenuKit.small(title == null ? "Video Player" : title);
			text(context, fit(t, w - 24), x + 12, y + 32, alpha(TEXT, ease));
		}
		drawControls(context, x, y, w, h, p, mouseX, mouseY, ease, live, full);
	}

	private static void drawControls(DrawContext context, int x, int y, int w, int h, VideoPlayer p, int mouseX, int mouseY, float ease, boolean live,
			boolean full) {
		boolean isLive = p != null && p.duration() >= 9_000_000;
		int accent = accent();
		lineX = x + 12;
		lineW = w - 24;
		barY = y + h - 34;
		boolean overLine = VeloUi.inside(mouseX, mouseY, lineX - 2, barY - 5, lineW + 4, 11) || dragKind == 3;
		lineHover = approach(lineHover, overLine ? 1 : 0, 0.15f);
		float th = 3 + lineHover * 2;
		VeloDraw.fillRounded(context, lineX, barY - th / 2f, lineW, th, th / 2f, alpha(0x40FFFFFF, ease));
		if (p != null && !isLive && p.duration() > 0) {
			float head = (float) (lineX + lineW * Math.min(1, p.position() / p.duration()));
			VeloDraw.fillRounded(context, lineX, barY - th / 2f, Math.max(th, head - lineX), th, th / 2f, alpha(accent, ease));
			if (lineHover > 0.05f) {
				VeloDraw.fillCircle(context, head, barY, 2.5f + lineHover * 3f, alpha(0xFFFFFFFF, ease * lineHover));
			}
			if (overLine && dragKind != 3 && live) {
				double at = Math.max(0, Math.min(1, (mouseX - lineX) / (double) lineW)) * p.duration();
				Text tip = MenuKit.mono(clock(at));
				int tw = width(tip) + 10;
				int tx = Math.max(x + 4, Math.min(x + w - tw - 4, mouseX - tw / 2));
				VeloDraw.fillRounded(context, tx, barY - 22, tw, 14, 7, 0xF0101016);
				text(context, tip, tx + 5, barY - 19, TEXT);
			}
		}
		int row = barY + 14;
		boolean playing = p != null && p.playing();
		add(live, roundButton(context, x + 22, row, 10, mouseX, mouseY, ease, false,
				(cx, cy, c) -> MenuKit.icon(context, playing ? "pause" : "play", cx - 5, cy - 5, 10, c), YouTubeOverlay::togglePause));
		int nextX = x + 22;
		if (shortPlaying) {
			nextX += 24;
			add(live, roundButton(context, nextX, row, 10, mouseX, mouseY, ease, false, (cx, cy, c) -> drawChevron(context, cx, cy, false, c), () -> flick(1)));
		}
		boolean muted = p != null && p.muted();
		float volume = p == null ? 0.8f : p.volume();
		int muteX = nextX + 24;
		add(live, roundButton(context, muteX, row, 10, mouseX, mouseY, ease, false,
				(cx, cy, c) -> MenuKit.icon(context, muted || volume <= 0.001f ? "volume_off" : "volume", cx - 5, cy - 5, 10, c), () -> {
					if (p != null) {
						p.setMuted(!p.muted());
					}
				}));
		boolean overVol = VeloUi.inside(mouseX, mouseY, muteX - 10, row - 10, 80, 20) || dragKind == 4;
		volHover = approach(volHover, overVol ? 1 : 0, 0.15f);
		volW = Math.round(50 * volHover);
		volX = muteX + 14;
		int timeX = muteX + 16 + (volW > 2 ? volW + 8 : 0);
		if (volW > 2) {
			VeloDraw.fillRounded(context, volX, row - 1, volW, 3, 1.5f, alpha(0x40FFFFFF, ease));
			VeloDraw.fillRounded(context, volX, row - 1, muted ? 0 : volW * volume, 3, 1.5f, alpha(0xFFFFFFFF, ease));
			VeloDraw.fillCircle(context, volX + (muted ? 0 : volW * volume), row + 0.5f, 3.5f, alpha(0xFFFFFFFF, ease));
		}
		if (isLive) {
			Text liveT = MenuKit.small("LIVE");
			VeloDraw.fillRounded(context, timeX, row - 6, width(liveT) + 12, 12, 6, alpha(0xFFE5242B, ease));
			VeloDraw.fillCircle(context, timeX + 5f, row, 1.8f, alpha(0xFFFFFFFF, ease));
			text(context, liveT, timeX + 9, row - 3, alpha(0xFFFFFFFF, ease));
		} else if (p != null && w > 200) {
			Text time = MenuKit.mono(clock(p.position()) + " / " + clock(p.duration()));
			text(context, time, timeX, row - 3, alpha(0xFFE6E6EC, ease));
		}
		add(live, roundButton(context, x + w - 22, row, 10, mouseX, mouseY, ease, false, (cx, cy, c) -> MenuKit.icon(context, "expand", cx - 5, cy - 5, 10, c),
				() -> mode = full ? Mode.NORMAL : Mode.FULLSCREEN));
	}

	// ---------------------------------------------------------------- browser (search, Shorts, Twitch, files)

	private static void drawBrowser(DrawContext context, int x, int y, int w, int h, int mouseX, int mouseY, long now) {
		int radius = mode == Mode.FULLSCREEN ? 0 : 12;
		VeloDraw.fillRounded(context, x, y, w, h, radius, 0xFA0B0B0F);
		int pad = 10;
		// header: the sources as a segmented control (names when there's room), window buttons on the right
		int src = source();
		boolean labels = w >= 360;
		int segW = labels ? 0 : 26;
		int[] widths = new int[SOURCES.length];
		int total = 0;
		for (int i = 0; i < SOURCES.length; i++) {
			widths[i] = labels ? width(MenuKit.small(SOURCES[i])) + 30 : segW;
			total += widths[i];
		}
		int sx = x + pad;
		int sy = y + 9;
		VeloDraw.fillRounded(context, sx, sy, total + 4, 22, 11, SURFACE_2);
		int cx = sx + 2;
		for (int i = 0; i < SOURCES.length; i++) {
			boolean on = i == src;
			boolean hot = !on && VeloUi.inside(mouseX, mouseY, cx, sy + 2, widths[i], 18);
			if (on || hot) {
				VeloDraw.fillRounded(context, cx, sy + 2, widths[i], 18, 9, on ? SURFACE_3 : 0x10FFFFFF);
			}
			drawSourceGlyph(context, i, labels ? cx + 9 : cx + (widths[i] - 12) / 2, sy + 5, on ? 1f : 0.55f);
			if (labels) {
				text(context, MenuKit.small(SOURCES[i]), cx + 24, sy + 8, on ? TEXT : MUTED);
			}
			int pick = i;
			HITS.add(new VeloUi.Hit(cx, sy + 2, widths[i], 18, () -> setSource(pick)));
			cx += widths[i];
		}
		int by = y + 20;
		int bx = x + w - pad - 10;
		HITS.add(roundButton(context, bx, by, 10, mouseX, mouseY, 1f, true, (gx, gy, c) -> MenuKit.icon(context, "close", gx - 5, gy - 5, 10, c), () -> {
			stop();
			MinecraftClient.getInstance().setScreen(null);
		}));
		HITS.add(roundButton(context, bx - 24, by, 10, mouseX, mouseY, 1f, false,
				(gx, gy, c) -> drawWindowGlyph(context, gx, gy, mode == Mode.MAXIMIZED ? 1 : 2, c), () -> mode = mode == Mode.MAXIMIZED ? Mode.NORMAL : Mode.MAXIMIZED));
		HITS.add(roundButton(context, bx - 48, by, 10, mouseX, mouseY, 1f, false, (gx, gy, c) -> drawWindowGlyph(context, gx, gy, 0, c),
				() -> mode = Mode.MINIMIZED));
		if (view != null) {
			HITS.add(roundButton(context, bx - 72, by, 10, mouseX, mouseY, 1f, false, (gx, gy, c) -> MenuKit.icon(context, "play", gx - 5, gy - 5, 10, c),
					() -> showResults = false));
		}
		int headerRight = view != null ? bx - 84 : bx - 60;
		if (sx + total + 8 > headerRight) {
			// a narrow window: buttons won't fit next to the control - nothing else to do, the control simply sits under them
		}

		int top = y + 40;
		if (files) {
			drawFiles(context, x + pad, top, w - pad * 2, h - (top - y) - pad, mouseX, mouseY);
			return;
		}
		// the search field (the screen's text field takes the keys; it's drawn here in the Velo font)
		int fh = 22;
		boolean focused = YouTubeScreen.fieldFocused();
		boolean hotField = VeloUi.inside(mouseX, mouseY, x + pad, top, w - pad * 2, fh);
		VeloDraw.fillRounded(context, x + pad, top, w - pad * 2, fh, 12, focused ? SURFACE_3 : hotField ? 0xFF1F1F28 : SURFACE_2);
		VeloDraw.strokeRounded(context, x + pad, top, w - pad * 2, fh, 12, focused ? alpha(accent(), 0.8f) : HAIR);
		drawSearchGlyph(context, x + pad + 14, top + fh / 2, focused ? TEXT : MUTED);
		String typed = YouTubeScreen.fieldText();
		int textX = x + pad + 26;
		int textW = w - pad * 2 - 36;
		if (typed.isEmpty() && !focused) {
			text(context, fit(MenuKit.small(placeholder()), textW), textX, top + 8, MUTED);
		} else {
			Text t = MenuKit.ui(typed);
			int tw = width(t);
			// long input scrolls: show the end
			String shown = typed;
			while (tw > textW && shown.length() > 1) {
				shown = shown.substring(1);
				tw = width(MenuKit.ui(shown));
			}
			text(context, MenuKit.ui(shown), textX, top + 8, TEXT);
			if (focused && (now / 500_000_000L) % 2 == 0) {
				context.fill(textX + tw + 1, top + 6, textX + tw + 2, top + 18, TEXT);
			}
		}
		searchFieldRect = new int[] {x + pad, top, w - pad * 2, fh};
		HITS.add(new VeloUi.Hit(x + pad, top, w - pad * 2, fh, YouTubeScreen::focusField));
		int listTop = top + fh + 10;
		drawResults(context, x + pad, listTop, w - pad * 2, h - (listTop - y) - pad, mouseX, mouseY, now);
	}

	private static String placeholder() {
		return switch (source()) {
			case 1 -> "A topic for Shorts - or nothing for the feed";
			case 2 -> "A Twitch channel or link";
			default -> "Search YouTube, or paste any video link";
		};
	}

	/** Results as cards: thumbnail, duration / LIVE, two lines of title, channel. Skeletons while loading. */
	private static void drawResults(DrawContext context, int x, int y, int w, int h, int mouseX, int mouseY, long now) {
		List<YtDlp.Result> list = results;
		String status = searchStatus;
		int cols = Math.max(1, Math.min(4, (w + 10) / 170));
		int cardW = (w - (cols - 1) * 10) / cols;
		int thumbH = Math.round(cardW * 9 / 16f);
		int cardH = thumbH + 40;
		if (list.isEmpty()) {
			if (searching) {
				// skeleton cards, gently pulsing
				float pulse = 0.5f + 0.5f * (float) Math.sin(now / 1e9 * 3.0);
				int rows = Math.max(1, h / (cardH + 10) + 1);
				context.enableScissor(x, y, x + w, y + h);
				for (int i = 0; i < cols * rows; i++) {
					int cx = x + (i % cols) * (cardW + 10);
					int cy = y + (i / cols) * (cardH + 10);
					int c = VeloAnimLerp(0xFF15151C, 0xFF1D1D26, pulse);
					VeloDraw.fillRounded(context, cx, cy, cardW, thumbH, 8, c);
					VeloDraw.fillRounded(context, cx, cy + thumbH + 7, cardW - 20, 7, 3, c);
					VeloDraw.fillRounded(context, cx, cy + thumbH + 20, cardW / 2, 6, 3, c);
				}
				context.disableScissor();
				if (status != null) {
					Text st = MenuKit.small(status);
					text(context, st, x + (w - width(st)) / 2, y + h - 12, MUTED);
				}
				return;
			}
			boolean bad = status != null && (status.startsWith("Search failed") || status.startsWith("Couldn't") || status.startsWith("These"));
			String main = status != null ? status : switch (source()) {
				case 1 -> "Shorts start right away - or type a topic";
				case 2 -> "Type a channel to watch it live";
				default -> "Find something to watch";
			};
			String sub = status != null ? null : switch (source()) {
				case 2 -> "Live streams, videos and clips - or paste a Twitch link";
				case 1 -> "Scroll, arrow keys or J / K for the next one";
				default -> "Search YouTube or paste a link - YouTube, Twitch or any video";
			};
			drawMessage(context, x, y, w, h, bad ? "!" : null, main, bad ? 0xFFFF6B6B : TEXT);
			if (sub != null) {
				Text s = MenuKit.small(sub);
				text(context, fit(s, w - 20), x + (w - Math.min(width(s), w - 20)) / 2, y + h / 2 + 22, MUTED);
			}
			return;
		}
		if (w < 420) {
			drawResultList(context, list, x, y, w, h, mouseX, mouseY);
			return;
		}
		int rowsVisible = Math.max(1, h / (cardH + 10));
		int rowsTotal = (list.size() + cols - 1) / cols;
		scroll = Math.max(0, Math.min(scroll, Math.max(0, rowsTotal - rowsVisible)));
		context.enableScissor(x, y, x + w, y + h);
		for (int i = scroll * cols; i < list.size() && i < (scroll + rowsVisible + 1) * cols; i++) {
			YtDlp.Result r = list.get(i);
			int cx = x + (i % cols) * (cardW + 10);
			int cy = y + (i / cols - scroll) * (cardH + 10);
			boolean hot = VeloUi.inside(mouseX, mouseY, cx, cy, cardW, cardH) && mouseY < y + h;
			if (hot) {
				VeloDraw.fillRounded(context, cx - 4, cy - 4, cardW + 8, cardH + 6, 10, 0x12FFFFFF);
			}
			drawThumb(context, r, cx, cy, cardW, thumbH, hot);
			List<Text> lines = MenuKit.wrap(tr(), r.title(), MenuKit::ui, cardW - 2);
			for (int k = 0; k < Math.min(2, lines.size()); k++) {
				Text line = k == 1 && lines.size() > 2 ? fit(lines.get(1), cardW - 12) : lines.get(k);
				text(context, line, cx, cy + thumbH + 6 + k * 10, TEXT);
			}
			text(context, fit(MenuKit.small(r.channel() == null ? "" : r.channel()), cardW), cx, cy + thumbH + 29, MUTED);
			if (cy + cardH <= y + h + 4) {
				HITS.add(new VeloUi.Hit(cx, cy, cardW, cardH, () -> play(r.id(), r.title(), r.channel())));
			}
		}
		context.disableScissor();
		scrollbar(context, x + w + 3, y, h, rowsVisible, rowsTotal);
	}

	/** Narrow windows: a compact list - small thumbnail, title, channel. */
	private static void drawResultList(DrawContext context, List<YtDlp.Result> list, int x, int y, int w, int h, int mouseX, int mouseY) {
		int rowH = 48;
		int tw = 76;
		int th = 43;
		int visible = Math.max(1, h / rowH);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, list.size() - visible)));
		context.enableScissor(x, y, x + w, y + h);
		for (int i = scroll; i < list.size() && i <= scroll + visible; i++) {
			YtDlp.Result r = list.get(i);
			int ry = y + (i - scroll) * rowH;
			boolean hot = VeloUi.inside(mouseX, mouseY, x, ry, w, rowH - 2) && mouseY < y + h;
			if (hot) {
				VeloDraw.fillRounded(context, x - 3, ry - 2, w + 6, rowH - 1, 8, 0x12FFFFFF);
			}
			drawThumb(context, r, x, ry + 1, tw, th, hot);
			int tx = x + tw + 9;
			List<Text> lines = MenuKit.wrap(tr(), r.title(), MenuKit::ui, w - tw - 10);
			for (int k = 0; k < Math.min(2, lines.size()); k++) {
				text(context, k == 1 && lines.size() > 2 ? fit(lines.get(1), w - tw - 20) : lines.get(k), tx, ry + 3 + k * 10, TEXT);
			}
			text(context, fit(MenuKit.small(r.channel() == null ? "" : r.channel()), w - tw - 10), tx, ry + 27, MUTED);
			if (ry + rowH <= y + h + 2) {
				HITS.add(new VeloUi.Hit(x, ry, w, rowH - 2, () -> play(r.id(), r.title(), r.channel())));
			}
		}
		context.disableScissor();
		scrollbar(context, x + w + 3, y, h, visible, list.size());
	}

	private static void drawThumb(DrawContext context, YtDlp.Result r, int x, int y, int w, int h, boolean hot) {
		Identifier t = thumb(r);
		VeloDraw.fillRounded(context, x, y, w, h, 6, 0xFF15151C);
		if (t != null) {
			context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, t, x, y, 0f, 0f, w, h, 320, 180, 320, 180);
			roundCorners(context, x, y, w, h, hot ? 0xFF16161D : 0xFF0B0B0F);
		}
		if (r.live()) {
			Text l = MenuKit.small("LIVE");
			VeloDraw.fillRounded(context, x + 4, y + 4, width(l) + 12, 11, 5, 0xFFE5242B);
			VeloDraw.fillCircle(context, x + 9f, y + 9.5f, 1.6f, 0xFFFFFFFF);
			text(context, l, x + 13, y + 6, 0xFFFFFFFF);
		} else if (r.seconds() > 0) {
			Text d = MenuKit.small(clock(r.seconds()));
			int dw = width(d) + 8;
			VeloDraw.fillRounded(context, x + w - dw - 3, y + h - 14, dw, 11, 4, 0xD0000000);
			text(context, d, x + w - dw + 1, y + h - 12, 0xFFFFFFFF);
		}
	}

	private static void scrollbar(DrawContext context, int x, int y, int h, int visible, int total) {
		if (total > visible) {
			int barH = Math.max(20, h * visible / total);
			int max = Math.max(1, total - visible);
			int barY = y + (h - barH) * Math.min(scroll, max) / max;
			VeloDraw.fillRounded(context, x, barY, 3, barH, 1, 0x40FFFFFF);
		}
	}

	/** Files: open a video from the PC, and the ones played lately. */
	private static void drawFiles(DrawContext context, int x, int y, int w, int h, int mouseX, int mouseY) {
		boolean hot = VeloUi.inside(mouseX, mouseY, x, y, w, 46);
		VeloDraw.fillRounded(context, x, y, w, 46, 10, hot ? 0xFF1F1F28 : SURFACE_2);
		VeloDraw.strokeRounded(context, x, y, w, 46, 10, hot ? alpha(accent(), 0.7f) : HAIR);
		VeloDraw.fillRounded(context, x + 10, y + 9, 28, 28, 8, alpha(accent(), 0.2f));
		MenuKit.icon(context, "mods_folder", x + 17, y + 16, 14, accent());
		text(context, MenuKit.uiBold(fileStatus != null ? fileStatus : "Open a video file"), x + 48, y + 13, TEXT);
		text(context, fit(MenuKit.small("MP4, MKV, WEBM, MOV, AVI - or paste a file path in the search"), w - 58), x + 48, y + 26, MUTED);
		HITS.add(new VeloUi.Hit(x, y, w, 46, YouTubeOverlay::pickFile));
		List<java.nio.file.Path> recent = recentFiles();
		int ry = y + 58;
		if (recent.isEmpty()) {
			Text none = MenuKit.small("Videos you open show up here.");
			text(context, none, x + (w - width(none)) / 2, ry + 20, MUTED);
			return;
		}
		text(context, MenuKit.small("RECENT"), x + 2, ry, MUTED);
		ry += 14;
		context.enableScissor(x, ry, x + w, y + h);
		for (java.nio.file.Path f : recent) {
			if (ry > y + h) {
				break;
			}
			boolean rh = VeloUi.inside(mouseX, mouseY, x, ry, w, 30) && mouseY < y + h;
			if (rh) {
				VeloDraw.fillRounded(context, x, ry, w, 30, 8, 0x12FFFFFF);
			}
			MenuKit.icon(context, "play", x + 10, ry + 10, 10, rh ? accent() : MUTED);
			text(context, fit(MenuKit.uiBold(f.getFileName().toString()), w - 40), x + 28, ry + 6, TEXT);
			java.nio.file.Path parent = f.getParent();
			text(context, fit(MenuKit.small(parent == null ? "" : parent.toString()), w - 40), x + 28, ry + 18, MUTED);
			HITS.add(new VeloUi.Hit(x, ry, w, 30, () -> playFile(f)));
			ry += 34;
		}
		context.disableScissor();
	}

	// ---------------------------------------------------------------- small pieces

	@FunctionalInterface
	private interface Glyph {
		void draw(int cx, int cy, int color);
	}

	/** A round button centred on (cx, cy): a soft disc when hovered (red for close). */
	private static VeloUi.Hit roundButton(DrawContext context, int cx, int cy, int r, int mouseX, int mouseY, float a, boolean danger, Glyph glyph,
			Runnable action) {
		boolean hot = VeloUi.inside(mouseX, mouseY, cx - r, cy - r, r * 2, r * 2);
		VeloDraw.fillCircle(context, cx, cy, r, alpha(hot ? (danger ? 0xFFD63A3A : 0x40FFFFFF) : 0x26000000, a));
		glyph.draw(cx, cy, alpha(hot ? 0xFFFFFFFF : 0xFFE4E4EA, a));
		return new VeloUi.Hit(cx - r, cy - r, r * 2, r * 2, action);
	}

	/** 0 = minimise (line), 1 = restore (two boxes), 2 = maximise (box). */
	private static void drawWindowGlyph(DrawContext context, int cx, int cy, int kind, int c) {
		switch (kind) {
			case 0 -> context.fill(cx - 4, cy + 2, cx + 4, cy + 3, c);
			case 1 -> {
				VeloDraw.strokeRect(context, cx - 2, cy - 4, 6, 6, c);
				VeloDraw.strokeRect(context, cx - 4, cy - 2, 6, 6, c);
			}
			default -> VeloDraw.strokeRect(context, cx - 4, cy - 4, 8, 8, c);
		}
	}

	private static void drawSearchGlyph(DrawContext context, int cx, int cy, int c) {
		VeloDraw.strokeRounded(context, cx - 4, cy - 4, 7, 7, 3, c);
		context.fill(cx + 2, cy + 2, cx + 4, cy + 4, c);
		context.fill(cx + 3, cy + 3, cx + 5, cy + 5, c);
	}

	private static void drawChevron(DrawContext context, int cx, int cy, boolean up, int c) {
		for (int i = 0; i < 4; i++) {
			int row = up ? cy - 2 + i : cy + 1 - i;
			context.fill(cx - i, row, cx + 1 + i, row + 1, c);
		}
	}

	/** The small mark of a source: YouTube, Shorts, Twitch or a file. */
	private static void drawSourceGlyph(DrawContext context, int source, int x, int y, float a) {
		int alpha = Math.round(255 * a);
		switch (source) {
			case 1 -> drawShortsLogo(context, x + 1, y, alpha);
			case 2 -> drawTwitchLogo(context, x, y, alpha);
			case 3 -> MenuKit.icon(context, "mods_folder", x, y, 12, VeloUi.withAlpha(MenuKit.accent(), alpha));
			default -> drawLogo(context, x - 1, y + 1, alpha);
		}
	}

	/** Rounds a picture's corners by painting the background colour into them (textures can't be clipped round). */
	private static void roundCorners(DrawContext context, int x, int y, int w, int h, int bg) {
		int[] cut = {4, 2, 1, 1};
		for (int i = 0; i < cut.length; i++) {
			context.fill(x, y + i, x + cut[i], y + i + 1, bg);
			context.fill(x + w - cut[i], y + i, x + w, y + i + 1, bg);
			context.fill(x, y + h - 1 - i, x + cut[i], y + h - i, bg);
			context.fill(x + w - cut[i], y + h - 1 - i, x + w, y + h - i, bg);
		}
	}

	private static int VeloAnimLerp(int a, int b, float t) {
		return net.veloclient.velo.client.gui.widget.VeloAnim.lerpArgb(a, b, t);
	}

	/** A centred message with an optional round badge above it. */
	private static void drawMessage(DrawContext context, int x, int y, int w, int h, String badge, String message, int color) {
		int cy = y + h / 2;
		if (badge != null) {
			VeloDraw.fillCircle(context, x + w / 2f, cy - 18, 11f, alpha(color, 0.18f));
			Text b = MenuKit.uiBold(badge);
			text(context, b, x + (w - width(b)) / 2, cy - 22, color);
		}
		List<Text> lines = MenuKit.wrap(tr(), message, MenuKit::uiBold, w - 32);
		for (int i = 0; i < Math.min(3, lines.size()); i++) {
			Text l = lines.get(i);
			text(context, l, x + (w - width(l)) / 2, cy + i * 11 - (badge != null ? 0 : 4), color);
		}
	}

	/** Three dots bouncing in the accent colour. */
	private static void drawLoader(DrawContext context, int cx, int cy, long now, int color) {
		double t = now / 1e9;
		for (int i = 0; i < 3; i++) {
			double phase = t * 6 - i * 0.7;
			float lift = (float) Math.max(0, Math.sin(phase)) * 5f;
			VeloDraw.fillCircle(context, cx - 10 + i * 10, cy - lift, 3f, alpha(color, 0.55f + 0.45f * (lift / 5f)));
		}
	}

	private static void drawShortsLogo(DrawContext context, int x, int y, int a) {
		VeloDraw.fillRounded(context, x, y, 10, 12, 3, VeloUi.withAlpha(0xFFFF2D3C, a));
		int white = VeloUi.withAlpha(0xFFFFFFFF, a);
		for (int i = 0; i < 3; i++) {
			context.fill(x + 4 + i, y + 3 + i, x + 5 + i, y + 9 - i, white);
		}
	}

	private static void drawTwitchLogo(DrawContext context, int x, int y, int a) {
		int purple = VeloUi.withAlpha(0xFF9146FF, a);
		int white = VeloUi.withAlpha(0xFFFFFFFF, a);
		context.fill(x + 1, y, x + 11, y + 9, purple);
		context.fill(x, y + 1, x + 1, y + 8, purple);
		context.fill(x + 3, y + 9, x + 6, y + 10, purple);
		context.fill(x + 3, y + 10, x + 4, y + 12, purple);
		context.fill(x + 4, y + 2, x + 5, y + 6, white);
		context.fill(x + 7, y + 2, x + 8, y + 6, white);
	}

	private static void drawLogo(DrawContext context, int x, int y, int a) {
		VeloDraw.fillRounded(context, x, y, 14, 10, 3, VeloUi.withAlpha(0xFFFF2D3C, a));
		int white = VeloUi.withAlpha(0xFFFFFFFF, a);
		for (int i = 0; i < 4; i++) {
			context.fill(x + 5 + i, y + 2 + i, x + 6 + i, y + 8 - i, white);
		}
	}

	private static void add(boolean live, VeloUi.Hit hit) {
		if (live) {
			HITS.add(hit);
		}
	}

	private static float approach(float value, float target, float step) {
		return value < target ? Math.min(target, value + step) : Math.max(target, value - step);
	}

	private static void setSource(int source) {
		if (source() != source) {
			twitch = source == 2;
			shorts = source == 1;
			files = source == 3;
			results = List.of();
			searchStatus = null;
			scroll = 0;
			YouTubeModule.saveSoon();
			if (shorts) {
				loadShorts(""); // straight into the feed; typing a topic gives Shorts about it
			}
		}
	}

	/** yt-dlp / FFmpeg errors as people read them: "This video is not available", not "ERROR: [youtube] abc123: ...". */
	static String readable(String error) {
		String e = error.replaceAll("(?s)^.*?ERROR:\\s*", "").replaceAll("^\\[[^\\]]+\\]\\s*[A-Za-z0-9_-]+:\\s*", "");
		int nl = e.indexOf('\n');
		if (nl > 0) {
			e = e.substring(0, nl);
		}
		e = e.replaceAll("\\s*\\(caused by.*$", "").replaceAll("\\s*Use --cookies.*$", "").trim();
		if (e.length() > 140) {
			e = e.substring(0, 137) + "...";
		}
		return e.isEmpty() ? "Couldn't play this video" : e;
	}

	// ---------------------------------------------------------------- local files and other links

	private static final List<String> VIDEO_EXTENSIONS = List.of("mp4", "mkv", "webm", "mov", "avi", "m4v", "flv", "wmv", "ts", "mpg", "mpeg", "gif",
			"mp3", "m4a", "ogg", "wav", "flac");
	private static volatile String fileStatus;

	private static java.nio.file.Path recentFile() {
		return net.veloclient.velo.config.VeloPaths.root().resolve("video-player-recent.txt");
	}

	static List<java.nio.file.Path> recentFiles() {
		try {
			java.nio.file.Path f = recentFile();
			if (!java.nio.file.Files.isRegularFile(f)) {
				return List.of();
			}
			List<java.nio.file.Path> out = new ArrayList<>();
			for (String line : java.nio.file.Files.readAllLines(f)) {
				if (!line.isBlank()) {
					java.nio.file.Path p = java.nio.file.Path.of(line.trim());
					if (java.nio.file.Files.isRegularFile(p)) {
						out.add(p);
					}
				}
			}
			return out;
		} catch (Exception e) {
			return List.of();
		}
	}

	private static void remember(java.nio.file.Path file) {
		try {
			List<String> lines = new ArrayList<>();
			lines.add(file.toAbsolutePath().toString());
			for (java.nio.file.Path p : recentFiles()) {
				String s = p.toAbsolutePath().toString();
				if (!lines.contains(s) && lines.size() < 20) {
					lines.add(s);
				}
			}
			java.nio.file.Files.createDirectories(recentFile().getParent());
			java.nio.file.Files.write(recentFile(), lines);
		} catch (Exception ignored) {
			// not remembered then
		}
	}

	/** The system's file picker (off the render thread), then plays the picked file. */
	static void pickFile() {
		if (fileStatus != null) {
			return;
		}
		fileStatus = "Choose a video...";
		Thread.ofVirtual().name("velo-video-pick").start(() -> {
			try {
				java.nio.file.Path f = net.veloclient.velo.client.gui.NativeFileDialog.pickFile("Open a video", "Videos",
						VIDEO_EXTENSIONS.toArray(new String[0]));
				if (f != null) {
					MinecraftClient.getInstance().execute(() -> playFile(f));
				}
			} catch (Exception e) {
				VeloClient.LOGGER.warn("Video file picker failed", e);
			} finally {
				fileStatus = null;
			}
		});
	}

	/** Plays a video (or sound) file from this PC. */
	static void playFile(java.nio.file.Path file) {
		stop();
		title = file.getFileName().toString();
		java.nio.file.Path parent = file.getParent();
		channel = parent == null ? null : parent.getFileName() == null ? parent.toString() : parent.getFileName().toString();
		showResults = false;
		remember(file);
		view = new VideoView(file.toAbsolutePath().toString(), true);
	}

	/** A link straight to a media file (or an HLS playlist): FFmpeg opens it itself. */
	private static boolean directMedia(String link) {
		String path = link.toLowerCase(Locale.ROOT).replaceAll("[?#].*$", "");
		int dot = path.lastIndexOf('.');
		return dot > 0 && (VIDEO_EXTENSIONS.contains(path.substring(dot + 1)) || path.endsWith(".m3u8"));
	}

	/** Plays a direct media link. */
	private static void playDirect(String url) {
		stop();
		String name = url.replaceAll("[?#].*$", "");
		title = name.substring(name.lastIndexOf('/') + 1);
		channel = URI.create(url).getHost();
		showResults = false;
		view = new VideoView(url, true);
	}

	private static String clock(double seconds) {
		int s = (int) Math.floor(Math.max(0, seconds));
		return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
	}

	// ---------------------------------------------------------------- input (only while the screen is open)

	private static final int EDGE = 5;

	/** Which edges of the window the point grabs (1 left, 2 right, 4 top, 8 bottom), NORMAL mode only. */
	private static int edges(double mx, double my, int[] r) {
		if (mode != Mode.NORMAL || !VeloUi.inside(mx, my, r[0] - 2, r[1] - 2, r[2] + 4, r[3] + 4)) {
			return 0;
		}
		int e = 0;
		if (mx < r[0] + EDGE) {
			e |= 1;
		} else if (mx >= r[0] + r[2] - EDGE) {
			e |= 2;
		}
		if (my < r[1] + EDGE) {
			e |= 4;
		} else if (my >= r[1] + r[3] - EDGE) {
			e |= 8;
		}
		// the corner mark is a bigger target
		if (mx >= r[0] + r[2] - 12 && my >= r[1] + r[3] - 12) {
			e = 2 | 8;
		}
		return e;
	}

	/** True when the click was on the overlay. */
	static boolean mouseClicked(double mx, double my, int sw, int sh) {
		for (VeloUi.Hit hit : List.copyOf(HITS)) {
			if (hit.contains(mx, my)) {
				hit.action().run();
				return true;
			}
		}
		int[] r = lastRect;
		int e = edges(mx, my, r);
		if (e != 0) {
			startDrag(2, mx, my, r);
			grabEdges = e;
			return true;
		}
		if (!VeloUi.inside(mx, my, r[0], r[1], r[2], r[3])) {
			return false;
		}
		VideoPlayer p = view == null ? null : view.player();
		if (p != null && !showResults && VeloUi.inside(mx, my, lineX - 2, barY - 5, lineW + 4, 11) && chrome > 0.5f && p.duration() < 9_000_000) {
			dragKind = 3;
			p.seek(Math.max(0, Math.min(1, (mx - lineX) / lineW)) * p.duration());
			return true;
		}
		if (p != null && volW > 2 && VeloUi.inside(mx, my, volX - 3, barY + 6, volW + 6, 12)) {
			dragKind = 4;
			p.setMuted(false);
			p.setVolume((float) Math.max(0, Math.min(1, (mx - volX) / volW)));
			return true;
		}
		if (mode == Mode.FULLSCREEN || mode == Mode.MAXIMIZED) {
			dragKind = 6; // a click toggles play, no moving
		} else {
			startDrag(5, mx, my, r); // a click toggles play, a drag moves the window
		}
		return true;
	}

	private static void startDrag(int kind, double mx, double my, int[] r) {
		dragKind = kind;
		grabX = mx;
		grabY = my;
		grabRect = r.clone();
	}

	static boolean mouseDragged(double mx, double my, int sw, int sh) {
		VideoPlayer p = view == null ? null : view.player();
		switch (dragKind) {
			case 5 -> {
				if (Math.abs(mx - grabX) + Math.abs(my - grabY) > 3) {
					dragKind = 1;
				}
			}
			case 1 -> {
				int w = grabRect[2];
				int h = grabRect[3];
				int nx = (int) Math.round(Math.max(0, Math.min(sw - (mode == Mode.MINIMIZED ? PILL_W : w), grabRect[0] + mx - grabX)));
				int ny = (int) Math.round(Math.max(0, Math.min(sh - (mode == Mode.MINIMIZED ? PILL_H : h), grabRect[1] + my - grabY)));
				fx = nx / (float) sw;
				fy = ny / (float) sh;
			}
			case 2 -> {
				double dx = mx - grabX;
				double dy = my - grabY;
				int left = grabRect[0];
				int top = grabRect[1];
				int right = grabRect[0] + grabRect[2];
				int bottom = grabRect[1] + grabRect[3];
				if ((grabEdges & 1) != 0) {
					left = (int) Math.round(Math.max(0, Math.min(right - MIN_W, left + dx)));
				}
				if ((grabEdges & 2) != 0) {
					right = (int) Math.round(Math.min(sw, Math.max(left + MIN_W, right + dx)));
				}
				if ((grabEdges & 4) != 0) {
					top = (int) Math.round(Math.max(0, Math.min(bottom - MIN_H, top + dy)));
				}
				if ((grabEdges & 8) != 0) {
					bottom = (int) Math.round(Math.min(sh, Math.max(top + MIN_H, bottom + dy)));
				}
				fx = left / (float) sw;
				fy = top / (float) sh;
				fw = (right - left) / (float) sw;
				fh = (bottom - top) / (float) sh;
			}
			case 3 -> {
				if (p != null) {
					p.seek(Math.max(0, Math.min(1, (mx - lineX) / lineW)) * p.duration());
				}
			}
			case 4 -> {
				if (p != null && volW > 0) {
					p.setVolume((float) Math.max(0, Math.min(1, (mx - volX) / volW)));
				}
			}
			default -> {
				return false;
			}
		}
		return true;
	}

	static boolean mouseReleased() {
		boolean was = dragKind != 0;
		if (dragKind == 5 || dragKind == 6) {
			if (!showResults && view != null) {
				togglePause();
			}
		} else if (was) {
			YouTubeModule.saveSoon();
		}
		dragKind = 0;
		return was;
	}

	/** Mouse wheel: scrolls the results; over the video it changes the volume. */
	static boolean mouseScrolled(double mx, double my, double amount) {
		int[] r = lastRect;
		if (!VeloUi.inside(mx, my, r[0], r[1], r[2], r[3])) {
			return false;
		}
		if (showResults || view == null) {
			scroll += amount > 0 ? -1 : 1;
			return true;
		}
		if (shortPlaying) {
			flick(amount > 0 ? -1 : 1); // wheel down = next short
			return true;
		}
		VideoPlayer p = view.player();
		if (p != null) {
			p.setMuted(false);
			p.setVolume(Math.max(0, Math.min(1, p.volume() + (amount > 0 ? 0.05f : -0.05f))));
		}
		return true;
	}

	/** Keys while the screen is open and the search field isn't focused. True when handled. */
	static boolean keyPressed(int key) {
		VideoPlayer p = view == null ? null : view.player();
		if (key == net.veloclient.velo.client.util.Keys.ESCAPE && mode == Mode.FULLSCREEN) {
			mode = Mode.NORMAL;
			return true;
		}
		if (key == net.veloclient.velo.client.util.Keys.F && view != null) {
			mode = mode == Mode.FULLSCREEN ? Mode.NORMAL : Mode.FULLSCREEN;
			return true;
		}
		if (p == null) {
			return false;
		}
		if (key == net.veloclient.velo.client.util.Keys.SPACE) {
			p.toggle();
			return true;
		}
		if ((key == net.veloclient.velo.client.util.Keys.LEFT || key == net.veloclient.velo.client.util.Keys.RIGHT) && p.duration() < 9_000_000) {
			p.seek(Math.max(0, p.position() + (key == net.veloclient.velo.client.util.Keys.LEFT ? -5 : 5)));
			return true;
		}
		if (key == net.veloclient.velo.client.util.Keys.M) {
			p.setMuted(!p.muted());
			return true;
		}
		if (shortPlaying && (key == net.veloclient.velo.client.util.Keys.DOWN || key == net.veloclient.velo.client.util.Keys.J
				|| key == net.veloclient.velo.client.util.Keys.PAGE_DOWN)) {
			flick(1);
			return true;
		}
		if (shortPlaying && (key == net.veloclient.velo.client.util.Keys.UP || key == net.veloclient.velo.client.util.Keys.K
				|| key == net.veloclient.velo.client.util.Keys.PAGE_UP)) {
			flick(-1);
			return true;
		}
		if (key == net.veloclient.velo.client.util.Keys.UP || key == net.veloclient.velo.client.util.Keys.DOWN) {
			p.setMuted(false);
			p.setVolume(Math.max(0, Math.min(1, p.volume() + (key == net.veloclient.velo.client.util.Keys.UP ? 0.1f : -0.1f))));
			return true;
		}
		return false;
	}

	// ---------------------------------------------------------------- persistence

	static String save() {
		return String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f,%d,%d,%d,%d", fx, fy, fw, fh, mode == Mode.FULLSCREEN ? 0 : mode.ordinal(), twitch ? 1 : 0,
				shorts ? 1 : 0, files ? 1 : 0);
	}

	static void load(String state) {
		try {
			String[] p = state.split(",");
			fx = Float.parseFloat(p[0]);
			fy = Float.parseFloat(p[1]);
			fw = Float.parseFloat(p[2]);
			fh = Float.parseFloat(p[3]);
			int m = p.length > 4 ? Integer.parseInt(p[4]) : 0;
			mode = m >= 0 && m < Mode.values().length && m != Mode.FULLSCREEN.ordinal() ? Mode.values()[m] : Mode.NORMAL;
			twitch = p.length > 5 && p[5].equals("1");
			shorts = !twitch && p.length > 6 && p[6].equals("1");
			files = !twitch && !shorts && p.length > 7 && p[7].equals("1");
		} catch (RuntimeException ignored) {
			// defaults
		}
	}
}
