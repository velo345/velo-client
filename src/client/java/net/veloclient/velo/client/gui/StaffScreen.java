package net.veloclient.velo.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.network.SocialClient;
import net.veloclient.velo.client.theme.ThemeManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code /velostaff [player]}: the Velo Client team's tools. Mods and up see a player's info and can censor their custom cape;
 * admins can also ban them from online services (friends &amp; messages, custom capes, parties) and work through player reports.
 * The server checks every action against your staff level.
 */
public final class StaffScreen extends VeloWindow {

	private enum Tab { PLAYER, REPORTS }

	private Tab tab = Tab.PLAYER;
	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private TextFieldWidget nameField;
	private TextFieldWidget reasonField;
	private JsonObject player;
	private List<JsonObject> reports;
	private String status = "";
	private int statusColor = 0xFFB8B8C8;
	private final Set<String> services = new LinkedHashSet<>(List.of("friends", "capes", "parties"));
	private int days = 7;
	private String armed;
	private int scroll;
	private final String initialTarget;

	public StaffScreen(Screen parent, String target) {
		super(Text.literal("Velo Staff"), 560, 400);
		returnTo(parent);
		initialTarget = target;
		if (target != null && !target.isBlank()) {
			lookup(target);
		}
	}

	private boolean admin() {
		return "admin".equals(SocialClient.staffLevel());
	}

	@Override
	protected void layoutContent() {
		String keepName = nameField == null ? (initialTarget == null ? "" : initialTarget) : nameField.getText();
		String keepReason = reasonField == null ? "" : reasonField.getText();
		this.clearChildren();
		if (tab == Tab.PLAYER) {
			nameField = new TextFieldWidget(this.textRenderer, contentX(), contentY() + 22, contentWidth() - 76, 18, Text.literal("Player"));
			nameField.setPlaceholder(net.veloclient.velo.client.gui.widget.VeloUi.placeholder("Player name or UUID"));
			nameField.setText(keepName);
			addDrawableChild(nameField);
			if (player != null && admin()) {
				reasonField = new TextFieldWidget(this.textRenderer, contentX() + contentWidth() / 2 + 6, contentY() + 184, contentWidth() / 2 - 6, 18,
						Text.literal("Reason"));
				reasonField.setPlaceholder(net.veloclient.velo.client.gui.widget.VeloUi.placeholder("Reason (shown to them)"));
				reasonField.setText(keepReason);
				addDrawableChild(reasonField);
			} else {
				reasonField = null;
			}
		}
	}

	private void say(String text, boolean error) {
		status = text;
		statusColor = error ? 0xFFFF8A8A : 0xFF6FE0A4;
	}

	private void call(String path, JsonObject body, java.util.function.Consumer<JsonObject> onOk) {
		say("Working...", false);
		SocialClient.request(path, body).thenAccept(reply -> MinecraftClient.getInstance().execute(() -> {
			if (reply.ok()) {
				status = "";
				onOk.accept(reply.json());
			} else {
				say(reply.error(), true);
			}
		}));
	}

	private void lookup(String target) {
		JsonObject body = new JsonObject();
		body.addProperty("target", target);
		call("/v1/staff/lookup", body, json -> {
			player = json;
			layoutContent();
		});
	}

	private void loadReports() {
		call("/v1/staff/reports?status=open", null, json -> {
			List<JsonObject> list = new ArrayList<>();
			json.getAsJsonArray("reports").forEach(e -> list.add(e.getAsJsonObject()));
			reports = list;
		});
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		int x = contentX();
		int y = contentY();
		int width = contentWidth();
		String level = SocialClient.staffLevel();
		hits.add(VeloUi.pill(context, x, y, 70, 16, "Player", tab == Tab.PLAYER ? 1 : 0, mouseX, mouseY, () -> {
			tab = Tab.PLAYER;
			layoutContent();
		}));
		if (admin()) {
			hits.add(VeloUi.pill(context, x + 74, y, 70, 16, "Reports", tab == Tab.REPORTS ? 1 : 0, mouseX, mouseY, () -> {
				tab = Tab.REPORTS;
				scroll = 0;
				layoutContent();
				loadReports();
			}));
		}
		String badge = level == null ? "not staff" : level.toUpperCase(Locale.ROOT);
		context.drawTextWithShadow(this.textRenderer, badge, x + width - this.textRenderer.getWidth(badge), y + 4, 0xFFFF6B6B);
		if (tab == Tab.PLAYER) {
			renderPlayer(context, x, y + 22, width, mouseX, mouseY);
		} else {
			renderReports(context, x, y + 24, width, mouseX, mouseY);
		}
		if (!status.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(status, width), x, contentBottom() - 9, statusColor);
		}
	}

	private void renderPlayer(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
		hits.add(VeloUi.pill(context, x + width - 70, y, 70, 18, "Look up", 1, mouseX, mouseY, () -> lookup(nameField.getText().trim())));
		if (player == null) {
			context.drawTextWithShadow(this.textRenderer, "Look a player up to see their info, censor a custom cape" + (admin() ? " or ban them." : "."),
					x, y + 30, VeloUi.muted());
			return;
		}
		String uuid = player.get("uuid").getAsString();
		String name = player.get("username").getAsString();
		int top = y + 28;
		VeloDraw.fillRounded(context, x, top, width, 50, 8, VeloUi.withAlpha(0xFFFFFFFF, 0x0C));
		PlayerHeads.draw(context, uuid, name, x + 8, top + 9, 32);
		context.drawTextWithShadow(this.textRenderer, name, x + 48, top + 9, ThemeManager.active().text());
		String levelText = player.has("level") && !player.get("level").isJsonNull() ? "  ·  staff: " + player.get("level").getAsString() : "";
		boolean online = player.has("online") && player.get("online").getAsBoolean();
		context.drawTextWithShadow(this.textRenderer, (online ? "Online" : "Offline") + levelText + "  ·  " + player.get("reports").getAsInt() + " reports",
				x + 48, top + 22, VeloUi.muted());
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(uuid, width - 60), x + 48, top + 34, VeloUi.muted());

		// Custom cape: what it looks like, and censoring it.
		int cy = top + 60;
		int half = width / 2 - 6;
		context.drawTextWithShadow(this.textRenderer, "Custom cape", x, cy, ThemeManager.active().text());
		String hash = player.has("customCape") && !player.get("customCape").isJsonNull() ? player.get("customCape").getAsString() : null;
		if (hash == null) {
			context.drawTextWithShadow(this.textRenderer, "None uploaded", x, cy + 14, VeloUi.muted());
		} else {
			var definition = net.veloclient.velo.client.cosmetics.RemoteCapeCache.resolve("custom:" + hash);
			VeloDraw.fillRounded(context, x, cy + 12, 44, 68, 6, 0xFF101014);
			if (definition.isPresent()) {
				try {
					var texture = net.veloclient.velo.client.cosmetics.CapeManager.textureIdentifier(definition.get());
					context.drawTexture(RenderPipelines.GUI_TEXTURED, texture, x + 2, cy + 14, 1f, 1f, 40, 64, 10, 16, 64, 32);
				} catch (RuntimeException ignored) {
					// still loading
				}
			}
			boolean sure = ("censor:" + uuid).equals(armed);
			hits.add(VeloUi.pill(context, x + 52, cy + 14, 110, 18, sure ? "Click to confirm" : "Censor cape", 3, mouseX, mouseY, () -> {
				if (!sure) {
					armed = "censor:" + uuid;
					return;
				}
				armed = null;
				JsonObject body = new JsonObject();
				body.addProperty("target", uuid);
				call("/v1/staff/censor-cape", body, json -> {
					player = json;
					say("Cape removed - that image can't be used again.", false);
				});
			}));
			for (String line : VeloUi.wrap("Removes it for everyone and blocks the same image from being uploaded again.", half - 52)) {
				cy += 10;
				context.drawTextWithShadow(this.textRenderer, line, x + 52, cy + 26, VeloUi.muted());
			}
		}

		// Online-service ban (admins).
		if (!admin()) {
			return;
		}
		int bx = x + half + 12;
		int by = top + 60;
		context.drawTextWithShadow(this.textRenderer, "Online service ban", bx, by, ThemeManager.active().text());
		JsonObject ban = player.has("ban") && player.get("ban").isJsonObject() ? player.getAsJsonObject("ban") : null;
		if (ban != null) {
			StringBuilder what = new StringBuilder();
			ban.getAsJsonArray("services").forEach(e -> what.append(what.length() == 0 ? "" : ", ").append(e.getAsString()));
			long until = ban.has("until") ? ban.get("until").getAsLong() : 0;
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim("Banned: " + what + (until > 0 ? "  ·  " + daysLeft(until) : "  ·  for good"),
					half), bx, by + 12, 0xFFFF8A8A);
			hits.add(VeloUi.pill(context, bx, by + 24, 80, 16, "Lift ban", 0, mouseX, mouseY, () -> {
				JsonObject body = new JsonObject();
				body.addProperty("target", uuid);
				call("/v1/staff/unban", body, json -> {
					player = json;
					say("Ban lifted", false);
				});
			}));
			by += 30;
		}
		int sx = bx;
		for (String[] s : new String[][] {{"friends", "Friends"}, {"capes", "Capes"}, {"parties", "Parties"}}) {
			int w = this.textRenderer.getWidth(s[1]) + 14;
			boolean on = services.contains(s[0]);
			hits.add(VeloUi.pill(context, sx, by + 14, w, 16, s[1], on ? 1 : 0, mouseX, mouseY, () -> {
				if (!services.remove(s[0])) {
					services.add(s[0]);
				}
			}));
			sx += w + 4;
		}
		sx = bx;
		for (int d : new int[] {1, 7, 30, 0}) {
			String label = d == 0 ? "Forever" : d + (d == 1 ? " day" : " days");
			int w = this.textRenderer.getWidth(label) + 14;
			hits.add(VeloUi.pill(context, sx, by + 34, w, 16, label, days == d ? 1 : 0, mouseX, mouseY, () -> days = d));
			sx += w + 4;
		}
		if (reasonField != null) {
			reasonField.setY(by + 54);
		}
		boolean sure = ("ban:" + uuid).equals(armed);
		hits.add(VeloUi.pill(context, bx, by + 78, 120, 18, sure ? "Click to confirm" : "Ban from services", 3, mouseX, mouseY, () -> {
			if (services.isEmpty()) {
				say("Pick at least one service", true);
				return;
			}
			if (!sure) {
				armed = "ban:" + uuid;
				return;
			}
			armed = null;
			JsonObject body = new JsonObject();
			body.addProperty("target", uuid);
			JsonArray list = new JsonArray();
			services.forEach(list::add);
			body.add("services", list);
			body.addProperty("days", days);
			body.addProperty("reason", reasonField == null ? "" : reasonField.getText());
			call("/v1/staff/ban", body, json -> {
				player = json;
				say(name + " is banned from " + String.join(", ", services), false);
			});
		}));
	}

	private void renderReports(DrawContext context, int x, int y, int width, int mouseX, int mouseY) {
		if (reports == null) {
			context.drawTextWithShadow(this.textRenderer, "Loading reports...", x, y + 6, VeloUi.muted());
			return;
		}
		if (reports.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, "No open reports - nice.", x, y + 6, VeloUi.muted());
			return;
		}
		int bottom = contentBottom() - 14;
		context.enableScissor(x, y, x + width, bottom);
		int ry = y - scroll;
		for (JsonObject r : List.copyOf(reports)) {
			List<String> lines = new ArrayList<>();
			for (JsonElement m : r.getAsJsonArray("messages")) {
				JsonObject o = m.getAsJsonObject();
				lines.add(o.get("fromName").getAsString() + ": " + o.get("text").getAsString());
			}
			int shown = Math.min(6, lines.size());
			int h = 44 + shown * 10 + (lines.size() > shown ? 10 : 0);
			VeloDraw.fillRounded(context, x, ry, width, h, 8, VeloUi.withAlpha(0xFFFFFFFF, 0x0C));
			String head = r.get("category").getAsString().toUpperCase(Locale.ROOT) + "  ·  " + r.get("reporterName").getAsString() + " → "
					+ r.get("targetName").getAsString() + "  ·  " + ("party".equals(r.get("context").getAsString()) ? "party" : "DMs");
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(head, width - 16), x + 8, ry + 6, 0xFFFF8A8A);
			String reason = r.has("reason") && !r.get("reason").isJsonNull() ? r.get("reason").getAsString() : "";
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(reason.isBlank() ? "(no note)" : reason, width - 16), x + 8, ry + 18,
					ThemeManager.active().text());
			int ly = ry + 30;
			for (int i = lines.size() - shown; i < lines.size(); i++) {
				context.drawTextWithShadow(this.textRenderer, VeloUi.trim(lines.get(i), width - 20), x + 12, ly, VeloUi.muted());
				ly += 10;
			}
			if (lines.isEmpty()) {
				context.drawTextWithShadow(this.textRenderer, "No messages attached", x + 12, ly, VeloUi.muted());
			}
			String id = r.get("id").getAsString();
			String target = r.get("target").getAsString();
			boolean visible = ry + h > y && ry < bottom;
			if (visible) {
				hits.add(VeloUi.pill(context, x + width - 210, ry + h - 22, 64, 16, "Look up", 0, mouseX, mouseY, () -> {
					tab = Tab.PLAYER;
					player = null;
					layoutContent();
					nameField.setText(target);
					lookup(target);
				}));
				hits.add(VeloUi.pill(context, x + width - 142, ry + h - 22, 70, 16, "Ban 7 days", 3, mouseX, mouseY, () -> resolve(id, true)));
				hits.add(VeloUi.pill(context, x + width - 68, ry + h - 22, 60, 16, "Dismiss", 0, mouseX, mouseY, () -> resolve(id, false)));
			}
			ry += h + 6;
		}
		context.disableScissor();
	}

	private void resolve(String id, boolean ban) {
		JsonObject body = new JsonObject();
		body.addProperty("id", id);
		body.addProperty("status", ban ? "actioned" : "dismissed");
		if (ban) {
			JsonObject b = new JsonObject();
			JsonArray list = new JsonArray();
			list.add("friends");
			list.add("parties");
			b.add("services", list);
			b.addProperty("days", 7);
			b.addProperty("reason", "Reported chat");
			body.add("ban", b);
		}
		call("/v1/staff/reports/resolve", body, json -> {
			reports.removeIf(r -> r.get("id").getAsString().equals(id));
			say(ban ? "Banned from friends & parties for 7 days" : "Report dismissed", false);
		});
	}

	private static String daysLeft(long until) {
		long d = Math.max(0, (until - System.currentTimeMillis()) / 86_400_000L);
		return d + (d == 1 ? " day left" : " days left");
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (tab == Tab.REPORTS) {
			scroll = Math.max(0, scroll - (int) Math.signum(verticalAmount) * 24);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

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
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if ((input.key() == net.veloclient.velo.client.util.Keys.ENTER || input.key() == net.veloclient.velo.client.util.Keys.KP_ENTER)
				&& nameField != null && nameField.isFocused()) {
			lookup(nameField.getText().trim());
			return true;
		}
		return super.keyPressed(input);
	}
}
