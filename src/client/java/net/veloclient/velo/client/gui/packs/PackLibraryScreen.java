package net.veloclient.velo.client.gui.packs;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.RemoteTextureLoader;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pack library: resource packs from Modrinth right in the game (Velo's answer to Resourcify). Search, sort, cards with
 * icon / author / downloads / description, and Install - the file for this Minecraft version (else the newest one:
 * textures work across versions) goes into the resource pack folder, checked against Modrinth's SHA-1. The pack screen
 * watches that folder, so an installed pack shows up under Available right away. Opened from the pack screen's toolbar.
 */
public final class PackLibraryScreen extends VeloWindow {

	private static final String API = "https://api.modrinth.com/v2";
	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL).build();
	private static final ExecutorService WORK = Executors.newVirtualThreadPerTaskExecutor();
	private static final String[] SORTS = {"relevance", "downloads", "follows", "updated", "newest"};
	private static final String[] SORT_LABELS = {"Relevance", "Downloads", "Follows", "Updated", "Newest"};
	private static final int CARD_H = 74;
	private static final int GAP = 10;
	private static final int PAGE = 30;

	/** One search result. */
	private record Hit(String projectId, String slug, String title, String author, String description, long downloads, String iconUrl) {
	}

	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private final List<Hit> results = new ArrayList<>();
	private final Map<String, Identifier> icons = new ConcurrentHashMap<>();
	/** projectId -> "Installing...", "Installed", or an error. */
	private final Map<String, String> installState = new ConcurrentHashMap<>();
	private TextFieldWidget search;
	private String query = "";
	private int sort;
	private int generation;
	private boolean loading;
	private boolean more;
	private String error;
	private int searchIn = 1;   // ticks until the (debounced) search runs; starts the first one right away
	private float scroll;
	private float scrollTarget;
	private int[] listArea = new int[4];
	private String status = "";
	private long statusAt;

	public PackLibraryScreen(Screen parent) {
		super(Text.literal("Pack Library"), 860, 500);
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		search = new TextFieldWidget(this.textRenderer, contentX() + 22, contentY() + 6, searchWidth() - 28, 12, Text.literal("Search"));
		search.setPlaceholder(VeloUi.placeholder("Search Modrinth resource packs"));
		search.setDrawsBackground(false);
		search.setText(query);
		search.setChangedListener(v -> {
			if (!v.equals(query)) {
				query = v;
				searchIn = 8;   // a short pause after typing, then one search
			}
		});
		addDrawableChild(search);
	}

	private int searchWidth() {
		return Math.max(160, Math.min(340, contentWidth() / 2));
	}

	// ---- Modrinth ----

	private void startSearch(boolean append) {
		int gen = append ? generation : ++generation;
		int offset = append ? results.size() : 0;
		if (!append) {
			results.clear();
			scrollTarget = 0;
			more = false;
		}
		loading = true;
		error = null;
		String text = query.strip();
		String version = net.veloclient.velo.client.util.ClientCompat.minecraftVersion();
		String sortKey = SORTS[sort];
		WORK.execute(() -> {
			try {
				String facets = "[[\"project_type:resourcepack\"]" + (version.isBlank() ? "" : ",[\"versions:" + version + "\"]") + "]";
				String url = API + "/search?limit=" + PAGE + "&offset=" + offset + "&index=" + sortKey + "&facets=" + enc(facets)
						+ (text.isEmpty() ? "" : "&query=" + enc(text));
				JsonObject json = JsonParser.parseString(get(url)).getAsJsonObject();
				List<Hit> page = new ArrayList<>();
				for (JsonElement e : json.getAsJsonArray("hits")) {
					JsonObject o = e.getAsJsonObject();
					page.add(new Hit(str(o, "project_id"), str(o, "slug"), str(o, "title"), str(o, "author"), str(o, "description"),
							o.has("downloads") ? o.get("downloads").getAsLong() : 0, str(o, "icon_url")));
				}
				int total = json.has("total_hits") ? json.get("total_hits").getAsInt() : 0;
				this.client.execute(() -> {
					if (gen != generation) {
						return;   // a newer search was started meanwhile
					}
					results.addAll(page);
					more = !page.isEmpty() && results.size() < total;
					loading = false;
				});
			} catch (Exception e) {
				this.client.execute(() -> {
					if (gen == generation) {
						loading = false;
						error = "Couldn't reach Modrinth - " + e.getMessage();
					}
				});
			}
		});
	}

	private void install(Hit hit) {
		if (installState.containsKey(hit.projectId()) && !installState.get(hit.projectId()).startsWith("Failed")) {
			return;
		}
		installState.put(hit.projectId(), "Installing...");
		String version = net.veloclient.velo.client.util.ClientCompat.minecraftVersion();
		WORK.execute(() -> {
			try {
				// The build for this Minecraft version; packs without one get their newest file (textures carry over).
				JsonArray versions = JsonParser.parseString(get(API + "/project/" + enc(hit.projectId()) + "/version?game_versions="
						+ enc("[\"" + version + "\"]"))).getAsJsonArray();
				if (versions.isEmpty()) {
					versions = JsonParser.parseString(get(API + "/project/" + enc(hit.projectId()) + "/version")).getAsJsonArray();
				}
				if (versions.isEmpty()) {
					throw new IllegalStateException("no files");
				}
				JsonObject file = primaryFile(versions.get(0).getAsJsonObject());
				String name = safeName(str(file, "filename"));
				if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
					throw new IllegalStateException("not a .zip pack");
				}
				Path dir = PackBridge.packDir();
				Files.createDirectories(dir);
				Path target = dir.resolve(name);
				if (!Files.exists(target)) {
					Path tmp = dir.resolve(name + ".part");
					download(str(file, "url"), tmp, file.has("hashes") ? str(file.getAsJsonObject("hashes"), "sha1") : null);
					Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
				}
				installState.put(hit.projectId(), "Installed");
				this.client.execute(() -> setStatus(hit.title() + " installed - it's under Available in the pack list."));
			} catch (Exception e) {
				installState.put(hit.projectId(), "Failed");
				this.client.execute(() -> setStatus("Couldn't install " + hit.title() + ": " + e.getMessage()));
			}
		});
	}

	private static JsonObject primaryFile(JsonObject version) {
		JsonArray files = version.getAsJsonArray("files");
		for (JsonElement f : files) {
			if (f.getAsJsonObject().has("primary") && f.getAsJsonObject().get("primary").getAsBoolean()) {
				return f.getAsJsonObject();
			}
		}
		return files.get(0).getAsJsonObject();
	}

	private static void download(String url, Path target, String sha1) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).header("User-Agent", userAgent()).GET().build();
		HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() != 200) {
			throw new IllegalStateException("download answered " + response.statusCode());
		}
		MessageDigest digest = MessageDigest.getInstance("SHA-1");
		try (InputStream in = response.body(); var out = Files.newOutputStream(target)) {
			byte[] buffer = new byte[64 * 1024];
			int read;
			while ((read = in.read(buffer)) > 0) {
				out.write(buffer, 0, read);
				digest.update(buffer, 0, read);
			}
		}
		if (sha1 != null && !sha1.equalsIgnoreCase(HexFormat.of().formatHex(digest.digest()))) {
			Files.deleteIfExists(target);
			throw new IllegalStateException("the download was damaged - try again");
		}
	}

	private static String get(String url) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("User-Agent", userAgent()).GET().build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		if (response.statusCode() != 200) {
			throw new IllegalStateException("Modrinth answered " + response.statusCode());
		}
		return response.body();
	}

	private static String userAgent() {
		return "velo-client/pack-library (velo-client.com)";
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	/** A file name that stays inside the pack folder. */
	private static String safeName(String name) {
		String n = name.replace('\\', '_').replace('/', '_').replace("..", "_").strip();
		return n.isEmpty() ? "pack.zip" : n;
	}

	private void setStatus(String s) {
		status = s;
		statusAt = System.currentTimeMillis();
	}

	// ---- Drawing ----

	@Override
	public void tick() {
		super.tick();
		if (searchIn > 0 && --searchIn == 0) {
			startSearch(false);
		}
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		PackUi.frame();
		hits.clear();
		int x = contentX();
		int y = contentY();
		int w = contentWidth();
		int bottom = contentBottom();

		VeloStyle.drawInputField(context, x, y, searchWidth(), 24, search != null && search.isFocused(),
				VeloUi.inside(mouseX, mouseY, x, y, searchWidth(), 24));
		VeloDraw.searchGlyph(context, x + 11, y + 12, 10, MenuKit.TEXT_3);
		int bx = x + w - 120;
		hits.add(PackUi.button(context, bx, y + 3, 120, 18, "Sort: " + SORT_LABELS[sort], 0, true, mouseX, mouseY, () -> {
			sort = (sort + 1) % SORTS.length;
			startSearch(false);
		}));
		hits.add(PackUi.button(context, bx - 98, y + 3, 92, 18, "Open folder", 0, true, mouseX, mouseY,
				() -> net.veloclient.velo.client.gui.FileManagerOpener.open(PackBridge.packDir().toFile(), this::setStatus)));

		int top = y + 34;
		int listBottom = bottom - 28;
		int height = listBottom - top;
		listArea = new int[] {x, top, w, height};
		int columns = w >= 760 ? 3 : w >= 480 ? 2 : 1;
		int cardW = (w - GAP * (columns - 1)) / columns;
		int rows = (results.size() + columns - 1) / columns;
		float content = rows * (CARD_H + GAP) + (more || loading ? 30 : 0);
		float maxScroll = Math.max(0, content - height);
		scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
		scroll += (scrollTarget - scroll) * 0.35f;
		// Near the end: the next page.
		if (more && !loading && scroll >= maxScroll - 40) {
			startSearch(true);
		}

		if (results.isEmpty()) {
			String message = loading ? "Searching Modrinth..." : error != null ? error : "No packs found";
			int tw = this.textRenderer.getWidth(message);
			MenuKit.text(context, this.textRenderer, MenuKit.ui(VeloUi.trim(message, w - 20)), x + Math.max(0, (w - tw) / 2), top + height / 2 - 4,
					MenuKit.TEXT_3);
		} else {
			context.enableScissor(x, top, x + w, listBottom);
			for (int i = 0; i < results.size(); i++) {
				int cx = x + (i % columns) * (cardW + GAP);
				int cy = Math.round(top + (i / columns) * (CARD_H + GAP) - scroll);
				if (cy + CARD_H < top || cy > listBottom) {
					continue;
				}
				drawCard(context, results.get(i), cx, cy, cardW, mouseX, mouseY, top, listBottom);
			}
			if (loading) {
				String more = "Loading more...";
				int ly = Math.round(top + rows * (CARD_H + GAP) - scroll) + 8;
				MenuKit.text(context, this.textRenderer, MenuKit.ui(more), x + (w - this.textRenderer.getWidth(more)) / 2, ly, MenuKit.TEXT_3);
			}
			context.disableScissor();
		}

		String hint = !status.isEmpty() && System.currentTimeMillis() - statusAt < 8000 ? status
				: "Resource packs from Modrinth for " + net.veloclient.velo.client.util.ClientCompat.minecraftVersion()
						+ " - Install puts them in your pack list.";
		MenuKit.text(context, this.textRenderer, MenuKit.ui(VeloUi.trim(hint, w - 110)), x, bottom - 13, MenuKit.TEXT_3);
		hits.add(PackUi.button(context, x + w - 86, bottom - 19, 86, 19, "Done", 1, true, mouseX, mouseY, this::requestClose));
	}

	private void drawCard(DrawContext context, Hit hit, int x, int y, int w, int mouseX, int mouseY, int clipTop, int clipBottom) {
		boolean inList = mouseY >= clipTop && mouseY <= clipBottom;
		boolean hovered = inList && VeloUi.inside(mouseX, mouseY, x, y, w, CARD_H);
		float h = PackUi.hover("lib:" + hit.projectId(), hovered);
		VeloDraw.fillRounded(context, x, y, w, CARD_H, 8, VeloAnim.lerpArgb(0x0CFFFFFF, 0x1AFFFFFF, h));
		VeloDraw.strokeRounded(context, x, y, w, CARD_H, 8, 0x14FFFFFF);
		int icon = 48;
		Identifier texture = icons.get(hit.projectId());
		if (texture == null && !hit.iconUrl().isBlank()) {
			icons.put(hit.projectId(), PLACEHOLDER);
			RemoteTextureLoader.load(hit.iconUrl(), id -> icons.put(hit.projectId(), id));
		}
		if (texture != null && texture != PLACEHOLDER) {
			context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x + 8, y + 8, 0f, 0f, icon, icon, icon, icon, icon, icon);
		} else {
			VeloDraw.fillRounded(context, x + 8, y + 8, icon, icon, 6, 0x22FFFFFF);
			String letter = hit.title().isEmpty() ? "?" : hit.title().substring(0, 1).toUpperCase(Locale.ROOT);
			MenuKit.text(context, this.textRenderer, MenuKit.uiBold(letter), x + 8 + (icon - this.textRenderer.getWidth(letter)) / 2, y + 8 + icon / 2 - 4,
					MenuKit.TEXT_2);
		}
		int tx = x + icon + 16;
		int tw = w - icon - 24;
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.uiBold(hit.title()), tw), tx, y + 8, MenuKit.TEXT);
		String meta = "by " + hit.author() + "  ·  " + compact(hit.downloads()) + " downloads";
		MenuKit.text(context, this.textRenderer, MenuKit.ui(VeloUi.trim(meta, tw)), tx, y + 20, MenuKit.TEXT_3);
		List<String> lines = VeloUi.wrap(hit.description(), Math.max(30, tw));
		for (int i = 0; i < Math.min(2, lines.size()); i++) {
			String line = i == 1 && lines.size() > 2 ? VeloUi.trim(lines.get(i) + "...", tw) : lines.get(i);
			context.drawTextWithShadow(this.textRenderer, line, tx, y + 33 + i * 10, MenuKit.TEXT_2);
		}
		String state = installState.get(hit.projectId());
		boolean busy = "Installing...".equals(state);
		boolean done = "Installed".equals(state);
		String label = done ? "Installed" : busy ? "Installing..." : "Failed".equals(state) ? "Retry" : "Install";
		int bw = 84;
		VeloUi.Hit button = PackUi.button(context, x + w - bw - 8, y + CARD_H - 24, bw, 18, label, done ? 0 : 1, !busy && !done && inList,
				mouseX, mouseY, () -> install(hit));
		if (inList) {
			hits.add(button);
		}
	}

	private static final Identifier PLACEHOLDER = Identifier.of("velo-client", "pack_library_placeholder");

	private static String compact(long n) {
		if (n >= 1_000_000) {
			return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
		}
		if (n >= 1_000) {
			return String.format(Locale.ROOT, "%.1fk", n / 1_000.0);
		}
		return String.valueOf(n);
	}

	// ---- Input ----

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		for (VeloUi.Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (VeloUi.inside(mouseX, mouseY, listArea[0], listArea[1], listArea[2], listArea[3])) {
			scrollTarget -= (float) verticalAmount * 40;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}
}
