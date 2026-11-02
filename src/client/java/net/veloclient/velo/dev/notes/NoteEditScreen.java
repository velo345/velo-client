package net.veloclient.velo.dev.notes;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.network.SocialClient;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;
import net.veloclient.velo.dev.storage.StorageHighlight;
import net.veloclient.velo.dev.storage.StorageMc;
import net.veloclient.velo.dev.storage.StorageStore;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * One note: write it (title, text with @mentions, checklist), style it (look, colour, icon, size, facing / fixed,
 * move it in the world, a picture), share it (only you / your friends / picked friends and your party, who may edit)
 * and draw on its whiteboard - with the card on the right exactly as it'll float in the world. People a note is
 * shared with read it here, tick its checklist, look at the board, hide it or report it.
 */
public final class NoteEditScreen extends VeloWindow {

	private static final String[] TABS = {"Note", "Look", "Share", "Board"};
	private static final int[] PENS = {0xFF23211D, 0xFFE5484D, 0xFFF5A524, 0xFF46A758, 0xFF3E8BFF, 0xFF8E4EC6, 0xFFFFFFFF, 0x00000000};
	private static final int[] WIDTHS = {2, 4, 8};

	private final Note original;
	private Note draft;
	private final boolean isNew;
	private int tab;
	private TextFieldWidget titleField;
	private Supplier<String> textValue;
	private final List<TextFieldWidget> checkFields = new ArrayList<>();
	private TextFieldWidget reasonField;
	private String reportCategory = "spam";
	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private String feedback = "";
	private long feedbackUntil;
	private boolean busy;
	private String confirmKey;
	private long confirmUntil;
	private double listScroll;
	// Board
	private int pen = 0;
	private int width = 1;
	private Note.Stroke drawing;
	private final List<Note.Stroke> pending = new ArrayList<>();
	private int boardX, boardY, boardW, boardH;
	// Live board: others' strokes while they draw (sid -> stroke, who, when), and ours going out.
	private record LiveStroke(Note.Stroke stroke, String by, long at) {
	}
	private final java.util.Map<String, LiveStroke> live = new java.util.LinkedHashMap<>();
	private String drawingSid;
	private long lastLiveSent;
	private String lastDrawer;
	private long lastDrawerAt;
	private final java.util.function.BiConsumer<String, JsonObject> liveListener = this::onLive;

	public NoteEditScreen(Screen parent, Note note, boolean isNew) {
		super(Text.literal(isNew ? "New note" : "Note"), 700, 440);
		returnTo(parent);
		this.original = note;
		this.draft = note.copy();
		this.isNew = isNew;
		if (draft.kind == null) {
			draft.kind = "note";   // notes from before picture / whiteboard notes
		}
		if (draft.editors == null) {
			draft.editors = new ArrayList<>();
		}
		NoteStore.seen(note);
		if (!draft.canEdit) {
			tab = 0;
		}
		NoteStore.liveListener = liveListener;
	}

	@Override
	public void removed() {
		if (NoteStore.liveListener == liveListener) {
			NoteStore.liveListener = null;
		}
		super.removed();
	}

	/** Someone else drew on this note's board: their stroke while drawing, or the finished strokes (applied once, in order). */
	private void onLive(String type, JsonObject data) {
		if (draft.id == null || !data.has("id") || !draft.id.equals(data.get("id").getAsString())) {
			return;
		}
		String by = data.has("by") ? data.get("by").getAsString() : "Someone";
		if ("note_live".equals(type)) {
			Note.Stroke stroke = NoteStore.GSON.fromJson(data, Note.Stroke.class);
			live.put(data.get("sid").getAsString(), new LiveStroke(stroke, by, System.currentTimeMillis()));
		} else {
			int version = data.has("version") ? data.get("version").getAsInt() : 0;
			if (data.has("sids")) {
				data.getAsJsonArray("sids").forEach(e -> live.remove(e.getAsString()));
			}
			if (version <= draft.version) {
				return;   // already part of a board we got back from our own save
			}
			draft.version = version;
			if (data.has("clear") && data.get("clear").getAsBoolean()) {
				draft.board.clear();
			}
			if (data.has("strokes")) {
				for (JsonElement e : data.getAsJsonArray("strokes")) {
					draft.board.add(NoteStore.GSON.fromJson(e, Note.Stroke.class));
				}
			}
		}
		lastDrawer = by;
		lastDrawerAt = System.currentTimeMillis();
	}

	/** Our own save came back: take its board unless live events already moved us past it. */
	private void takeBoard(Note saved) {
		if (saved.version >= draft.version) {
			draft.board = saved.board;
			draft.version = saved.version;
		}
	}

	/** Where the board is drawn and takes input: its own tab, or the Note tab of a whiteboard note. */
	private boolean boardShown() {
		return tab == 3 || tab == 0 && draft.isBoard();
	}

	/** Dev-only (screenshot tour). */
	public void tourTab(int index) {
		selectHeaderTab(index);
	}

	@Override
	protected String[] headerTabs() {
		return draft.canEdit ? TABS : new String[] {"Note", "Board", "Share"};
	}

	@Override
	protected int headerTab() {
		if (draft.canEdit) {
			return tab;
		}
		return tab == 3 ? 1 : tab == 2 ? 2 : 0;
	}

	@Override
	protected void selectHeaderTab(int index) {
		syncFields();
		tab = draft.canEdit ? index : index == 1 ? 3 : index == 2 ? 2 : 0;
		listScroll = 0;
		layoutContent();
	}

	private int formWidth() {
		return contentWidth() - NoteCard.W - 40;
	}

	@Override
	protected void layoutContent() {
		syncFields();
		this.clearChildren();
		checkFields.clear();
		titleField = null;
		textValue = null;
		reasonField = null;
		int x = contentX();
		int y = contentY();
		int w = formWidth();
		if (tab == 0 && draft.canEdit) {
			int top = y + kindBar();
			titleField = new TextFieldWidget(this.textRenderer, x + 6, top + 16, w - 12, 14, Text.literal("Title"));
			titleField.setDrawsBackground(false);
			titleField.setMaxLength(60);
			titleField.setPlaceholder(VeloUi.placeholder(draft.isImage() ? "Caption (optional)" : draft.isBoard() ? "Title (optional)" : "Title"));
			titleField.setText(draft.title == null ? "" : draft.title);
			addDrawableChild(titleField);
		}
		if (tab == 0 && draft.canEdit && !draft.isImage() && !draft.isBoard()) {
			int top = y + kindBar();
			int boxY = top + 54;
			int boxH = 70;
			//? if <26.1 {
			net.minecraft.client.gui.widget.EditBoxWidget box = net.minecraft.client.gui.widget.EditBoxWidget.builder().x(x + 2).y(boxY + 2)
					.hasBackground(false).build(this.textRenderer, w - 4, boxH - 4, Text.literal("Write something... @name pings them"));
			box.setMaxLength(2000);
			//?} else {
			/*net.minecraft.client.gui.components.MultiLineEditBox box = net.minecraft.client.gui.components.MultiLineEditBox.builder().setX(x + 2).setY(boxY + 2)
					.setShowBackground(false).build(this.textRenderer, w - 4, boxH - 4, Text.literal("Write something... @name pings them"));
			box.setCharacterLimit(2000);
			*///?}
			box.setText(draft.text == null ? "" : draft.text);
			textValue = () -> box.getText();
			addDrawableChild(box);
			// Same rows as drawNoteTab draws (listTop + 14, 20 apart).
			int listTop = top + 142;
			int rowY = listTop + 14 - (int) listScroll;
			for (Note.Check check : draft.checks) {
				TextFieldWidget field = new TextFieldWidget(this.textRenderer, x + 22, rowY + 5, w - 110, 12, Text.literal("Item"));
				field.setDrawsBackground(false);
				field.setMaxLength(140);
				field.setText(check.text == null ? "" : check.text);
				field.visible = rowY >= listTop + 8 && rowY + 16 <= contentBottom() - 20;
				addDrawableChild(field);
				checkFields.add(field);
				rowY += 20;
			}
		}
		if (tab == 2 && !draft.mine) {
			reasonField = new TextFieldWidget(this.textRenderer, x + 6, y + 150, w - 12, 14, Text.literal("Reason"));
			reasonField.setDrawsBackground(false);
			reasonField.setMaxLength(300);
			reasonField.setPlaceholder(VeloUi.placeholder("What's wrong with it? (optional)"));
			addDrawableChild(reasonField);
		}
	}

	/** Field contents into the draft (before rebuilding the widgets or saving). */
	private void syncFields() {
		if (titleField != null) {
			draft.title = titleField.getText();
		}
		if (textValue != null) {
			draft.text = textValue.get();
		}
		for (int i = 0; i < checkFields.size() && i < draft.checks.size(); i++) {
			draft.checks.get(i).text = checkFields.get(i).getText();
		}
	}

	// ---- Drawing ----

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		Theme theme = ThemeManager.active();
		int x = contentX();
		int y = contentY();
		int w = formWidth();
		switch (tab) {
			case 0 -> drawNoteTab(context, x, y, w, mouseX, mouseY, theme);
			case 1 -> drawLookTab(context, x, y, w, mouseX, mouseY, theme);
			case 2 -> drawShareTab(context, x, y, w, mouseX, mouseY, theme);
			default -> drawBoardTab(context, x, y, w, mouseX, mouseY, theme);
		}
		drawPreview(context, mouseX, mouseY, theme);
		String footer = System.currentTimeMillis() < feedbackUntil ? feedback
				: draft.local ? "Private - only you see this note." : draft.mine ? "Shared - changes reach everyone it's shared with."
				: "Shared with you by " + draft.ownerName + (draft.canEdit ? " - you can edit it" : "");
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(footer, contentWidth() - NoteCard.W - 40), x, contentBottom() - 8, VeloUi.muted());
	}

	private void drawPreview(DrawContext context, int mouseX, int mouseY, Theme theme) {
		syncFields();
		int px = contentX() + contentWidth() - NoteCard.W - 16;
		int py = contentY();
		VeloDraw.fillRounded(context, px - 10, py - 4, NoteCard.W + 20, contentBottom() - py - 2, 8, VeloUi.withAlpha(0xFF000000, 0x30));
		int cardH = NoteCard.height(draft, false);
		int space = contentBottom() - py - 92;
		float scale = Math.min(1f, space / (float) Math.max(1, cardH));
		var matrices = context.getMatrices();
		matrices.pushMatrix();
		matrices.translate(px + NoteCard.W * (1 - scale) / 2f, py + 6);
		matrices.scale(scale, scale);
		NoteCard.draw(context, draft, false, false, 255);
		matrices.popMatrix();
		int by = contentBottom() - 80;
		int bw = NoteCard.W;
		if (draft.canEdit) {
			hits.add(VeloUi.pill(context, px, by, bw, 18, busy ? "Saving..." : isNew ? "Save note" : "Save changes", 1, mouseX, mouseY, this::save));
		}
		hits.add(VeloUi.pill(context, px, by + 22, bw, 16, "Show me where it is", 0, mouseX, mouseY, () -> {
			StorageHighlight.block(NoteStore.worldKey() == null ? null : draft.world, draft.dim, (int) Math.floor(draft.x), (int) Math.floor(draft.y),
					(int) Math.floor(draft.z), null, draft.displayTitle(), null);
			if (draft.world != null && draft.world.equals(NoteStore.worldKey())) {
				closeToGame();
			} else {
				flash("That note is in another world or dimension - you'll be led there once you're in it.");
			}
		}));
		hits.add(VeloUi.pill(context, px, by + 42, bw, 16, "All notes", 0, mouseX, mouseY, () -> this.client.setScreen(new NotesListScreen(null))));
	}

	/** Height of the "Note / Picture / Whiteboard" row on top of the Note tab (only the owner picks the kind). */
	private int kindBar() {
		return draft.mine && draft.canEdit ? 28 : 0;
	}

	private void drawKindBar(DrawContext context, int x, int y, int w, int mouseX, int mouseY) {
		String[][] kinds = {{"note", "Note"}, {"image", "Picture  +"}, {"board", "Whiteboard  +"}};
		int pw = (w - 8) / 3;
		for (int i = 0; i < kinds.length; i++) {
			String kind = kinds[i][0];
			hits.add(VeloUi.pill(context, x + i * (pw + 4), y, pw, 18, kinds[i][1], kind.equals(draft.kind) ? 1 : 0, mouseX, mouseY, () -> pickKind(kind)));
		}
	}

	private void pickKind(String kind) {
		if (kind.equals(draft.kind)) {
			return;
		}
		if (!"note".equals(kind)) {
			if (!Note.plus()) {
				flash("Picture and whiteboard notes are Velo+ - try it free for 7 days in the Store.");
				return;
			}
			if ("image".equals(kind) && !Note.imagesHere()) {
				flash("This server turned pictures on notes off.");
				return;
			}
			if ("board".equals(kind) && !Note.whiteboardsHere()) {
				flash("This server turned whiteboards off.");
				return;
			}
		}
		syncFields();
		draft.kind = kind;
		listScroll = 0;
		layoutContent();
	}

	private void drawNoteTab(DrawContext context, int x, int y, int w, int mouseX, int mouseY, Theme theme) {
		if (!draft.canEdit) {
			drawReadOnly(context, x, y, w, mouseX, mouseY, theme);
			return;
		}
		if (kindBar() > 0) {
			drawKindBar(context, x, y, w, mouseX, mouseY);
			y += kindBar();
		}
		label(context, draft.isImage() ? "Caption" : "Title", x, y);
		net.veloclient.velo.client.gui.widget.VeloStyle.drawInputField(context, x, y + 11, w, 20, titleField != null && titleField.isFocused(),
				VeloUi.inside(mouseX, mouseY, x, y + 11, w, 20));
		if (draft.isImage()) {
			drawPictureNote(context, x, y + 40, w, mouseX, mouseY, true);
			return;
		}
		if (draft.isBoard()) {
			drawBoardTab(context, x, y + 40, w, mouseX, mouseY, theme);
			return;
		}
		label(context, "Write  -  @name pings someone who can see it", x, y + 40);
		net.veloclient.velo.client.gui.widget.VeloStyle.drawInputField(context, x, y + 52, w, 70, false, VeloUi.inside(mouseX, mouseY, x, y + 52, w, 70));
		int listTop = y + 142;
		label(context, "Checklist  " + (draft.checks.isEmpty() ? "" : draft.done() + "/" + draft.checks.size()), x, listTop);
		hits.add(VeloUi.pill(context, x + w - 70, listTop - 3, 70, 14, "+ Add item", 0, mouseX, mouseY, () -> {
			syncFields();
			if (draft.checks.size() >= 60) {
				flash("Up to 60 items per note.");
				return;
			}
			Note.Check check = new Note.Check();
			check.id = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
			check.text = "";
			draft.checks.add(check);
			layoutContent();
			if (!checkFields.isEmpty()) {
				TextFieldWidget last = checkFields.get(checkFields.size() - 1);
				setFocused(last);
				last.setFocused(true);
			}
		}));
		int rowY = listTop + 14 - (int) listScroll;
		for (int i = 0; i < draft.checks.size(); i++) {
			Note.Check check = draft.checks.get(i);
			if (rowY >= listTop + 8 && rowY + 16 <= contentBottom() - 20) {
				VeloDraw.fillRounded(context, x, rowY, w, 18, 4, VeloUi.withAlpha(0xFFFFFFFF, 0x0A));
				drawBox(context, x + 6, rowY + 5, check.done);
				int index = i;
				hits.add(new VeloUi.Hit(x, rowY, 18, 18, () -> toggle(draft.checks.get(index))));
				if (check.doneBy != null && check.done) {
					String by = check.doneBy;
					context.drawTextWithShadow(this.textRenderer, by, x + w - 24 - this.textRenderer.getWidth(by), rowY + 5, VeloUi.muted());
				}
				hits.add(VeloUi.pill(context, x + w - 18, rowY + 2, 14, 14, "x", 0, mouseX, mouseY, () -> {
					syncFields();
					draft.checks.remove(index);
					layoutContent();
				}));
			}
			rowY += 20;
		}
		if (draft.checks.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, "No checklist - add items your friends can tick off.", x + 4, listTop + 18, VeloUi.muted());
		}
	}

	/** For people it's shared with who can't edit: the text, and the checklist they can tick. */
	private void drawReadOnly(DrawContext context, int x, int y, int w, int mouseX, int mouseY, Theme theme) {
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(draft.displayTitle(), w), x, y, theme.text());
		if (draft.isImage()) {
			drawPictureNote(context, x, y + 16, w, mouseX, mouseY, false);
			return;
		}
		if (draft.isBoard()) {
			drawBoardTab(context, x, y + 16, w, mouseX, mouseY, theme);
			return;
		}
		int ly = y + 16;
		if (draft.text != null) {
			for (String paragraph : draft.text.split("\n", -1)) {
				for (String line : paragraph.isBlank() ? List.of("") : VeloUi.wrap(paragraph, w - 8)) {
					if (ly < y + 150) {
						context.drawTextWithShadow(this.textRenderer, line, x + 2, ly, 0xFFD6DCE6);
					}
					ly += 11;
				}
			}
		}
		ly = Math.max(ly + 6, y + 40);
		if (!draft.checks.isEmpty()) {
			label(context, "Checklist  " + draft.done() + "/" + draft.checks.size() + "  -  click to tick", x, ly);
			ly += 14;
			for (Note.Check check : draft.checks) {
				if (ly + 16 > contentBottom() - 20) {
					break;
				}
				boolean hovered = VeloUi.inside(mouseX, mouseY, x, ly, w, 18);
				VeloDraw.fillRounded(context, x, ly, w, 18, 4, VeloUi.withAlpha(0xFFFFFFFF, hovered ? 0x16 : 0x0A));
				drawBox(context, x + 6, ly + 5, check.done);
				context.drawTextWithShadow(this.textRenderer, VeloUi.trim(check.text, w - 90), x + 20, ly + 5, check.done ? VeloUi.muted() : theme.text());
				if (check.done && check.doneBy != null) {
					context.drawTextWithShadow(this.textRenderer, check.doneBy, x + w - 6 - this.textRenderer.getWidth(check.doneBy), ly + 5, VeloUi.muted());
				}
				hits.add(new VeloUi.Hit(x, ly, w, 18, () -> toggle(check)));
				ly += 20;
			}
		}
	}

	private void toggle(Note.Check check) {
		boolean done = !check.done;
		if (isNew || draft.local) {
			check.done = done;
			check.doneBy = done ? NotesMc.myName() : null;
			return;
		}
		// Shared: ticks go out right away (also for people who can't edit the rest).
		Note live = NoteStore.byId(draft.id);
		Note.Check target = live == null ? null : live.checks.stream().filter(c -> c.id.equals(check.id)).findFirst().orElse(null);
		check.done = done;
		check.doneBy = done ? NotesMc.myName() : null;
		if (target != null) {
			NoteStore.check(live, target, done, this::flash);
		}
	}

	private void drawLookTab(DrawContext context, int x, int y, int w, int mouseX, int mouseY, Theme theme) {
		label(context, "Look", x, y);
		int px = x;
		String[] names = {"Paper", "Sticky", "Glass", "Sign", "Neon"};
		int pw = (w - 16) / 5;
		for (int i = 0; i < 5; i++) {
			String style = Note.STYLES.get(i);
			hits.add(VeloUi.pill(context, px, y + 12, pw, 18, names[i], style.equals(draft.style) ? 1 : 0, mouseX, mouseY, () -> draft.style = style));
			px += pw + 4;
		}
		label(context, "Colour", x, y + 40);
		int sx = x;
		for (int color : Note.COLORS) {
			boolean selected = (draft.color | 0xFF000000) == (color | 0xFF000000);
			VeloDraw.fillRounded(context, sx, y + 52, 18, 18, 9, color | 0xFF000000);
			if (selected) {
				VeloDraw.strokeRounded(context, sx - 2, y + 50, 22, 22, 11, 0xFFFFFFFF);
			}
			hits.add(new VeloUi.Hit(sx, y + 52, 18, 18, () -> draft.color = color | 0xFF000000));
			sx += 24;
		}
		hits.add(VeloUi.pill(context, sx + 4, y + 53, 64, 16, "Custom...", 0, mouseX, mouseY, () -> {
			syncFields();
			this.client.setScreen(new net.veloclient.velo.client.gui.VeloColorPickerScreen(this, "Note colour", draft.color, false,
					value -> draft.color = value | 0xFF000000));
		}));

		label(context, "Icon", x, y + 80);
		if (draft.icon != null) {
			VeloDraw.fillRounded(context, x, y + 92, 20, 20, 4, VeloUi.withAlpha(0xFF000000, 0x50));
			StorageMc.drawItem(context, StorageMc.stack(draft.icon, 1), x + 2, y + 94, "");
		}
		int ix = x + (draft.icon != null ? 26 : 0);
		hits.add(VeloUi.pill(context, ix, y + 94, 120, 16, "Use item in hand", 0, mouseX, mouseY, () -> {
			String held = NotesMc.heldItem();
			if (held == null) {
				flash("Hold the item you want as the icon.");
			} else {
				draft.icon = held;
			}
		}));
		if (draft.icon != null) {
			hits.add(VeloUi.pill(context, ix + 126, y + 94, 70, 16, "No icon", 0, mouseX, mouseY, () -> draft.icon = null));
		}

		label(context, "Size  " + Math.round(draft.size * 100) + "%", x, y + 122);
		hits.add(VeloUi.pill(context, x, y + 134, 24, 16, "-", 0, mouseX, mouseY, () -> draft.size = Math.max(0.4, Math.round((draft.size - 0.1) * 10) / 10.0)));
		hits.add(VeloUi.pill(context, x + 28, y + 134, 24, 16, "+", 0, mouseX, mouseY, () -> draft.size = Math.min(2.5, Math.round((draft.size + 0.1) * 10) / 10.0)));

		label(context, "In the world", x + 80, y + 122);
		hits.add(VeloUi.pill(context, x + 80, y + 134, 92, 16, "Faces you", draft.face ? 1 : 0, mouseX, mouseY, () -> draft.face = true));
		hits.add(VeloUi.pill(context, x + 176, y + 134, 92, 16, "Fixed (wall)", draft.face ? 0 : 1, mouseX, mouseY, () -> {
			if (draft.face) {
				draft.face = false;
				double[] pos = StorageMc.playerPos();
				draft.yaw = pos == null ? 0 : pos[3];
			}
		}));
		hits.add(VeloUi.pill(context, x, y + 160, w, 20, "Move, turn and resize it in the world", 1, mouseX, mouseY, this::move));
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim("Point where it should go - walls work too. Arrows: size and turn.", w), x, y + 184, VeloUi.muted());

		label(context, "Picture", x, y + 204);
		hits.add(VeloUi.pill(context, x, y + 216, 120, 16, draft.image > 0 ? "Change picture..." : "Add picture  +", 0, mouseX, mouseY, this::pickPicture));
		if (draft.image > 0) {
			hits.add(VeloUi.pill(context, x + 126, y + 216, 100, 16, "Remove picture", 0, mouseX, mouseY, () -> setPicture(null)));
		}
		context.drawTextWithShadow(this.textRenderer, "PNG / JPG, shrunk to 256 px. Shared pictures can be reported.", x, y + 238, VeloUi.muted());
	}

	private void move() {
		syncFields();
		if (draft.world != null && !draft.world.equals(NoteStore.worldKey()) || draft.dim != null && !draft.dim.equals(StorageStore.here())) {
			flash("Go to the note's world and dimension to move it.");
			return;
		}
		Note keep = draft;
		boolean wasNew = isNew;
		Screen parent = returnScreen();
		NotePlacer.start(draft, placed -> {
			placed.world = NoteStore.worldKey();
			placed.dim = StorageStore.here();
			MinecraftClient.getInstance().setScreen(new NoteEditScreen(parent, placed, wasNew));
		}, () -> MinecraftClient.getInstance().setScreen(new NoteEditScreen(parent, keep, wasNew)));
	}

	/** A picture note: the picture as large as fits, and (for whoever may edit) the buttons to pick or remove it. */
	private void drawPictureNote(DrawContext context, int x, int y, int w, int mouseX, int mouseY, boolean editable) {
		int maxH = contentBottom() - y - (editable ? 50 : 24);
		float aspect = NoteImages.aspect(draft);
		int pw = Math.min(w, Math.round(maxH * aspect));
		int ph = Math.round(pw / Math.max(0.3f, aspect));
		var picture = draft.image > 0 ? NoteStore.texture(draft) : null;
		VeloDraw.fillRounded(context, x, y, pw, ph, 4, VeloUi.withAlpha(0xFF000000, 0x40));
		if (picture != null) {
			context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, picture, x, y, 0f, 0f, pw, ph, pw, ph);
		} else {
			String hint = !Note.imagesHere() ? "Pictures are off on this server." : draft.image > 0 ? "Loading the picture..." : "No picture yet.";
			context.drawTextWithShadow(this.textRenderer, hint, x + 8, y + 8, VeloUi.muted());
		}
		if (editable) {
			int by = y + ph + 8;
			hits.add(VeloUi.pill(context, x, by, 130, 18, draft.image > 0 ? "Change picture..." : "Pick a picture...", 1, mouseX, mouseY, this::pickPicture));
			if (draft.image > 0) {
				hits.add(VeloUi.pill(context, x + 136, by, 100, 18, "Remove", 0, mouseX, mouseY, () -> setPicture(null)));
			}
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim("PNG / JPG, shrunk to 256 px. Velo+. Shared pictures can be reported.", w - 250),
					x + 244, by + 5, VeloUi.muted());
		}
	}

	private void pickPicture() {
		if (!Note.plus()) {
			flash("Pictures on notes are Velo+ - try it free for 7 days in the Store.");
			return;
		}
		if (!Note.imagesHere()) {
			flash("This server turned pictures on notes off.");
			return;
		}
		Thread thread = new Thread(() -> {
			try {
				var path = net.veloclient.velo.client.gui.NativeFileDialog.pickImageFile("Picture for the note");
				if (path == null) {
					return;
				}
				byte[] png = NotePictures.shrink(path);
				MinecraftClient.getInstance().execute(() -> setPicture(png));
			} catch (Exception e) {
				MinecraftClient.getInstance().execute(() -> flash("Couldn't read that picture: " + e.getMessage()));
			}
		}, "Velo note picture");
		thread.setDaemon(true);
		thread.start();
	}

	private void setPicture(byte[] png) {
		if (isNew || draft.local || draft.id.startsWith("local-")) {
			draft.png = png == null ? null : java.util.Base64.getEncoder().encodeToString(png);
			draft.image = png == null ? 0 : draft.image + 1;
			return;
		}
		NoteStore.image(draft, png, saved -> {
			draft.image = saved.image;
			flash(png == null ? "Picture removed" : "Picture added");
		}, this::flash);
	}

	private void drawShareTab(DrawContext context, int x, int y, int w, int mouseX, int mouseY, Theme theme) {
		if (!draft.mine) {
			drawSharedWithMe(context, x, y, w, mouseX, mouseY, theme);
			return;
		}
		label(context, "Who sees it", x, y);
		boolean can = NoteStore.canShareHere() || !draft.local;
		int pw = (w - 8) / 3;
		hits.add(VeloUi.pill(context, x, y + 12, pw, 18, "Only me", draft.local ? 1 : 0, mouseX, mouseY, () -> draft.local = true));
		VeloUi.Hit friends = VeloUi.pill(context, x + pw + 4, y + 12, pw, 18, "All my friends", !draft.local && "friends".equals(draft.share) ? 1 : 0, mouseX, mouseY,
				() -> {
					draft.local = false;
					draft.share = "friends";
				});
		VeloUi.Hit picked = VeloUi.pill(context, x + (pw + 4) * 2, y + 12, pw, 18, "Picked people", !draft.local && "people".equals(draft.share) ? 1 : 0, mouseX,
				mouseY, () -> {
					draft.local = false;
					draft.share = "people";
				});
		if (can && !NoteStore.blocked()) {
			hits.add(friends);
			hits.add(picked);
		}
		int ly = y + 38;
		if (NoteStore.blocked()) {
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim("You're blocked from shared notes - private notes still work.", w), x, ly, 0xFFE5604D);
			ly += 14;
		} else if (!can) {
			for (String line : VeloUi.wrap("Sharing works on multiplayer servers while you're signed in to Velo. Private notes work everywhere.", w)) {
				context.drawTextWithShadow(this.textRenderer, line, x, ly, VeloUi.muted());
				ly += 11;
			}
		}
		if (draft.local) {
			deleteButton(context, x, contentBottom() - 40, mouseX, mouseY);
			return;
		}
		label(context, draft.isBoard() ? "Who may draw along (live)" : "Others may", x, ly + 2);
		int ew = (w - 8) / 3;
		hits.add(VeloUi.pill(context, x, ly + 14, ew, 16, draft.isBoard() ? "Only look" : "Only tick the list", "owner".equals(draft.edit) ? 1 : 0, mouseX, mouseY,
				() -> draft.edit = "owner"));
		hits.add(VeloUi.pill(context, x + ew + 4, ly + 14, ew, 16, "Everyone", "everyone".equals(draft.edit) ? 1 : 0, mouseX, mouseY, () -> draft.edit = "everyone"));
		hits.add(VeloUi.pill(context, x + (ew + 4) * 2, ly + 14, ew, 16, "Picked people", "picked".equals(draft.edit) ? 1 : 0, mouseX, mouseY,
				() -> draft.edit = "picked"));
		ly += 40;
		boolean picking = "picked".equals(draft.edit);
		if ("people".equals(draft.share) || picking) {
			boolean people = "people".equals(draft.share);
			label(context, people ? "People  (" + draft.people.size() + ")" + (picking ? "  -  " + draft.editors.size() + " may edit" : "")
					: "Friends who may edit  (" + draft.editors.size() + ")", x, ly);
			if (people) {
				hits.add(VeloUi.pill(context, x + w - 110, ly - 3, 110, 14, "+ My whole party", 0, mouseX, mouseY, this::addParty));
			}
			ly += 14;
			List<SocialClient.Friend> friendList = new ArrayList<>(SocialClient.friends());
			friendList.sort((a, b) -> a.username.compareToIgnoreCase(b.username));
			List<String[]> rows = new ArrayList<>();
			for (SocialClient.Friend friend : friendList) {
				rows.add(new String[] {friend.uuid, friend.username});
			}
			for (String uuid : draft.people) {
				if (rows.stream().noneMatch(r -> r[0].equals(uuid))) {
					rows.add(new String[] {uuid, draft.names.getOrDefault(uuid, "Party member")});
				}
			}
			int listBottom = contentBottom() - 46;
			int ry = ly - (int) listScroll;
			context.enableScissor(x, ly, x + w, listBottom);
			for (String[] row : rows) {
				if (ry + 16 >= ly && ry <= listBottom) {
					boolean in = people ? draft.people.contains(row[0]) : true;
					boolean hovered = VeloUi.inside(mouseX, mouseY, x, ry, w, 16) && mouseY >= ly && mouseY < listBottom;
					VeloDraw.fillRounded(context, x, ry, w, 16, 4, VeloUi.withAlpha(0xFFFFFFFF, hovered ? 0x16 : 0x08));
					if (people) {
						drawBox(context, x + 5, ry + 4, in);
					}
					context.drawTextWithShadow(this.textRenderer, row[1], x + (people ? 18 : 6), ry + 4, theme.text());
					if (picking && in) {
						boolean editor = draft.editors.contains(row[0]);
						String uuid = row[0];
						hits.add(VeloUi.pill(context, x + w - 74, ry + 1, 70, 14, editor ? "Can edit" : "Can view", editor ? 1 : 0, mouseX, mouseY, () -> {
							if (editor) {
								draft.editors.remove(uuid);
							} else {
								draft.editors.add(uuid);
							}
						}));
					}
					if (hovered && people && !(picking && in && mouseX >= x + w - 74)) {
						hits.add(new VeloUi.Hit(x, ry, w, 16, () -> {
							if (in) {
								draft.people.remove(row[0]);
								draft.editors.remove(row[0]);
							} else if (draft.people.size() < 24) {
								draft.people.add(row[0]);
								draft.names.put(row[0], row[1]);
							} else {
								flash("Up to 24 people per note.");
							}
						}));
					}
				}
				ry += 18;
			}
			context.disableScissor();
			if (rows.isEmpty()) {
				context.drawTextWithShadow(this.textRenderer, "Add friends in Velo (or join a party) to share with them.", x, ly + 4, VeloUi.muted());
			}
		}
		deleteButton(context, x, contentBottom() - 40, mouseX, mouseY);
	}

	private void addParty() {
		JsonObject party = SocialClient.party();
		if (party == null || !party.has("members")) {
			flash("You're not in a Velo party.");
			return;
		}
		int added = 0;
		String me = SocialClient.myUuidPublic();
		for (JsonElement element : party.getAsJsonArray("members")) {
			JsonObject member = element.getAsJsonObject();
			String uuid = member.has("uuid") ? member.get("uuid").getAsString() : null;
			if (uuid == null || uuid.equals(me) || draft.people.contains(uuid) || draft.people.size() >= 24) {
				continue;
			}
			draft.people.add(uuid);
			draft.names.put(uuid, member.has("name") ? member.get("name").getAsString() : "Party member");
			added++;
		}
		flash(added == 0 ? "Your party is already on it." : "Added " + added + " party member" + (added == 1 ? "" : "s") + " - save to share.");
	}

	private void deleteButton(DrawContext context, int x, int y, int mouseX, int mouseY) {
		if (isNew) {
			return;
		}
		hits.add(confirmPill(context, x, y, 120, "Delete note", "delete", mouseX, mouseY, () -> NoteStore.delete(original, () -> {
			flash("Deleted");
			this.client.setScreen(returnScreen());
		}, this::flash)));
	}

	/** Someone else's note: who shared it, hide / leave, report. */
	private void drawSharedWithMe(DrawContext context, int x, int y, int w, int mouseX, int mouseY, Theme theme) {
		label(context, "From", x, y);
		context.drawTextWithShadow(this.textRenderer, draft.ownerName + ("friends".equals(draft.share) ? " - shared with all their friends"
				: " - shared with " + draft.people.size() + " people"), x, y + 12, theme.text());
		boolean hidden = NoteStore.hidden(original);
		hits.add(VeloUi.pill(context, x, y + 30, 150, 16, hidden ? "Show it in my world" : "Hide it in my world", 0, mouseX, mouseY,
				() -> NoteStore.setHidden(original, !hidden)));
		if (!"friends".equals(draft.share)) {
			hits.add(confirmPill(context, x + 156, y + 30, 130, "Leave this note", "leave", mouseX, mouseY, () -> NoteStore.delete(original, () ->
					this.client.setScreen(returnScreen()), this::flash)));
		}
		label(context, "Report it to the Velo team", x, y + 64);
		String[][] categories = {{"spam", "Spam"}, {"inappropriate", "Inappropriate"}, {"harassment", "Harassment"}, {"other", "Other"}};
		int cx = x;
		int cw = (w - 12) / 4;
		for (String[] category : categories) {
			hits.add(VeloUi.pill(context, cx, y + 76, cw, 16, category[1], reportCategory.equals(category[0]) ? 1 : 0, mouseX, mouseY,
					() -> reportCategory = category[0]));
			cx += cw + 4;
		}
		net.veloclient.velo.client.gui.widget.VeloStyle.drawInputField(context, x, y + 145, w, 20, reasonField != null && reasonField.isFocused(),
				VeloUi.inside(mouseX, mouseY, x, y + 145, w, 20));
		hits.add(VeloUi.pill(context, x, y + 172, 120, 16, "Send report", 3, mouseX, mouseY, () -> NoteStore.report(original, reportCategory,
				reasonField == null ? "" : reasonField.getText(), () -> flash("Thanks - the Velo team will look at it."), this::flash)));
	}

	private void drawBoardTab(DrawContext context, int x, int y, int w, int mouseX, int mouseY, Theme theme) {
		boardX = x;
		boardY = y + (draft.canEdit ? 26 : 0);
		int fitH = Math.max(60, contentBottom() - boardY - 40);
		boardW = Math.min(Math.min(w, 384), fitH * Note.BOARD_W / Note.BOARD_H);
		boardH = boardW * Note.BOARD_H / Note.BOARD_W;
		if (!Note.whiteboardsHere()) {
			context.drawTextWithShadow(this.textRenderer, "This server turned whiteboards off.", x, y, VeloUi.muted());
			return;
		}
		if (draft.isImage()) {
			context.drawTextWithShadow(this.textRenderer, "Picture notes are just the picture - make a whiteboard note to draw.", x, y, VeloUi.muted());
			return;
		}
		if (draft.canEdit) {
			int px = x;
			for (int i = 0; i < PENS.length; i++) {
				int color = PENS[i];
				int index = i;
				if ((color >>> 24) == 0) {
					hits.add(VeloUi.pill(context, px, y, 48, 18, "Eraser", pen == i ? 1 : 0, mouseX, mouseY, () -> pen = index));
					px += 52;
					continue;
				}
				VeloDraw.fillRounded(context, px, y, 18, 18, 9, color);
				if (pen == i) {
					VeloDraw.strokeRounded(context, px - 2, y - 2, 22, 22, 11, 0xFFFFFFFF);
				}
				hits.add(new VeloUi.Hit(px, y, 18, 18, () -> pen = index));
				px += 22;
			}
			for (int i = 0; i < WIDTHS.length; i++) {
				int index = i;
				hits.add(VeloUi.pill(context, px + 4, y + 1, 24, 16, String.valueOf(WIDTHS[i]), width == i ? 1 : 0, mouseX, mouseY, () -> width = index));
				px += 28;
			}
		}
		List<Note.Stroke> strokes = new ArrayList<>(draft.board);
		strokes.addAll(pending);
		long now = System.currentTimeMillis();
		live.values().removeIf(l -> now - l.at() > 4000);   // a stroke whose finish never came
		for (LiveStroke l : live.values()) {
			strokes.add(l.stroke());
		}
		if (drawing != null) {
			strokes.add(drawing);
		}
		NoteCard.drawBoard(context, strokes, boardX, boardY, boardW, boardH, 255, Integer.MAX_VALUE);
		VeloDraw.strokeRounded(context, boardX, boardY, boardW, boardH, 3, VeloUi.withAlpha(0xFFFFFFFF, 0x30));
		int by = boardY + boardH + 8;
		if (draft.canEdit) {
			hits.add(confirmPill(context, x, by, 90, "Clear board", "clear", mouseX, mouseY, () -> {
				pending.clear();
				if (isNew || draft.local) {
					draft.board.clear();
				} else {
					NoteStore.board(draft, List.of(), true, this::takeBoard, this::flash);
				}
			}));
			hits.add(VeloUi.pill(context, x + 96, by, 70, 16, "Undo", 0, mouseX, mouseY, this::undo));
			String hint = now - lastDrawerAt < 2500 && lastDrawer != null ? lastDrawer + " is drawing..."
					: isNew || draft.local ? "Draw with the mouse - saved with the note." : "Live - everyone who may edit draws along.";
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(hint, w - 180), x + 174, by + 4,
					now - lastDrawerAt < 2500 ? 0xFF7FD6A4 : VeloUi.muted());
		} else if (draft.board.isEmpty()) {
			context.drawTextWithShadow(this.textRenderer, "Nothing drawn yet.", x, by, VeloUi.muted());
		}
	}

	private void undo() {
		if (!pending.isEmpty()) {
			pending.remove(pending.size() - 1);
			return;
		}
		if ((isNew || draft.local) && !draft.board.isEmpty()) {
			draft.board.remove(draft.board.size() - 1);
		} else {
			flash("Shared drawings can't be undone once sent - clear the board instead.");
		}
	}

	// ---- Board input ----

	private int[] boardPoint(double mouseX, double mouseY) {
		int bx = (int) Math.round((mouseX - boardX) * Note.BOARD_W / boardW);
		int by = (int) Math.round((mouseY - boardY) * Note.BOARD_H / boardH);
		return new int[] {Math.max(0, Math.min(Note.BOARD_W, bx)), Math.max(0, Math.min(Note.BOARD_H, by))};
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		for (VeloUi.Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				return true;
			}
		}
		if (boardShown() && draft.canEdit && Note.whiteboardsHere() && VeloUi.inside(click.x(), click.y(), boardX, boardY, boardW, boardH)) {
			if (draft.mine && !Note.plus()) {
				flash("Whiteboards are Velo+ - try it free for 7 days in the Store.");
				return true;
			}
			int[] p = boardPoint(click.x(), click.y());
			drawingSid = UUID.randomUUID().toString().substring(0, 8);
			drawing = new Note.Stroke();
			drawing.c = PENS[pen];
			drawing.w = (PENS[pen] >>> 24) == 0 ? WIDTHS[width] * 3 : WIDTHS[width];
			drawing.p = p;
			return true;
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (drawing != null) {
			int[] p = boardPoint(click.x(), click.y());
			int n = drawing.p.length;
			int dx = p[0] - drawing.p[n - 2];
			int dy = p[1] - drawing.p[n - 1];
			if (dx * dx + dy * dy >= 2 && n < 800) {
				int[] grown = java.util.Arrays.copyOf(drawing.p, n + 2);
				grown[n] = p[0];
				grown[n + 1] = p[1];
				drawing.p = grown;
			}
			long now = System.currentTimeMillis();
			if (!isNew && !draft.local && now - lastLiveSent > 110) {
				lastLiveSent = now;
				NoteStore.live(draft, drawingSid, drawing);
			}
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		if (drawing != null) {
			Note.Stroke done = drawing;
			drawing = null;
			if (isNew || draft.local) {
				draft.board.add(done);
			} else {
				pending.add(done);
				List<Note.Stroke> send = new ArrayList<>(pending);
				NoteStore.board(draft, send, false, List.of(drawingSid), saved -> {
					pending.removeAll(send);
					takeBoard(saved);
				}, error -> {
					pending.removeAll(send);
					flash(error);
				});
			}
			return true;
		}
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (tab == 0 || tab == 2) {
			listScroll = Math.max(0, listScroll - verticalAmount * 18);
			if (tab == 0) {
				layoutContent();
			}
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	// ---- Saving ----

	private void save() {
		if (busy) {
			return;
		}
		syncFields();
		draft.checks.removeIf(c -> c.text == null || c.text.isBlank());
		if (draft.isImage() && draft.image == 0) {
			flash("Pick a picture first.");
			return;
		}
		if (draft.isBoard() && draft.board.isEmpty() && pending.isEmpty()) {
			flash("Draw something first.");
			return;
		}
		if (!draft.isImage() && !draft.isBoard() && (draft.title == null || draft.title.isBlank()) && (draft.text == null || draft.text.isBlank())
				&& draft.checks.isEmpty() && draft.board.isEmpty() && draft.image == 0) {
			flash("Write something first.");
			return;
		}
		if (draft.mine && !"note".equals(draft.kind) && !Note.plus() && !draft.kind.equals(original.kind)) {
			flash("Picture and whiteboard notes are Velo+.");
			return;
		}
		if (!draft.local && "people".equals(draft.share) && draft.people.isEmpty()) {
			flash("Pick at least one person - or share with all your friends.");
			return;
		}
		busy = true;
		NoteStore.save(draft, saved -> {
			busy = false;
			flash("Saved");
			this.client.setScreen(returnScreen());
		}, error -> {
			busy = false;
			flash(error);
		});
	}

	// ---- Helpers ----

	private void label(DrawContext context, String text, int x, int y) {
		context.drawTextWithShadow(this.textRenderer, text, x, y, VeloUi.muted());
	}

	private void drawBox(DrawContext context, int x, int y, boolean done) {
		VeloDraw.strokeRounded(context, x, y, 9, 9, 2, done ? 0xFF4CC38A : 0xFF9AA2B0);
		if (done) {
			VeloDraw.fillRounded(context, x + 2, y + 2, 5, 5, 1, 0xFF4CC38A);
		}
	}

	private VeloUi.Hit confirmPill(DrawContext context, int x, int y, int w, String label, String key, int mouseX, int mouseY, Runnable action) {
		boolean armed = key.equals(confirmKey) && System.currentTimeMillis() < confirmUntil;
		return VeloUi.pill(context, x, y, w, 16, armed ? "Sure?" : label, armed ? 3 : 0, mouseX, mouseY, () -> {
			if (armed) {
				confirmKey = null;
				action.run();
			} else {
				confirmKey = key;
				confirmUntil = System.currentTimeMillis() + 3000;
			}
		});
	}

	private void flash(String message) {
		feedback = message;
		feedbackUntil = System.currentTimeMillis() + 5000;
	}
}
