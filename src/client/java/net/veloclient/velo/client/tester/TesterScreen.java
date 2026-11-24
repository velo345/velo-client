package net.veloclient.velo.client.tester;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * The Tester window inside the game ({@code /velo tester}) - for testers who play with only the mod jar on another
 * launcher (owner 2026-11-24). Same server as the Tester launcher's window: the build's test plan one item after the
 * other (Works / Broken / Skip), and reports for broken items or anything else with the game log always attached and
 * recent screenshots (F2) or Velo clips picked from a list.
 */
public final class TesterScreen extends VeloWindow {

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private static final int PER_PAGE = 5;

	private enum Mode { LOADING, PLAN, REPORT, MESSAGE }

	private Mode mode = Mode.LOADING;
	private String message = "Loading the test plan...";
	private String buildVersion;
	private String buildTitle = "";
	private final List<JsonObject> items = new ArrayList<>();
	private final java.util.Map<String, String> states = new java.util.HashMap<>();
	private int page;
	// report
	private String reportItem;
	private String reportItemTitle;
	private String reportType = "bug";
	private TextFieldWidget titleField;
	private TextFieldWidget textField;
	private final List<Path> candidates = new ArrayList<>();
	private final java.util.Set<Path> picked = new java.util.LinkedHashSet<>();
	private String status = "";
	private boolean sending;

	public TesterScreen(Screen parent) {
		super(Text.literal("Velo Tester"), 420, 300);
		returnTo(parent);
		load();
	}

	// ------------------------------------------------------------------ server

	private static HttpRequest.Builder request(String path, Duration timeout) {
		String base = net.veloclient.velo.client.network.VeloServerClient.serverBaseUrl();
		String token = net.veloclient.velo.client.network.VeloServerClient.sessionToken();
		if (base == null || token == null) {
			throw new IllegalStateException("Not connected to the Velo Network yet - wait a moment and try again");
		}
		return HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout).header("Authorization", "Bearer " + token);
	}

	private static JsonObject send(HttpRequest request) throws Exception {
		HttpResponse<String> r = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		JsonObject json;
		try {
			json = JsonParser.parseString(r.body()).getAsJsonObject();
		} catch (RuntimeException e) {
			json = new JsonObject();
		}
		if (r.statusCode() / 100 != 2) {
			throw new IllegalStateException(json.has("error") ? json.get("error").getAsString() : "Server error " + r.statusCode());
		}
		return json;
	}

	private void load() {
		CompletableFuture.runAsync(() -> {
			try {
				JsonObject me = send(request("/v1/tester/me", Duration.ofSeconds(15)).GET().build());
				boolean allowed = me.has("tester") && me.get("tester").getAsBoolean() || me.has("owner") && me.get("owner").getAsBoolean();
				if (!allowed) {
					show("You're not a tester. Testers get the Tester role on the Velo Discord - link your account with /link there.");
					return;
				}
				JsonArray builds = send(request("/v1/tester/builds", Duration.ofSeconds(15)).GET().build()).getAsJsonArray("builds");
				String mine = myVersion();
				JsonObject build = null;
				for (JsonElement b : builds) {
					JsonObject o = b.getAsJsonObject();
					if (build == null || o.get("version").getAsString().equals(mine)) {
						build = o;
					}
				}
				if (build == null || !build.has("notes")) {
					show("No tester build with a test plan yet.");
					return;
				}
				JsonObject b = build;
				JsonObject notes = b.getAsJsonObject("notes");
				JsonObject stat = me.has("mine") && me.getAsJsonObject("mine").has(b.get("version").getAsString())
						? me.getAsJsonObject("mine").getAsJsonObject(b.get("version").getAsString()) : new JsonObject();
				MinecraftClient.getInstance().execute(() -> {
					buildVersion = b.get("version").getAsString();
					buildTitle = notes.has("title") ? notes.get("title").getAsString() : "Tester build";
					items.clear();
					if (notes.has("test")) {
						notes.getAsJsonArray("test").forEach(e -> items.add(e.getAsJsonObject()));
					}
					if (stat.has("feedback")) {
						for (var f : stat.getAsJsonObject("feedback").entrySet()) {
							states.put(f.getKey(), f.getValue().getAsJsonObject().get("state").getAsString());
						}
					}
					mode = Mode.PLAN;
					layoutContent();
				});
			} catch (Exception e) {
				show(e.getMessage());
			}
		});
	}

	/** The tester build this jar is (its version without "-dev+mc"), or "" for a normal build. */
	private static String myVersion() {
		return net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("velo-client")
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("").replaceAll("[-+].*$", "");
	}

	private void show(String text) {
		MinecraftClient.getInstance().execute(() -> {
			mode = Mode.MESSAGE;
			message = text;
			layoutContent();
		});
	}

	private void answer(JsonObject item, String state) {
		String id = item.get("id").getAsString();
		states.put(id, state);
		JsonObject e = new JsonObject();
		e.addProperty("kind", "feedback");
		e.addProperty("version", buildVersion);
		e.addProperty("item", id);
		e.addProperty("state", state);
		CompletableFuture.runAsync(() -> {
			try {
				send(request("/v1/tester/event", Duration.ofSeconds(15)).header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(e.toString())).build());
			} catch (Exception ignored) {
				// shown as answered anyway; it goes again next time
			}
		});
		net.veloclient.velo.VeloClient.LOGGER.info("[tester] {} -> {}", id, state);
		layoutContent();
	}

	// ------------------------------------------------------------------ report

	private void openReport(String item, String title) {
		mode = Mode.REPORT;
		reportItem = item;
		reportItemTitle = title;
		reportType = item == null ? "bug" : "broken";
		status = "";
		picked.clear();
		candidates.clear();
		Path game = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir();
		candidates.addAll(newest(game.resolve("screenshots"), ".png", 6));
		candidates.addAll(newest(game.resolve("velo-clips"), ".mp4", 3));
		titleField = null;
		textField = null;
		layoutContent();
	}

	private static List<Path> newest(Path dir, String ext, int max) {
		if (!Files.isDirectory(dir)) {
			return List.of();
		}
		try (Stream<Path> files = Files.walk(dir, 2)) {
			return files.filter(p -> p.toString().toLowerCase().endsWith(ext) && Files.isRegularFile(p))
					.sorted((a, b) -> Long.compare(modified(b), modified(a))).limit(max).toList();
		} catch (Exception e) {
			return List.of();
		}
	}

	private static long modified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (Exception e) {
			return 0;
		}
	}

	private void sendReport() {
		String text = textField == null ? "" : textField.getText().strip();
		if (text.isEmpty() && picked.isEmpty()) {
			status = "Write a few words (or pick a screenshot) first.";
			return;
		}
		sending = true;
		status = "Sending...";
		JsonObject report = new JsonObject();
		report.addProperty("version", buildVersion);
		report.addProperty("type", reportType);
		if (reportItem != null) {
			report.addProperty("item", reportItem);
		}
		report.addProperty("title", titleField == null ? "" : titleField.getText().strip());
		report.addProperty("text", text + "\n\n(sent from the game: Minecraft "
				+ net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?")
				+ ", Velo " + net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("velo-client").map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?") + ")");
		List<Path> files = List.copyOf(picked);
		CompletableFuture.runAsync(() -> {
			try {
				report.addProperty("gameLog", redact(tail(net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log"))));
				String id = send(request("/v1/tester/report", Duration.ofSeconds(60)).header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(report.toString())).build()).get("id").getAsString();
				int n = 0;
				for (Path f : files) {
					n++;
					int k = n;
					MinecraftClient.getInstance().execute(() -> status = "Uploading " + k + " of " + files.size() + "...");
					String name = f.getFileName().toString().replaceAll("[^A-Za-z0-9._()-]", "_");
					send(request("/v1/tester/attachment?report=" + id + "&name=" + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8),
							Duration.ofMinutes(15)).header("Content-Type", "application/octet-stream").POST(HttpRequest.BodyPublishers.ofFile(f)).build());
				}
				net.veloclient.velo.VeloClient.LOGGER.info("[tester] report {} sent ({} file(s))", id, files.size());
				MinecraftClient.getInstance().execute(() -> {
					sending = false;
					if (reportItem != null) {
						states.put(reportItem, "broken");
					}
					mode = Mode.PLAN;
					status = "Report sent - thank you!";
					layoutContent();
				});
			} catch (Exception e) {
				MinecraftClient.getInstance().execute(() -> {
					sending = false;
					status = "Couldn't send it: " + e.getMessage();
					layoutContent();
				});
			}
		});
	}

	private static String tail(Path file) {
		try {
			byte[] all = Files.readAllBytes(file);
			int from = Math.max(0, all.length - 1_500_000);
			return new String(all, from, all.length - from, StandardCharsets.UTF_8);
		} catch (Exception e) {
			return "";
		}
	}

	private static String redact(String log) {
		return log.replaceAll("(?i)(accessToken|token|authorization)([\"'=: ]+)[A-Za-z0-9._\\-]{16,}", "$1$2[redacted]")
				.replaceAll("eyJ[A-Za-z0-9_\\-]{20,}\\.[A-Za-z0-9_\\-]{10,}\\.[A-Za-z0-9_\\-]{10,}", "[redacted jwt]");
	}

	/** {@code text} cut with "…" to fit {@code width} pixels. */
	private String fit(String text, int width) {
		if (this.textRenderer.getWidth(text) <= width) {
			return text;
		}
		String s = text;
		while (s.length() > 1 && this.textRenderer.getWidth(s + "…") > width) {
			s = s.substring(0, s.length() - 1);
		}
		return s + "…";
	}

	// ------------------------------------------------------------------ layout

	@Override
	protected void layoutContent() {
		this.clearChildren();
		int x = contentX();
		int w = contentWidth();
		int bottom = contentBottom() - 20;
		switch (mode) {
			case PLAN -> {
				int pages = Math.max(1, (items.size() + PER_PAGE - 1) / PER_PAGE);
				page = Math.min(page, pages - 1);
				int y = contentY() + 26;
				for (int i = page * PER_PAGE; i < Math.min(items.size(), (page + 1) * PER_PAGE); i++) {
					JsonObject item = items.get(i);
					String id = item.get("id").getAsString();
					String title = item.has("title") ? item.get("title").getAsString() : id;
					int bw = 52;
					int bx = x + w - bw * 3 - 8;
					addDrawableChild(new VeloButton(bx, y, bw, 16, Text.literal("Works"), b -> answer(item, "works")));
					addDrawableChild(new VeloButton(bx + bw + 4, y, bw, 16, Text.literal("Broken"), b -> openReport(id, title)));
					addDrawableChild(new VeloButton(bx + (bw + 4) * 2, y, bw, 16, Text.literal("Skip"), b -> answer(item, "skip")));
					y += 34;
				}
				if (pages > 1) {
					addDrawableChild(new VeloButton(x, bottom - 22, 20, 16, Text.literal("<"), b -> {
						page = Math.max(0, page - 1);
						layoutContent();
					}));
					addDrawableChild(new VeloButton(x + 24, bottom - 22, 20, 16, Text.literal(">"), b -> {
						page = Math.min(pages - 1, page + 1);
						layoutContent();
					}));
				}
				int half = (w - 8) / 2;
				addDrawableChild(new VeloButton(x, bottom, half, 20, Text.literal("Report a bug / idea"), b -> openReport(null, null)).primary());
				addDrawableChild(new VeloButton(x + half + 8, bottom, half, 20, Text.literal("Close"), b -> requestClose()));
			}
			case REPORT -> {
				int y = contentY() + 16;
				if (reportItem == null) {
					addDrawableChild(new VeloButton(x, y, 70, 16, Text.literal((reportType.equals("bug") ? "● " : "") + "Bug"), b -> {
						reportType = "bug";
						layoutContent();
					}));
					addDrawableChild(new VeloButton(x + 74, y, 90, 16, Text.literal((reportType.equals("suggestion") ? "● " : "") + "Suggestion"), b -> {
						reportType = "suggestion";
						layoutContent();
					}));
					y += 22;
				}
				String oldTitle = titleField == null ? (reportItemTitle == null ? "" : reportItemTitle + " doesn't work") : titleField.getText();
				String oldText = textField == null ? "" : textField.getText();
				titleField = new TextFieldWidget(this.textRenderer, x, y, w, 16, Text.literal("Title"));
				titleField.setMaxLength(150);
				titleField.setText(oldTitle);
				titleField.setPlaceholder(Text.literal("Short title"));
				addDrawableChild(titleField);
				y += 22;
				textField = new TextFieldWidget(this.textRenderer, x, y, w, 16, Text.literal("What happened"));
				textField.setMaxLength(2000);
				textField.setText(oldText);
				textField.setPlaceholder(Text.literal("What happened? What did you expect?"));
				addDrawableChild(textField);
				y += 26;
				int col = 0;
				for (Path f : candidates) {
					boolean on = picked.contains(f);
					String name = f.getFileName().toString();
					String label = (on ? "✔ " : "") + (name.length() > 26 ? name.substring(0, 26) + "…" : name);
					int cw = (w - 6) / 2;
					addDrawableChild(new VeloButton(x + col * (cw + 6), y, cw, 14, Text.literal(label), b -> {
						if (!picked.remove(f)) {
							picked.add(f);
						}
						layoutContent();
					}));
					col++;
					if (col == 2) {
						col = 0;
						y += 17;
					}
				}
				int half = (w - 8) / 2;
				VeloButton send = new VeloButton(x + half + 8, bottom, half, 20, Text.literal(sending ? "Sending..." : "Send report"), b -> {
					if (!sending) {
						sendReport();
					}
				}).primary();
				addDrawableChild(new VeloButton(x, bottom, half, 20, Text.literal("Back"), b -> {
					mode = Mode.PLAN;
					layoutContent();
				}));
				addDrawableChild(send);
			}
			default -> addDrawableChild(new VeloButton(x, bottom, w, 20, Text.literal("Close"), b -> requestClose()));
		}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int x = contentX();
		int y = contentY();
		int w = contentWidth();
		switch (mode) {
			case PLAN -> {
				long done = items.stream().filter(i -> states.containsKey(i.get("id").getAsString())).count();
				context.drawTextWithShadow(this.textRenderer, buildTitle, x, y, VeloStyle.text());
				context.drawTextWithShadow(this.textRenderer, done + " of " + items.size() + " checked" + (status.isEmpty() ? "" : "  ·  " + status),
						x, y + 11, VeloStyle.textMuted());
				int rowY = y + 26;
				for (int i = page * PER_PAGE; i < Math.min(items.size(), (page + 1) * PER_PAGE); i++) {
					JsonObject item = items.get(i);
					String id = item.get("id").getAsString();
					String state = states.get(id);
					String title = (i + 1) + ". " + (item.has("title") ? item.get("title").getAsString() : id);
					int textW = w - 52 * 3 - 16;
					context.drawTextWithShadow(this.textRenderer, fit(title, textW), x, rowY + 4,
							state == null ? VeloStyle.text() : state.equals("works") ? 0xFF3DDC97 : state.equals("broken") ? 0xFFFF6B73 : 0xFF9A9396);
					if (item.has("how")) {
						context.drawTextWithShadow(this.textRenderer, fit(item.get("how").getAsString(), w), x, rowY + 19, VeloStyle.textMuted());
					}
					rowY += 34;
				}
			}
			case REPORT -> {
				context.drawTextWithShadow(this.textRenderer, reportItem == null ? "Report a bug or an idea" : "Broken: " + reportItemTitle, x, y, VeloStyle.text());
				int listY = y + 16 + (reportItem == null ? 22 : 0) + 22 + 22 + 4;
				context.drawTextWithShadow(this.textRenderer, candidates.isEmpty() ? "Tip: press F2 for a screenshot - it shows up here to attach."
						: "Attach (newest screenshots and clips):", x, listY - 10, VeloStyle.textMuted());
				context.drawTextWithShadow(this.textRenderer, status.isEmpty() ? "Your game log is attached automatically." : status, x,
						contentBottom() - 32, VeloStyle.textMuted());
			}
			default -> {
				List<String> lines = VeloUi.wrap(message, w);
				int ly = y;
				for (String line : lines) {
					context.drawTextWithShadow(this.textRenderer, line, x, ly, VeloStyle.text());
					ly += 11;
				}
			}
		}
	}
}
