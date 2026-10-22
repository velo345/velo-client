package net.veloclient.velo.client.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.media.ClipPlayerPanel;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.network.SocialClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /velostaff clips} and {@code /velostaff worlds}: every staff member (helpers too) works through reported
 * clips and public worlds here. A reported clip plays right in the screen (streamed from its link, with sound); one
 * decision - remove or dismiss - closes every report on it. Mods and up can also stop the owner sharing clips /
 * listing public worlds for a while. "Live worlds" lists every world hosted right now, to take one down without a
 * report. The owner panel (panel.velo-client.com) has the same tools plus the numbers.
 */
public final class StaffModerationScreen extends VeloWindow {

	private enum Tab { CLIPS, WORLDS, LIVE }

	private Tab tab;
	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private List<JsonObject> rows = List.of();
	private JsonObject selected;
	private ClipPlayerPanel player;
	private TextFieldWidget noteField;
	private String status = "Loading...";
	private int statusColor = 0xFFB8B8C8;
	private int blockDays = -1;
	private boolean loading;
	private boolean armed;
	private double scroll;
	private int openClips;
	private int openWorlds;
	private final float[] rowHover = new float[64];
	private long opened = System.nanoTime();

	public StaffModerationScreen(Screen parent, String kind) {
		super(Text.literal("Velo Staff · Reports"), 760, 460);
		returnTo(parent);
		tab = "world".equals(kind) ? Tab.WORLDS : Tab.CLIPS;
		reload();
	}

	private boolean canBlock() {
		String level = SocialClient.staffLevel();
		return "mod".equals(level) || "admin".equals(level);
	}

	private void reload() {
		loading = true;
		select(null);
		String path = switch (tab) {
			case CLIPS -> "/v1/staff/moderation?kind=clip&status=open";
			case WORLDS -> "/v1/staff/moderation?kind=world&status=open";
			case LIVE -> "/v1/staff/worlds";
		};
		SocialClient.request(path, null).thenAccept(reply -> this.client.execute(() -> {
			loading = false;
			if (!reply.ok()) {
				status = "Couldn't load: " + reply.error();
				statusColor = 0xFFFF7070;
				return;
			}
			List<JsonObject> list = new ArrayList<>();
			for (JsonElement e : reply.json().getAsJsonArray(tab == Tab.LIVE ? "worlds" : "reports")) {
				list.add(e.getAsJsonObject());
			}
			rows = list;
			if (reply.json().has("openClips")) {
				openClips = reply.json().get("openClips").getAsInt();
				openWorlds = reply.json().get("openWorlds").getAsInt();
			}
			status = list.isEmpty() ? (tab == Tab.LIVE ? "No worlds are open right now." : "No open reports - all clear.")
					: list.size() + (tab == Tab.LIVE ? " worlds open" : " open reports");
			statusColor = 0xFFB8B8C8;
			if (!list.isEmpty()) {
				select(list.getFirst());
			}
		}));
	}

	private void select(JsonObject row) {
		if (player != null) {
			player.release();
			player = null;
		}
		selected = row;
		armed = false;
		blockDays = -1;
		if (row != null && tab == Tab.CLIPS && row.has("clip")) {
			player = new ClipPlayerPanel(row.getAsJsonObject("clip").get("url").getAsString() + ".mp4", true);
		}
	}

	@Override
	protected void layoutContent() {
		String keep = noteField == null ? "" : noteField.getText();
		this.clearChildren();
		int listW = 230;
		int dx = contentX() + listW + 12;
		noteField = new TextFieldWidget(this.textRenderer, dx, contentBottom() - 44, contentWidth() - listW - 12, 18, Text.literal("Note"));
		noteField.setPlaceholder(VeloUi.placeholder(tab == Tab.CLIPS ? "Note (only staff see it)" : "Reason (the host is told)"));
		noteField.setText(keep);
		addDrawableChild(noteField);
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		int x = contentX();
		int y = contentY();
		int width = contentWidth();
		float open = Math.min(1f, (System.nanoTime() - opened) / 350_000_000f);
		float eased = 1f - (float) Math.pow(1f - open, 3);

		// ---- Tabs ----
		int tx = x;
		tx += tabButton(context, tx, y, "Clip reports", openClips, Tab.CLIPS, mouseX, mouseY) + 6;
		tx += tabButton(context, tx, y, "World reports", openWorlds, Tab.WORLDS, mouseX, mouseY) + 6;
		tabButton(context, tx, y, "Live worlds", 0, Tab.LIVE, mouseX, mouseY);
		hits.add(VeloUi.pill(context, x + width - 74, y, 74, 20, "Refresh", 0, mouseX, mouseY, this::reload));

		// ---- List ----
		int listW = 230;
		int top = y + 30;
		int bottom = contentBottom();
		VeloDraw.fillRounded(context, x, top, listW, bottom - top, 8, 0x8C0E0E12);
		context.enableScissor(x, top, x + listW, bottom);
		int rowH = 38;
		double maxScroll = Math.max(0, rows.size() * (rowH + 4) - (bottom - top - 8));
		scroll = Math.max(0, Math.min(maxScroll, scroll));
		for (int i = 0; i < rows.size(); i++) {
			JsonObject row = rows.get(i);
			int ry = top + 4 + i * (rowH + 4) - (int) scroll;
			if (ry + rowH < top || ry > bottom) {
				continue;
			}
			// Each row slides in a little after the one above when the list opens.
			int slide = Math.round((1f - Math.min(1f, Math.max(0f, eased * 1.6f - i * 0.08f))) * 18);
			boolean hot = VeloUi.inside(mouseX, mouseY, x + 4, ry, listW - 8, rowH) && mouseY >= top && mouseY < bottom;
			int hi = Math.min(i, rowHover.length - 1);
			rowHover[hi] = VeloAnim.step(rowHover[hi], hot ? 1 : 0, delta / 20f * 12);
			boolean picked = row == selected;
			int fill = picked ? (MenuKit.accent() & 0x00FFFFFF) | 0x40000000 : VeloAnim.lerpArgb(0x00FFFFFF, 0x14FFFFFF, rowHover[hi]);
			VeloDraw.fillRounded(context, x + 4 + slide, ry, listW - 8, rowH, 6, fill);
			if (picked) {
				VeloDraw.fillRounded(context, x + 4 + slide, ry + 8, 3, rowH - 16, 1.5f, MenuKit.accent());
			}
			String title = tab == Tab.LIVE ? str(row, "name") : str(row, "title");
			String sub = tab == Tab.LIVE ? str(row, "hostName") + " · " + row.get("guests").getAsInt() + "/" + row.get("maxPlayers").getAsInt()
					+ " · " + visibility(str(row, "visibility"))
					: str(row, "ownerName") + " · " + str(row, "category") + " · " + VeloUi.ago(row.get("at").getAsLong());
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(title.isEmpty() ? "(untitled)" : title, listW - 50), x + 12 + slide, ry + 7, 0xFFF0F0F4);
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(sub, listW - 24), x + 12 + slide, ry + 21, 0xFF8A8A95);
			int count = tab == Tab.LIVE ? row.get("reports").getAsInt() : row.get("reportsOnTarget").getAsInt();
			if (count > 0) {
				String n = String.valueOf(count);
				int nw = this.textRenderer.getWidth(n) + 8;
				VeloDraw.fillRounded(context, x + listW - 12 - nw + slide, ry + 6, nw, 11, 5, 0xFFE0262E);
				context.drawTextWithShadow(this.textRenderer, n, x + listW - 8 - nw + slide, ry + 8, 0xFFFFFFFF);
			}
			int clipTop = Math.max(ry, top);
			int clipBottom = Math.min(ry + rowH, bottom);
			if (clipBottom > clipTop) {
				hits.add(new VeloUi.Hit(x + 4, clipTop, listW - 8, clipBottom - clipTop, () -> select(row)));
			}
		}
		if (rows.isEmpty()) {
			context.drawCenteredTextWithShadow(this.textRenderer, loading ? "Loading..." : "Nothing here", x + listW / 2, top + 30, 0xFF8A8A95);
		}
		context.disableScissor();

		// ---- Detail ----
		int dx = x + listW + 12;
		int dw = width - listW - 12;
		if (selected == null) {
			context.drawTextWithShadow(this.textRenderer, status, dx, top + 6, statusColor);
			return;
		}
		int dy = top;
		if (tab == Tab.CLIPS) {
			if (player != null) {
				int ph = Math.min(Math.round(dw * 9 / 16f) + 36, bottom - dy - 110);
				player.render(context, this.textRenderer, dx, dy, dw, ph, mouseX, mouseY);
				dy += ph + 8;
			} else {
				VeloDraw.fillRounded(context, dx, dy, dw, 40, 8, 0x8C0E0E12);
				context.drawTextWithShadow(this.textRenderer, "This clip was already deleted.", dx + 10, dy + 16, 0xFF8A8A95);
				dy += 48;
			}
		} else {
			JsonObject world = tab == Tab.LIVE ? selected : selected.has("world") ? selected.getAsJsonObject("world") : null;
			VeloDraw.fillRounded(context, dx, dy, dw, 76, 8, 0x8C0E0E12);
			if (world != null) {
				MenuKit.icon(context, "globe", dx + 10, dy + 10, 20, MenuKit.accent());
				context.drawTextWithShadow(this.textRenderer, VeloUi.trim(str(world, "name"), dw - 50), dx + 38, dy + 10, 0xFFF0F0F4);
				context.drawTextWithShadow(this.textRenderer, VeloUi.trim("Host " + str(world, "hostName") + " · " + visibility(str(world, "visibility"))
						+ " · " + str(world, "version"), dw - 50), dx + 38, dy + 22, 0xFF8A8A95);
				context.drawTextWithShadow(this.textRenderer, VeloUi.trim(world.get("guests").getAsInt() + "/" + world.get("maxPlayers").getAsInt() + " playing · "
						+ world.get("visitors").getAsInt() + " visited · " + world.get("mods").getAsInt() + " mods · open " + VeloUi.ago(world.get("openedAt").getAsLong()),
						dw - 20), dx + 10, dy + 42, 0xFFB8B8C2);
				String description = str(world, "description");
				if (!description.isEmpty()) {
					context.drawTextWithShadow(this.textRenderer, VeloUi.trim("“" + description + "”", dw - 20), dx + 10, dy + 56, 0xFF8A8A95);
				}
			} else {
				context.drawTextWithShadow(this.textRenderer, "This world isn't open any more.", dx + 10, dy + 30, 0xFF8A8A95);
			}
			dy += 84;
		}
		if (tab != Tab.LIVE) {
			VeloDraw.fillRounded(context, dx, dy, dw, 34, 6, 0x33E0262E);
			String reason = str(selected, "reason");
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(str(selected, "category").toUpperCase(Locale.ROOT) + "  ·  reported by "
					+ str(selected, "reporterName"), dw - 16), dx + 8, dy + 6, 0xFFFFB4B4);
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(reason.isEmpty() ? "(no reason given)" : reason, dw - 16), dx + 8, dy + 19, 0xFFE4E4EA);
		}

		// ---- Actions ----
		int ay = bottom - 20;
		boolean exists = tab == Tab.LIVE || selected.has("exists") && selected.get("exists").getAsBoolean();
		int ax = dx;
		if (canBlock() && exists) {
			String label = blockDays < 0 ? "Don't block" : blockDays == 0 ? "Block for good" : "Block " + blockDays + " days";
			hits.add(VeloUi.pill(context, ax, ay, 104, 20, label, blockDays < 0 ? 0 : 3, mouseX, mouseY, () -> {
				blockDays = blockDays < 0 ? 7 : blockDays == 7 ? 30 : blockDays == 30 ? 0 : -1;
			}));
			ax += 110;
		}
		if (tab != Tab.LIVE) {
			hits.add(VeloUi.pill(context, ax, ay, 104, 20, "Dismiss", 0, mouseX, mouseY, () -> resolve("dismiss")));
		}
		String remove = tab == Tab.CLIPS ? "Remove clip" : "Take down";
		if (exists) {
			hits.add(VeloUi.pill(context, dx + dw - 120, ay, 120, 20, armed ? "Really " + remove.toLowerCase(Locale.ROOT) + "?" : remove, 3, mouseX, mouseY, () -> {
				if (!armed) {
					armed = true;
				} else {
					resolve("remove");
				}
			}));
		}
		if (!status.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(status, dw), dx, bottom - 58, statusColor);
		}
	}

	private int tabButton(DrawContext context, int x, int y, String label, int badge, Tab which, int mouseX, int mouseY) {
		String text = badge > 0 ? label + "  " + badge : label;
		int w = this.textRenderer.getWidth(text) + 22;
		hits.add(VeloUi.pill(context, x, y, w, 20, text, tab == which ? 1 : 0, mouseX, mouseY, () -> {
			if (tab != which) {
				tab = which;
				scroll = 0;
				opened = System.nanoTime();
				layoutContent();
				reload();
			}
		}));
		return w;
	}

	private void resolve(String action) {
		JsonObject row = selected;
		if (row == null) {
			return;
		}
		JsonObject body = new JsonObject();
		String path;
		if (tab == Tab.LIVE) {
			path = "/v1/staff/worlds/takedown";
			body.addProperty("id", str(row, "id"));
		} else {
			path = "/v1/staff/moderation/resolve";
			body.addProperty("kind", tab == Tab.CLIPS ? "clip" : "world");
			body.addProperty("target", str(row, "target"));
			body.addProperty("action", action);
		}
		body.addProperty("note", noteField == null ? "" : noteField.getText());
		if ("remove".equals(action) && blockDays >= 0) {
			body.addProperty("blockDays", blockDays);
		}
		status = "Working...";
		SocialClient.request(path, body).thenAccept(reply -> this.client.execute(() -> {
			if (!reply.ok()) {
				status = "Couldn't do that: " + reply.error();
				statusColor = 0xFFFF7070;
				return;
			}
			if (noteField != null) {
				noteField.setText("");
			}
			reload();
			status = "remove".equals(action) ? (tab == Tab.CLIPS ? "Clip removed" : "World taken down") : "Reports dismissed";
			statusColor = 0xFF6FE39A;
		}));
	}

	private static String visibility(String v) {
		return switch (v) {
			case "public" -> "Public";
			case "request" -> "Ask to join";
			default -> "Friends";
		};
	}

	private static String str(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (player != null && player.mouseClicked(click.x(), click.y())) {
			return true;
		}
		for (VeloUi.Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (player != null && player.mouseDragged(click.x(), click.y())) {
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (player != null) {
			player.mouseReleased();
		}
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scroll -= verticalAmount * 30;
		return true;
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		if (player != null && (noteField == null || !noteField.isFocused()) && player.keyPressed(input.key())) {
			return true;
		}
		return super.keyPressed(input);
	}

	@Override
	public void removed() {
		if (player != null) {
			player.release();
			player = null;
		}
		super.removed();
	}
}
