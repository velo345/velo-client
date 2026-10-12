package net.veloclient.velo.client.gui.media;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.clips.ClipRecorder;
import net.veloclient.velo.client.gui.PlayerHeads;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.pvp.FightTracker;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fight recaps: your last fights on the left, the selected one on the right - who won, a hit
 * timeline (your hits above the line, theirs below, crits highlighted) and the numbers: hits both
 * ways, best combo, crits, accuracy, reach, damage. Links to the fight's clip when one was saved,
 * or saves one right now while the fight is still in the replay buffer.
 */
public final class FightRecapScreen extends VeloWindow {

	private static final Gson GSON = new Gson();
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH).withZone(ZoneId.systemDefault());
	/** Clips saved for a recap (by its start time). */
	private static final Map<Long, Path> CLIPS = new ConcurrentHashMap<>();

	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private List<FightTracker.Recap> recaps;
	private int selected;
	private String status;

	public FightRecapScreen(Screen parent) {
		super(Text.literal("Fight Recap"), 640, 380);
		returnTo(parent);
		recaps = FightTracker.recaps();
	}

	public static void linkClip(FightTracker.Recap recap, Path clip) {
		CLIPS.put(recap.startedAt(), clip);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		Theme theme = ThemeManager.active();
		int x = contentX();
		int y = contentY();
		int width = contentWidth();
		int bottom = contentBottom();
		if (recaps.isEmpty()) {
			context.drawCenteredTextWithShadow(this.textRenderer, "No fights yet - recaps appear here after you fight a player.",
					x + width / 2, y + 80, VeloUi.muted());
			return;
		}
		// Fight list
		int listW = 170;
		int rowY = y;
		for (int i = 0; i < recaps.size() && rowY + 30 <= bottom; i++) {
			FightTracker.Recap r = recaps.get(i);
			int index = i;
			boolean active = i == selected;
			boolean hover = VeloUi.inside(mouseX, mouseY, x, rowY, listW, 28);
			VeloDraw.fillRounded(context, x, rowY, listW, 28, 5, active ? VeloUi.withAlpha(theme.accentStart(), 0x60)
					: hover ? VeloUi.withAlpha(theme.accentStart(), 0x30) : VeloStyle.card());
			PlayerHeads.draw(context, r.opponentUuid(), r.opponent(), x + 5, rowY + 5, 18);
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(r.opponent(), listW - 70), x + 28, rowY + 5, theme.text());
			context.drawTextWithShadow(this.textRenderer, WHEN.format(Instant.ofEpochMilli(r.startedAt())), x + 28, rowY + 16, VeloUi.muted());
			String tag = switch (r.outcome()) {
				case WON -> "W";
				case LOST -> "L";
				case ENDED -> "-";
			};
			int tagColor = r.outcome() == FightTracker.Outcome.WON ? 0xFF6FE39A : r.outcome() == FightTracker.Outcome.LOST ? 0xFFFF5555 : 0xFFFFC53D;
			String score = r.hitsDealt() + "-" + r.hitsTaken();
			context.drawTextWithShadow(this.textRenderer, score, x + listW - 8 - this.textRenderer.getWidth(score) - 12, rowY + 10, VeloUi.muted());
			context.drawTextWithShadow(this.textRenderer, tag, x + listW - 12, rowY + 10, tagColor);
			hits.add(new VeloUi.Hit(x, rowY, listW, 28, () -> selected = index));
			rowY += 32;
		}

		// Details
		FightTracker.Recap r = recaps.get(Math.min(selected, recaps.size() - 1));
		JsonObject json = GSON.toJsonTree(r).getAsJsonObject();
		int dx = x + listW + 14;
		int dw = width - listW - 14;
		VeloDraw.fillRounded(context, dx, y, dw, bottom - y, 8, VeloStyle.card());
		PlayerHeads.draw(context, r.opponentUuid(), r.opponent(), dx + 10, y + 10, 32);
		String headline = switch (r.outcome()) {
			case WON -> "Victory";
			case LOST -> "Defeat";
			case ENDED -> "Fight ended";
		};
		int color = r.outcome() == FightTracker.Outcome.WON ? 0xFF6FE39A : r.outcome() == FightTracker.Outcome.LOST ? 0xFFFF5555 : 0xFFFFC53D;
		context.drawTextWithShadow(this.textRenderer, headline, dx + 50, y + 12, color);
		context.drawTextWithShadow(this.textRenderer, "vs " + r.opponent() + (r.server() != null ? "  ·  " + r.server() : ""), dx + 50, y + 26, VeloUi.muted());

		// Timeline: your hits above the line, theirs below.
		int tlX = dx + 10;
		int tlW = dw - 20;
		int tlY = y + 58;
		VeloDraw.fillRounded(context, tlX, tlY, tlW, 26, 4, 0x40000000);
		context.fill(tlX + 4, tlY + 13, tlX + tlW - 4, tlY + 14, 0x60FFFFFF);
		double total = Math.max(1, r.durationMillis());
		for (FightTracker.Event e : r.events()) {
			int ex = tlX + 4 + (int) Math.min(tlW - 8, e.atMillis() / total * (tlW - 8));
			if (e.dealt()) {
				context.fill(ex, tlY + 3, ex + 2, tlY + 12, e.crit() ? 0xFFFFD84A : 0xFF6FE39A);
			} else {
				context.fill(ex, tlY + 15, ex + 2, tlY + 24, 0xFFFF5555);
			}
		}
		// Stats in two columns
		List<String[]> rows = FightRecapRows.rows(json);
		int colW = (dw - 30) / 2;
		int sy = tlY + 36;
		for (int i = 0; i < rows.size(); i++) {
			int cx = dx + 10 + (i % 2) * (colW + 10);
			int cy = sy + (i / 2) * 14;
			context.drawTextWithShadow(this.textRenderer, rows.get(i)[0], cx, cy, VeloUi.muted());
			int w = this.textRenderer.getWidth(rows.get(i)[1]);
			context.drawTextWithShadow(this.textRenderer, rows.get(i)[1], cx + colW - w, cy, theme.text());
		}

		int by = bottom - 28;
		Path clip = CLIPS.get(r.startedAt());
		if (clip != null && java.nio.file.Files.exists(clip)) {
			hits.add(VeloUi.pill(context, dx + 10, by, 110, 20, "Watch clip", 1, mouseX, mouseY,
					() -> this.client.setScreen(new MediaScreen(this, clip))));
		} else if (ClipRecorder.active() && System.currentTimeMillis() - (r.startedAt() + r.durationMillis()) < ClipRecorder.bufferedSeconds() * 1000) {
			hits.add(VeloUi.pill(context, dx + 10, by, 130, 20, "Save clip of fight", 1, mouseX, mouseY, () -> {
				status = "Saving...";
				double seconds = (System.currentTimeMillis() - r.startedAt()) / 1000.0 + 2;
				ClipRecorder.save("fight", r, seconds, file -> {
					if (file != null) {
						linkClip(r, file);
						status = "Clip saved";
					} else {
						status = "Nothing to save";
					}
				});
			}));
		}
		hits.add(VeloUi.pill(context, dx + dw - 120, by, 110, 20, "All clips", 0, mouseX, mouseY,
				() -> this.client.setScreen(new MediaScreen(this))));
		if (status != null) {
			context.drawTextWithShadow(this.textRenderer, status, dx + 150, by + 6, VeloUi.muted());
		}
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
}
