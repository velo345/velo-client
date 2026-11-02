package net.veloclient.velo.dev.notes;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.dev.storage.StorageHighlight;
import net.veloclient.velo.dev.storage.StorageMc;
import net.veloclient.velo.dev.storage.StorageStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The notes list (O when you don't look at a note): the notes around you, everything on this server, your private
 * notes everywhere and the notes you were @mentioned in. Open, lead me there, hide / delete - and a new note where
 * you stand.
 */
public final class NotesListScreen extends VeloWindow {

	private static final String[] TABS = {"Here", "This server", "Private", "Mentions"};
	private static final int ROW = 34;
	private static int tab;

	private TextFieldWidget searchField;
	private String search = "";
	private double scroll;
	private int contentHeight;
	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private String feedback = "";
	private long feedbackUntil;

	public NotesListScreen(Screen parent) {
		super(Text.literal("World Notes"), 640, 420);
		returnTo(parent);
		NoteStore.requestRefresh();
	}

	void showMentions() {
		tab = 3;
	}

	@Override
	protected String[] headerTabs() {
		return TABS;
	}

	@Override
	protected int headerTab() {
		return tab;
	}

	@Override
	protected void selectHeaderTab(int index) {
		tab = index;
		scroll = 0;
	}

	@Override
	protected String headerTabBadge(int index) {
		int unread = NoteStore.inbox().size();
		return index == 3 && unread > 0 ? String.valueOf(unread) : null;
	}

	@Override
	protected void layoutContent() {
		if (searchField != null) {
			search = searchField.getText();
		}
		this.clearChildren();
		searchField = new TextFieldWidget(this.textRenderer, contentX() + 20, contentY() + 3, 200, 14, Text.literal("Search"));
		searchField.setDrawsBackground(false);
		searchField.setPlaceholder(VeloUi.placeholder("Search notes, checklists, people..."));
		searchField.setText(search);
		searchField.setChangedListener(value -> scroll = 0);
		addDrawableChild(searchField);
	}

	private List<Note> notes() {
		List<Note> list = switch (tab) {
			case 0 -> new ArrayList<>(NoteStore.here());
			case 1 -> new ArrayList<>(NoteStore.onServer());
			case 2 -> new ArrayList<>(NoteStore.allLocal());
			default -> {
				List<Note> mentioned = new ArrayList<>();
				for (NoteStore.Mention mention : NoteStore.inbox()) {
					Note note = NoteStore.byId(mention.id());
					if (note != null) {
						mentioned.add(note);
					}
				}
				yield mentioned;
			}
		};
		String query = (searchField == null ? search : searchField.getText()).toLowerCase(Locale.ROOT).strip();
		if (!query.isEmpty()) {
			list.removeIf(n -> !matches(n, query));
		}
		list.sort(Comparator.comparing((Note n) -> !n.unread).thenComparingDouble(this::distance));
		return list;
	}

	private static boolean matches(Note note, String query) {
		if (contains(note.title, query) || contains(note.text, query) || contains(note.ownerName, query)) {
			return true;
		}
		return note.checks.stream().anyMatch(c -> contains(c.text, query)) || note.names.values().stream().anyMatch(n -> contains(n, query));
	}

	private static boolean contains(String text, String query) {
		return text != null && text.toLowerCase(Locale.ROOT).contains(query);
	}

	private double distance(Note note) {
		double[] me = StorageMc.playerPos();
		if (me == null || note.world == null || !note.world.equals(NoteStore.worldKey()) || note.dim == null || !note.dim.equals(StorageStore.here())) {
			return 1e9;
		}
		double dx = note.x - me[0];
		double dy = note.y - me[1];
		double dz = note.z - me[2];
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		Theme theme = ThemeManager.active();
		int x = contentX();
		int y = contentY();
		int width = contentWidth();
		int newWidth = 120;
		int toggleWidth = 120;
		int searchWidth = width - newWidth - toggleWidth - 12;
		net.veloclient.velo.client.gui.widget.VeloStyle.drawInputField(context, x, y, searchWidth, 20, searchField.isFocused(),
				VeloUi.inside(mouseX, mouseY, x, y, searchWidth, 20));
		searchField.setWidth(Math.max(20, searchWidth - 26));
		VeloDraw.searchGlyph(context, x + 10f, y + 10f, 9f, VeloUi.muted());
		boolean inWorld = StorageMc.inWorld() && NoteStore.serverKey() != null;
		VeloUi.Hit add = VeloUi.pill(context, x + searchWidth + 6, y + 2, newWidth, 16, "+ New note here", inWorld ? 1 : 0, mouseX, mouseY,
				() -> this.client.setScreen(new NoteEditScreen(this, NoteStore.draft(), true)));
		if (inWorld) {
			hits.add(add);
		}
		boolean shown = NotesModule.showInWorld();
		hits.add(VeloUi.pill(context, x + width - toggleWidth, y + 2, toggleWidth, 16, shown ? "Shown in world" : "Hidden in world", shown ? 1 : 0, mouseX,
				mouseY, () -> NotesModule.setShowInWorld(!shown)));

		int top = y + 28;
		int bottom = contentBottom() - 14;
		List<Note> notes = notes();
		contentHeight = notes.size() * (ROW + 4);
		scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - (bottom - top))));
		if (notes.isEmpty()) {
			String empty = switch (tab) {
				case 0 -> "No notes around you. Press \"+ New note here\" to stick one in the world.";
				case 3 -> "Nobody mentioned you yet. People write @" + NotesMc.myName() + " in a note shared with you.";
				default -> "No notes yet.";
			};
			int lineY = top + 30;
			for (String line : VeloUi.wrap(empty, width - 80)) {
				context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(line), x + width / 2, lineY, VeloUi.muted());
				lineY += 11;
			}
		}
		context.enableScissor(x, top, x + width, bottom);
		int cy = top - (int) scroll;
		for (Note note : notes) {
			if (cy + ROW >= top && cy <= bottom) {
				drawRow(context, note, x, cy, width - 6, mouseX, mouseY, top, bottom, theme);
			}
			cy += ROW + 4;
		}
		context.disableScissor();
		String footer = System.currentTimeMillis() < feedbackUntil ? feedback
				: NoteStore.blocked() ? "You're blocked from shared notes - private notes still work."
				: notes.size() + " note" + (notes.size() == 1 ? "" : "s") + "  -  look at a note in the world and press " + NotesModule.OPEN_KEY.getBoundKeyLocalizedText().getString() + " to open it";
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(footer, width), x, contentBottom() - 8, VeloUi.muted());
	}

	private void drawRow(DrawContext context, Note note, int x, int y, int width, int mouseX, int mouseY, int clipTop, int clipBottom, Theme theme) {
		boolean visible = mouseY >= clipTop && mouseY < clipBottom;
		boolean hovered = visible && VeloUi.inside(mouseX, mouseY, x, y, width, ROW);
		VeloDraw.fillRounded(context, x, y, width, ROW, 6, VeloUi.withAlpha(0xFFFFFFFF, hovered ? 0x14 : 0x08));
		VeloDraw.fillRounded(context, x, y + 5, 3, ROW - 10, 1, note.color | 0xFF000000);
		VeloDraw.fillRounded(context, x + 8, y + 5, 24, 24, 5, VeloUi.withAlpha(note.color | 0xFF000000, 0x50));
		if (note.icon != null) {
			StorageMc.drawItem(context, StorageMc.stack(note.icon, 1), x + 12, y + 9, "");
		} else {
			VeloDraw.fillRounded(context, x + 15, y + 11, 10, 12, 2, 0xFFF8F3E6);
			context.fill(x + 17, y + 14, x + 23, y + 15, 0xFF8A8478);
			context.fill(x + 17, y + 17, x + 23, y + 18, 0xFF8A8478);
		}
		int tx = x + 40;
		int actions = hovered ? 190 : 0;
		String title = note.displayTitle() + (note.unread ? "  @you" : "");
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(title, width - (tx - x) - actions - 8), tx, y + 6, note.unread ? 0xFFFF8A9A : theme.text());
		StringBuilder detail = new StringBuilder();
		detail.append(note.local ? "Private" : note.mine ? ("friends".equals(note.share) ? "Shared with friends" : "Shared with " + note.people.size())
				: "From " + note.ownerName);
		if (!note.checks.isEmpty()) {
			detail.append("  -  ").append(note.done()).append("/").append(note.checks.size()).append(" done");
		}
		double d = distance(note);
		detail.append("  -  ").append(d < 1e9 ? Math.round(d) + "m away" : note.server != null && note.server.equals(NoteStore.serverKey())
				? StorageStore.dimensionLabel(note.dim) : note.server == null ? "" : note.server.replaceFirst("^(mp|sp|realm):", ""));
		if (!note.mine && NoteStore.hidden(note)) {
			detail.append("  -  hidden");
		}
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(detail.toString(), width - (tx - x) - actions - 8), tx, y + 19, VeloUi.muted());
		if (!visible) {
			return;
		}
		if (!hovered) {
			hits.add(new VeloUi.Hit(x, y, width, ROW, () -> this.client.setScreen(new NoteEditScreen(this, note, false))));
			return;
		}
		int bx = x + width - 8;
		bx -= 58;
		hits.add(VeloUi.pill(context, bx, y + 9, 54, 16, "Open", 1, mouseX, mouseY, () -> this.client.setScreen(new NoteEditScreen(this, note, false))));
		bx -= 68;
		hits.add(VeloUi.pill(context, bx, y + 9, 64, 16, "Lead me", 0, mouseX, mouseY, () -> lead(note)));
		if (!note.mine) {
			bx -= 58;
			boolean hidden = NoteStore.hidden(note);
			hits.add(VeloUi.pill(context, bx, y + 9, 54, 16, hidden ? "Show" : "Hide", 0, mouseX, mouseY, () -> NoteStore.setHidden(note, !hidden)));
		}
		hits.add(new VeloUi.Hit(x, y, bx - x - 4, ROW, () -> this.client.setScreen(new NoteEditScreen(this, note, false))));
	}

	private void lead(Note note) {
		StorageHighlight.block(note.world, note.dim, (int) Math.floor(note.x), (int) Math.floor(note.y), (int) Math.floor(note.z), null, note.displayTitle(), null);
		if (note.world != null && note.world.equals(NoteStore.worldKey()) && note.dim != null && note.dim.equals(StorageStore.here())) {
			closeToGame();
		} else {
			feedback = "That note is in another world or dimension - you'll be led there once you're in it.";
			feedbackUntil = System.currentTimeMillis() + 5000;
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

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		scroll -= verticalAmount * 20;
		return true;
	}
}
