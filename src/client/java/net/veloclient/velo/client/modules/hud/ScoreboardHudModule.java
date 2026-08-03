package net.veloclient.velo.client.modules.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.scoreboard.number.NumberFormat;
import net.minecraft.scoreboard.number.StyledNumberFormat;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.Comparator;
import java.util.List;

/**
 * Lets the server-set scoreboard sidebar be dragged/resized/hidden through
 * the same HUD Layout Editor as every other element, instead of being stuck
 * at vanilla's hardcoded top-right corner. Faithfully ports the real
 * algorithm from {@code InGameHud#renderScoreboardSidebar(DrawContext,
 * ScoreboardObjective)} (name and score are two separately-aligned text
 * runs, not one combined string - an earlier version of this wrongly used
 * {@link ScoreboardEntry#formatted} alone, which is only the score half and
 * silently dropped every player's name) instead of guessing at vanilla's
 * behavior. {@link net.veloclient.velo.client.mixin.ScoreboardSidebarMixin}
 * cancels vanilla's own render call while this is enabled so it doesn't
 * draw twice. Reports zero size (and so is skipped by the edit screen and
 * never drawn) whenever the server hasn't actually set a sidebar objective -
 * "if one exists," per the request that added this.
 */
public final class ScoreboardHudModule extends AbstractModule implements HudModule, Configurable {

	private static final int MAX_ENTRIES = 15;
	private static final int PADDING_X = 4;
	private static final String SCORE_JOINER = ": ";
	private static final List<String> BACKGROUND_OPTIONS = List.of("Vanilla", "Solid Panel", "None");

	private final HudPosition position = new HudPosition(0.995f, 0.02f);
	private String background = "Vanilla";

	public ScoreboardHudModule() {
		super("scoreboard-hud", "Scoreboard", "Repositions, scales, or hides the server's scoreboard sidebar - appears here automatically whenever a server actually sets one.",
				ModuleCategory.HUD, SafetyTag.ALWAYS_SAFE, true);
	}

	private record Row(Text name, Text score, int nameWidth, int scoreWidth) {
	}

	private record Layout(Text title, List<Row> rows, int rowHeight, int width, int height) {
	}

	private Layout computeLayout() {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world == null || client.player == null) {
			return null;
		}
		Scoreboard scoreboard = client.world.getScoreboard();
		ScoreboardObjective objective = scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
		if (objective == null) {
			return null;
		}
		TextRenderer renderer = client.textRenderer;
		NumberFormat numberFormat = objective.getNumberFormatOr(StyledNumberFormat.RED);

		List<Row> rows = scoreboard.getScoreboardEntries(objective).stream()
				.filter(e -> !e.hidden())
				.sorted(Comparator.comparing(ScoreboardEntry::value).reversed()
						.thenComparing(ScoreboardEntry::owner, String.CASE_INSENSITIVE_ORDER))
				.limit(MAX_ENTRIES)
				.map(entry -> {
					Team team = scoreboard.getScoreHolderTeam(entry.owner());
					Text name = Team.decorateName((AbstractTeam) team, entry.name());
					MutableText score = entry.formatted(numberFormat);
					return new Row(name, score, renderer.getWidth(name), renderer.getWidth(score));
				})
				.toList();

		Text title = objective.getDisplayName();
		int joinerWidth = renderer.getWidth(SCORE_JOINER);
		int rowHeight = renderer.fontHeight + 1;
		int width = renderer.getWidth(title);
		for (Row row : rows) {
			width = Math.max(width, row.nameWidth() + (row.scoreWidth() > 0 ? joinerWidth + row.scoreWidth() : 0));
		}
		width += PADDING_X * 2;
		int height = rowHeight * (1 + rows.size());
		return new Layout(title, rows, rowHeight, width, height);
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		Layout layout = computeLayout();
		if (layout == null) {
			return;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		TextRenderer renderer = client.textRenderer;

		if (!background.equals("None")) {
			int titleBg;
			int rowBg;
			if (background.equals("Vanilla")) {
				titleBg = client.options.getTextBackgroundColor(0.4f);
				rowBg = client.options.getTextBackgroundColor(0.3f);
			} else {
				Theme theme = ThemeManager.active();
				titleBg = (theme.surfaceWithOpacity() & 0x00FFFFFF) | 0x8C000000;
				rowBg = (theme.surfaceWithOpacity() & 0x00FFFFFF) | 0x60000000;
			}
			context.fill(x, y, x + layout.width(), y + layout.rowHeight(), titleBg);
			context.fill(x, y + layout.rowHeight(), x + layout.width(), y + layout.height(), rowBg);
		}

		int titleWidth = renderer.getWidth(layout.title());
		context.drawText(renderer, layout.title(), x + (layout.width() - titleWidth) / 2, y + 1, Colors.WHITE, false);

		int rowY = y + layout.rowHeight();
		for (Row row : layout.rows()) {
			context.drawText(renderer, row.name(), x + PADDING_X, rowY + 1, Colors.WHITE, false);
			if (row.scoreWidth() > 0) {
				context.drawText(renderer, row.score(), x + layout.width() - PADDING_X - row.scoreWidth(), rowY + 1, Colors.WHITE, false);
			}
			rowY += layout.rowHeight();
		}
	}

	@Override
	public int width() {
		Layout layout = computeLayout();
		return layout == null ? 0 : layout.width();
	}

	@Override
	public int height() {
		Layout layout = computeLayout();
		return layout == null ? 0 : layout.height();
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(new ConfigField.ChoiceField("Background", BACKGROUND_OPTIONS, () -> background, v -> background = v));
	}
}
