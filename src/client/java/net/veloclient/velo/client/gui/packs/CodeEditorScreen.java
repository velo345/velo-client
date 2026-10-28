package net.veloclient.velo.client.gui.packs;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.FileManagerOpener;
import net.veloclient.velo.client.gui.VeloConfirmScreen;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.util.Keys;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The in-game text / code editor for every non-image file of a pack: JSON (models, block states, item definitions,
 * sounds.json, languages, atlases...), .mcmeta, GLSL shaders, .lang/.properties and plain text. Syntax colours,
 * line numbers, selection, clipboard, undo / redo, find & replace, auto-indent, bracket pairs, line comments,
 * JSON checking (the error line is marked) and formatting.
 */
public final class CodeEditorScreen extends VeloWindow {

	enum Lang { JSON, GLSL, PROPERTIES, PLAIN }

	/** File extensions this editor opens. */
	public static final Set<String> TEXT_TYPES = Set.of("json", "mcmeta", "txt", "lang", "properties", "fsh", "vsh", "glsl", "frag", "vert", "csh",
			"md", "csv", "yml", "yaml", "toml", "cfg", "ini");

	private static final int C_TEXT = 0xFFD4D4D8;
	private static final int C_KEY = 0xFF7CC4FF;
	private static final int C_STRING = 0xFFA5D6A7;
	private static final int C_NUMBER = 0xFFF7C04A;
	private static final int C_LITERAL = 0xFFC792EA;
	private static final int C_PUNCT = 0xFF8A8A96;
	private static final int C_COMMENT = 0xFF6E7681;
	private static final int C_TYPE = 0xFF5FD7D7;
	private static final int C_PREPROCESSOR = 0xFFFF9E64;
	private static final int C_FUNCTION = 0xFF82AAFF;
	private static final Set<String> GLSL_KEYWORDS = Set.of("if", "else", "for", "while", "do", "return", "break", "continue", "discard", "uniform", "in",
			"out", "inout", "const", "struct", "layout", "flat", "smooth", "precision", "highp", "mediump", "lowp", "switch", "case", "default", "true",
			"false", "std140");
	private static final Set<String> GLSL_TYPES = Set.of("void", "bool", "int", "uint", "float", "double", "vec2", "vec3", "vec4", "ivec2", "ivec3",
			"ivec4", "uvec2", "uvec3", "uvec4", "bvec2", "bvec3", "bvec4", "mat2", "mat3", "mat4", "mat2x2", "mat3x3", "mat4x4", "sampler2D", "sampler3D",
			"samplerCube", "sampler2DShadow", "isampler2D", "usampler2D", "samplerBuffer", "isamplerBuffer");
	private static final Pattern JSON_ERROR = Pattern.compile("line (\\d+) column (\\d+)");

	private final PackBrowserScreen browser;
	private final Path file;
	private final String key;
	private final Lang lang;
	private final List<String> lines = new ArrayList<>();
	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private String loadError;
	private String indentUnit = "  ";

	// Caret & selection (line, column in characters).
	private int caretLine;
	private int caretCol;
	private int anchorLine;
	private int anchorCol;
	private int preferredX = -1;
	private long caretMovedAt;

	// View.
	private float scroll;
	private float scrollTarget;
	private int scrollX;
	private int edX;
	private int edY;
	private int edW;
	private int edH;
	private int gutterW;
	private int lineH = 10;
	private boolean selecting;
	private boolean[] commentStart = new boolean[0];
	private int commentRevision = -1;

	// History.
	private record Snapshot(String text, int line, int col) {
	}

	private final Deque<Snapshot> undo = new ArrayDeque<>();
	private final Deque<Snapshot> redo = new ArrayDeque<>();
	private long lastEditAt;
	private String lastEditKind = "";
	private int revision;
	private int savedRevision;

	// Checking.
	private int checkedRevision = -1;
	private long checkDue;
	private int errorLine = -1;
	private String errorText;

	// Find & replace.
	private boolean findOpen;
	private TextFieldWidget findField;
	private TextFieldWidget replaceField;
	private String findText = "";
	private String replaceText = "";
	private boolean matchCase;
	private final List<int[]> matches = new ArrayList<>();
	private int matchRevision = -1;
	private String matchQuery = "";

	private String status = "";
	private long statusAt;
	private boolean discardConfirmed;

	public CodeEditorScreen(PackBrowserScreen browser, Path file, String key) {
		super(Text.literal(file.getFileName().toString()), 1000, 600);
		this.browser = browser;
		this.file = file;
		this.key = key;
		this.lang = langOf(file.getFileName().toString());
		returnTo(browser);
		try {
			String text = Files.readString(file, StandardCharsets.UTF_8);
			if (text.startsWith("﻿")) {
				text = text.substring(1);
			}
			setText(text);
		} catch (IOException e) {
			loadError = "Couldn't open: " + e.getMessage();
			setText("");
		}
		detectIndent();
		checkDue = 0;
	}

	static boolean canEdit(String fileName) {
		int dot = fileName.lastIndexOf('.');
		return dot >= 0 && TEXT_TYPES.contains(fileName.substring(dot + 1).toLowerCase(Locale.ROOT));
	}

	private static Lang langOf(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		if (n.endsWith(".json") || n.endsWith(".mcmeta")) {
			return Lang.JSON;
		}
		if (n.endsWith(".fsh") || n.endsWith(".vsh") || n.endsWith(".glsl") || n.endsWith(".frag") || n.endsWith(".vert") || n.endsWith(".csh")) {
			return Lang.GLSL;
		}
		if (n.endsWith(".lang") || n.endsWith(".properties") || n.endsWith(".cfg") || n.endsWith(".ini") || n.endsWith(".toml")) {
			return Lang.PROPERTIES;
		}
		return Lang.PLAIN;
	}

	// ---- Text model ----

	private void setText(String text) {
		lines.clear();
		for (String line : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
			lines.add(line);
		}
		if (lines.isEmpty()) {
			lines.add("");
		}
	}

	private String text() {
		return String.join("\n", lines);
	}

	private void detectIndent() {
		for (String line : lines) {
			if (line.startsWith("\t")) {
				indentUnit = "\t";
				return;
			}
			if (line.startsWith("    ") && !line.startsWith("     ")) {
				indentUnit = "    ";
				return;
			}
			if (line.startsWith("  ")) {
				indentUnit = "  ";
				return;
			}
		}
	}

	private boolean hasSelection() {
		return anchorLine != caretLine || anchorCol != caretCol;
	}

	/** {startLine, startCol, endLine, endCol} of the selection, in order. */
	private int[] selection() {
		boolean anchorFirst = anchorLine < caretLine || (anchorLine == caretLine && anchorCol <= caretCol);
		return anchorFirst ? new int[] {anchorLine, anchorCol, caretLine, caretCol} : new int[] {caretLine, caretCol, anchorLine, anchorCol};
	}

	private String selectedText() {
		if (!hasSelection()) {
			return "";
		}
		int[] s = selection();
		if (s[0] == s[2]) {
			return lines.get(s[0]).substring(s[1], s[3]);
		}
		StringBuilder sb = new StringBuilder(lines.get(s[0]).substring(s[1]));
		for (int l = s[0] + 1; l < s[2]; l++) {
			sb.append('\n').append(lines.get(l));
		}
		sb.append('\n').append(lines.get(s[2]), 0, s[3]);
		return sb.toString();
	}

	/** Remembers the text before an edit; consecutive edits of the same kind within a moment are one undo step. */
	private void beginEdit(String kind) {
		long now = System.currentTimeMillis();
		if (!kind.equals(lastEditKind) || now - lastEditAt > 900 || kind.equals("other")) {
			undo.push(new Snapshot(text(), caretLine, caretCol));
			while (undo.size() > 150) {
				undo.removeLast();
			}
			redo.clear();
		}
		lastEditKind = kind;
		lastEditAt = now;
	}

	private void changed() {
		revision++;
		checkDue = System.currentTimeMillis() + 300;
		caretMovedAt = System.currentTimeMillis();
		preferredX = -1;
	}

	private void deleteSelection() {
		if (!hasSelection()) {
			return;
		}
		int[] s = selection();
		String head = lines.get(s[0]).substring(0, s[1]);
		String tail = lines.get(s[2]).substring(s[3]);
		for (int l = s[2]; l > s[0]; l--) {
			lines.remove(l);
		}
		lines.set(s[0], head + tail);
		caretLine = anchorLine = s[0];
		caretCol = anchorCol = s[1];
	}

	/** Inserts text (may contain line breaks) at the caret, replacing the selection. */
	private void insert(String text) {
		deleteSelection();
		String line = lines.get(caretLine);
		String head = line.substring(0, caretCol);
		String tail = line.substring(caretCol);
		String[] parts = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
		if (parts.length == 1) {
			lines.set(caretLine, head + parts[0] + tail);
			caretCol += parts[0].length();
		} else {
			lines.set(caretLine, head + parts[0]);
			for (int i = 1; i < parts.length; i++) {
				lines.add(caretLine + i, i == parts.length - 1 ? parts[i] + tail : parts[i]);
			}
			caretLine += parts.length - 1;
			caretCol = parts[parts.length - 1].length();
		}
		anchorLine = caretLine;
		anchorCol = caretCol;
		changed();
	}

	private void moveCaret(int line, int col, boolean extend) {
		caretLine = Math.max(0, Math.min(lines.size() - 1, line));
		caretCol = Math.max(0, Math.min(lines.get(caretLine).length(), col));
		if (!extend) {
			anchorLine = caretLine;
			anchorCol = caretCol;
		}
		caretMovedAt = System.currentTimeMillis();
		ensureCaretVisible();
	}

	private void ensureCaretVisible() {
		int visible = Math.max(1, edH / lineH);
		if (caretLine < scrollTarget) {
			scrollTarget = caretLine;
		} else if (caretLine >= scrollTarget + visible - 1) {
			scrollTarget = caretLine - visible + 2;
		}
		int cx = width(display(lines.get(caretLine).substring(0, caretCol)));
		int textW = edW - gutterW - 12;
		if (cx - scrollX > textW - 20) {
			scrollX = cx - textW + 40;
		} else if (cx - scrollX < 0) {
			scrollX = Math.max(0, cx - 40);
		}
	}

	private static boolean wordChar(char c) {
		return Character.isLetterOrDigit(c) || c == '_';
	}

	private int wordLeft(String line, int col) {
		int i = col;
		while (i > 0 && !wordChar(line.charAt(i - 1))) {
			i--;
		}
		while (i > 0 && wordChar(line.charAt(i - 1))) {
			i--;
		}
		return i;
	}

	private int wordRight(String line, int col) {
		int i = col;
		while (i < line.length() && !wordChar(line.charAt(i))) {
			i++;
		}
		while (i < line.length() && wordChar(line.charAt(i))) {
			i++;
		}
		return i;
	}

	// ---- Display helpers ----

	/** Tabs shown as spaces to the next multiple of 4. */
	private static String display(String s) {
		if (s.indexOf('\t') < 0) {
			return s;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == '\t') {
				int n = 4 - sb.length() % 4;
				sb.append(" ".repeat(n));
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	private int width(String s) {
		return s.isEmpty() ? 0 : this.textRenderer.getWidth(MenuKit.mono(s));
	}

	/** The column under x (relative to the text start) on a line. */
	private int colAt(String line, int x) {
		if (x <= 0) {
			return 0;
		}
		int lo = 0;
		int hi = line.length();
		while (lo < hi) {
			int mid = (lo + hi + 1) / 2;
			int w = width(display(line.substring(0, mid)));
			if (w <= x) {
				lo = mid;
			} else {
				hi = mid - 1;
			}
		}
		if (lo < line.length()) {
			int a = width(display(line.substring(0, lo)));
			int b = width(display(line.substring(0, lo + 1)));
			if (x - a > (b - a) / 2) {
				lo++;
			}
		}
		return lo;
	}

	// ---- Rendering ----

	@Override
	protected void layoutContent() {
		this.clearChildren();
		findField = null;
		replaceField = null;
		if (findOpen) {
			int x = contentX();
			int y = contentY() + 26;
			int w = Math.min(220, (contentWidth() - 260) / 2);
			findField = new TextFieldWidget(this.textRenderer, x + 22, y + 5, w - 26, 12, Text.literal("Find"));
			findField.setDrawsBackground(false);
			findField.setPlaceholder(VeloUi.placeholder("Find"));
			findField.setText(findText);
			findField.setChangedListener(v -> {
				findText = v;
				matchRevision = -1;
			});
			addDrawableChild(findField);
			replaceField = new TextFieldWidget(this.textRenderer, x + w + 14, y + 5, w - 12, 12, Text.literal("Replace"));
			replaceField.setDrawsBackground(false);
			replaceField.setPlaceholder(VeloUi.placeholder("Replace with"));
			replaceField.setText(replaceText);
			replaceField.setChangedListener(v -> replaceText = v);
			addDrawableChild(replaceField);
			setInitialFocus(findField);
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
		lineH = this.textRenderer.fontHeight + 2;

		// Toolbar.
		PackUi.badge(context, x, y + 5, lang == Lang.PLAIN ? "TEXT" : lang.name(), lang == Lang.JSON ? MenuKit.BLUE : lang == Lang.GLSL ? MenuKit.GOLD
				: MenuKit.TEXT_2);
		String path = key != null ? key : file.getFileName().toString();
		MenuKit.text(context, this.textRenderer, MenuKit.small(VeloUi.trim(path, w / 2)), x + 44, y + 7, MenuKit.TEXT_3);
		int bx = x + w;
		bx -= 104;
		hits.add(PackUi.button(context, bx, y + 1, 104, 18, "Save & reload", 1, true, mouseX, mouseY, this::saveAndReload));
		bx -= 64;
		hits.add(PackUi.button(context, bx, y + 1, 60, 18, dirty() ? "Save" : "Saved", 0, dirty(), mouseX, mouseY, this::save));
		bx -= 26;
		hits.add(PackUi.iconButton(context, bx, y + 1, 18, "redo", false, mouseX, mouseY, this::redo));
		bx -= 20;
		hits.add(PackUi.iconButton(context, bx, y + 1, 18, "undo", false, mouseX, mouseY, this::undo));
		bx -= 26;
		hits.add(PackUi.iconButton(context, bx, y + 1, 18, "search", findOpen, mouseX, mouseY, this::toggleFind));
		if (lang == Lang.JSON) {
			bx -= 74;
			hits.add(PackUi.button(context, bx, y + 1, 70, 18, "Format", 0, true, mouseX, mouseY, this::formatJson));
		}
		bx -= 112;
		hits.add(PackUi.button(context, bx, y + 1, 108, 18, "System editor", 0, true, mouseX, mouseY,
				() -> FileManagerOpener.open(file.toFile(), this::setStatus)));

		int top = y + 26;
		if (findOpen) {
			drawFindBar(context, x, top, w, mouseX, mouseY);
			top += 28;
		}
		edX = x;
		edY = top;
		edW = w;
		edH = bottom - 18 - top;
		VeloDraw.fillRounded(context, edX, edY, edW, edH, 8, 0xFF141418);
		gutterW = width(String.valueOf(lines.size())) + 18;
		context.fill(edX + gutterW, edY + 4, edX + gutterW + 1, edY + edH - 4, 0x14FFFFFF);

		int visible = edH / lineH;
		float maxScroll = Math.max(0, lines.size() - visible + 2);
		scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
		scroll += (scrollTarget - scroll) * 0.4f;
		if (Math.abs(scroll - scrollTarget) < 0.01f) {
			scroll = scrollTarget;
		}
		runChecks();
		updateComments();
		if (findOpen) {
			updateMatches();
		}

		int textX = edX + gutterW + 8;
		int textRight = edX + edW - 8;
		context.enableScissor(edX + 1, edY + 2, edX + edW - 1, edY + edH - 2);
		int first = Math.max(0, (int) Math.floor(scroll));
		float offset = (scroll - first) * lineH;
		int[] sel = hasSelection() ? selection() : null;
		for (int l = first; l < Math.min(lines.size(), first + visible + 2); l++) {
			int ly = Math.round(edY + 4 + (l - first) * lineH - offset);
			String line = lines.get(l);
			if (l == caretLine && !hasSelection()) {
				context.fill(textX - 4, ly - 1, textRight, ly + lineH - 1, 0x0CFFFFFF);
			}
			if (l == errorLine) {
				context.fill(edX + 1, ly - 1, edX + edW - 1, ly + lineH - 1, 0x22FF5050);
			}
			// Selection.
			if (sel != null && l >= sel[0] && l <= sel[2]) {
				int from = l == sel[0] ? sel[1] : 0;
				int to = l == sel[2] ? sel[3] : line.length();
				int sx = textX - scrollX + width(display(line.substring(0, from)));
				int ex = textX - scrollX + width(display(line.substring(0, to))) + (l < sel[2] ? 4 : 0);
				context.fill(Math.max(textX - 2, sx), ly - 1, Math.min(textRight, ex), ly + lineH - 1, VeloUi.withAlpha(VeloStyle.accent(), 0x55));
			}
			// Find matches.
			if (findOpen && !findText.isEmpty()) {
				for (int[] m : matches) {
					if (m[0] == l) {
						int sx = textX - scrollX + width(display(line.substring(0, m[1])));
						int ex = textX - scrollX + width(display(line.substring(0, m[1] + findText.length())));
						context.fill(sx, ly - 1, ex, ly + lineH - 1, 0x40F7C04A);
					}
				}
			}
			// Line number.
			Text number = MenuKit.mono(String.valueOf(l + 1));
			MenuKit.text(context, this.textRenderer, number, edX + gutterW - 8 - this.textRenderer.getWidth(number), ly + 1,
					l == caretLine ? MenuKit.TEXT_2 : l == errorLine ? MenuKit.RED_TEXT : 0xFF4E4E58);
			drawLine(context, line, l, textX - scrollX, ly + 1, textX, textRight);
		}
		// Caret.
		boolean blink = (System.currentTimeMillis() - caretMovedAt) % 1000 < 550;
		if (blink && caretLine >= first && caretLine < first + visible + 2) {
			int ly = Math.round(edY + 4 + (caretLine - first) * lineH - offset);
			int cx = textX - scrollX + width(display(lines.get(caretLine).substring(0, caretCol)));
			if (cx >= textX - 1) {
				context.fill(cx, ly - 1, cx + 1, ly + lineH - 1, 0xFFFFFFFF);
			}
		}
		context.disableScissor();
		// Scrollbar.
		if (maxScroll > 0) {
			int barH = Math.max(18, Math.round(edH * visible / (float) (lines.size() + 2)));
			int barY = edY + Math.round((edH - barH) * (scroll / maxScroll));
			VeloDraw.fillRounded(context, edX + edW - 5, barY, 3, barH, 1, 0x40FFFFFF);
			if (errorLine >= 0) {
				int ey = edY + Math.round(edH * errorLine / (float) lines.size());
				context.fill(edX + edW - 7, ey, edX + edW - 1, ey + 2, 0xFFFF5050);
			}
		}

		// Status bar.
		String left = "Ln " + (caretLine + 1) + ", Col " + (caretCol + 1) + "    " + lines.size() + " lines" + (hasSelection() ? "    "
				+ selectedText().length() + " selected" : "");
		MenuKit.text(context, this.textRenderer, MenuKit.small(left), x + 2, bottom - 11, MenuKit.TEXT_3);
		String right;
		int rightColor;
		if (!status.isEmpty() && System.currentTimeMillis() - statusAt < 5000) {
			right = status;
			rightColor = MenuKit.TEXT_2;
		} else if (loadError != null) {
			right = loadError;
			rightColor = MenuKit.RED_TEXT;
		} else if (lang == Lang.JSON) {
			right = errorText == null ? "Valid JSON" : "Line " + (errorLine + 1) + ": " + errorText;
			rightColor = errorText == null ? MenuKit.GREEN : MenuKit.RED_TEXT;
		} else {
			right = indentUnit.equals("\t") ? "Tabs" : indentUnit.length() + " spaces";
			rightColor = MenuKit.TEXT_3;
		}
		Text rt = MenuKit.small(VeloUi.trim(right, w / 2));
		int rx = x + w - this.textRenderer.getWidth(rt) - 2;
		MenuKit.text(context, this.textRenderer, rt, rx, bottom - 11, rightColor);
		if (errorText != null && lang == Lang.JSON) {
			hits.add(new VeloUi.Hit(rx, bottom - 13, this.textRenderer.getWidth(rt), 12, () -> moveCaret(errorLine, 0, false)));
		}
	}

	private void drawFindBar(DrawContext context, int x, int y, int w, int mouseX, int mouseY) {
		int fw = Math.min(220, (w - 260) / 2);
		VeloStyle.drawInputField(context, x, y, fw, 22, findField != null && findField.isFocused(), false);
		PackUi.glyph(context, "search", x + 11, y + 11, 10, MenuKit.TEXT_3);
		VeloStyle.drawInputField(context, x + fw + 8, y, fw, 22, replaceField != null && replaceField.isFocused(), false);
		int bx = x + fw * 2 + 16;
		hits.add(PackUi.iconButton(context, bx, y + 2, 18, "up", false, mouseX, mouseY, () -> findNext(-1)));
		hits.add(PackUi.iconButton(context, bx + 20, y + 2, 18, "down", false, mouseX, mouseY, () -> findNext(1)));
		hits.add(PackUi.button(context, bx + 42, y + 2, 34, 18, "Aa", matchCase ? 1 : 0, true, mouseX, mouseY, () -> {
			matchCase = !matchCase;
			matchRevision = -1;
		}));
		hits.add(PackUi.button(context, bx + 80, y + 2, 64, 18, "Replace", 0, !findText.isEmpty(), mouseX, mouseY, this::replaceOne));
		hits.add(PackUi.button(context, bx + 148, y + 2, 40, 18, "All", 0, !findText.isEmpty(), mouseX, mouseY, this::replaceAll));
		String count = findText.isEmpty() ? "" : matches.size() + (matches.size() == 1 ? " match" : " matches");
		MenuKit.text(context, this.textRenderer, MenuKit.small(count), bx + 194, y + 8, matches.isEmpty() && !findText.isEmpty() ? MenuKit.RED_TEXT
				: MenuKit.TEXT_3);
	}

	/** Draws one line in syntax colours, clipped to [clipLeft, clipRight]. */
	private void drawLine(DrawContext context, String line, int index, int x, int y, int clipLeft, int clipRight) {
		if (line.isEmpty()) {
			return;
		}
		int[] colors = colorize(line, index);
		int start = 0;
		int px = x;
		String shown = display(line);
		boolean tabs = shown.length() != line.length();
		if (tabs) {
			// Rare: colour by the original, draw the tab-expanded text in one go.
			MenuKit.text(context, this.textRenderer, MenuKit.mono(shown), x, y, C_TEXT);
			return;
		}
		while (start < line.length()) {
			int color = colors[start];
			int end = start + 1;
			while (end < line.length() && colors[end] == color) {
				end++;
			}
			String part = line.substring(start, end);
			int pw = width(part);
			if (px + pw >= clipLeft - 50 && px <= clipRight) {
				MenuKit.text(context, this.textRenderer, MenuKit.mono(part), px, y, color);
			}
			px += pw;
			if (px > clipRight) {
				break;
			}
			start = end;
		}
	}

	// ---- Syntax colours ----

	private int[] colorize(String line, int index) {
		int[] c = new int[line.length()];
		java.util.Arrays.fill(c, C_TEXT);
		switch (lang) {
			case JSON -> colorJson(line, c);
			case GLSL -> colorGlsl(line, c, index < commentStart.length && commentStart[index]);
			case PROPERTIES -> colorProperties(line, c);
			default -> {
			}
		}
		return c;
	}

	private static void colorJson(String line, int[] c) {
		int i = 0;
		int n = line.length();
		while (i < n) {
			char ch = line.charAt(i);
			if (ch == '"') {
				int j = i + 1;
				while (j < n && line.charAt(j) != '"') {
					j += line.charAt(j) == '\\' ? 2 : 1;
				}
				j = Math.min(n, j + 1);
				int k = j;
				while (k < n && line.charAt(k) == ' ') {
					k++;
				}
				int color = k < n && line.charAt(k) == ':' ? C_KEY : C_STRING;
				for (int p = i; p < j; p++) {
					c[p] = color;
				}
				i = j;
			} else if (ch == '-' || Character.isDigit(ch)) {
				int j = i + 1;
				while (j < n && (Character.isDigit(line.charAt(j)) || ".eE+-".indexOf(line.charAt(j)) >= 0)) {
					j++;
				}
				for (int p = i; p < j; p++) {
					c[p] = C_NUMBER;
				}
				i = j;
			} else if (Character.isLetter(ch)) {
				int j = i;
				while (j < n && Character.isLetter(line.charAt(j))) {
					j++;
				}
				for (int p = i; p < j; p++) {
					c[p] = C_LITERAL;
				}
				i = j;
			} else {
				if ("{}[],:".indexOf(ch) >= 0) {
					c[i] = C_PUNCT;
				}
				i++;
			}
		}
	}

	private static void colorGlsl(String line, int[] c, boolean inComment) {
		int i = 0;
		int n = line.length();
		if (inComment) {
			int end = line.indexOf("*/");
			int stop = end < 0 ? n : end + 2;
			for (int p = 0; p < stop; p++) {
				c[p] = C_COMMENT;
			}
			i = stop;
		}
		String trimmed = line.stripLeading();
		if (i == 0 && trimmed.startsWith("#")) {
			int start = n - trimmed.length();
			int j = start;
			while (j < n && !Character.isWhitespace(line.charAt(j))) {
				j++;
			}
			for (int p = start; p < j; p++) {
				c[p] = C_PREPROCESSOR;
			}
			i = j;
		}
		while (i < n) {
			char ch = line.charAt(i);
			if (ch == '/' && i + 1 < n && line.charAt(i + 1) == '/') {
				for (int p = i; p < n; p++) {
					c[p] = C_COMMENT;
				}
				return;
			}
			if (ch == '/' && i + 1 < n && line.charAt(i + 1) == '*') {
				int end = line.indexOf("*/", i + 2);
				int stop = end < 0 ? n : end + 2;
				for (int p = i; p < stop; p++) {
					c[p] = C_COMMENT;
				}
				i = stop;
				continue;
			}
			if (ch == '"') {
				int j = line.indexOf('"', i + 1);
				j = j < 0 ? n : j + 1;
				for (int p = i; p < j; p++) {
					c[p] = C_STRING;
				}
				i = j;
			} else if (Character.isDigit(ch) || (ch == '.' && i + 1 < n && Character.isDigit(line.charAt(i + 1)))) {
				int j = i + 1;
				while (j < n && (Character.isLetterOrDigit(line.charAt(j)) || line.charAt(j) == '.')) {
					j++;
				}
				for (int p = i; p < j; p++) {
					c[p] = C_NUMBER;
				}
				i = j;
			} else if (Character.isLetter(ch) || ch == '_') {
				int j = i;
				while (j < n && (Character.isLetterOrDigit(line.charAt(j)) || line.charAt(j) == '_')) {
					j++;
				}
				String word = line.substring(i, j);
				int k = j;
				while (k < n && line.charAt(k) == ' ') {
					k++;
				}
				int color = GLSL_KEYWORDS.contains(word) ? C_LITERAL : GLSL_TYPES.contains(word) ? C_TYPE : k < n && line.charAt(k) == '(' ? C_FUNCTION : C_TEXT;
				for (int p = i; p < j; p++) {
					c[p] = color;
				}
				i = j;
			} else {
				if ("{}()[];,".indexOf(ch) >= 0) {
					c[i] = C_PUNCT;
				}
				i++;
			}
		}
	}

	private static void colorProperties(String line, int[] c) {
		String t = line.stripLeading();
		if (t.startsWith("#") || t.startsWith("!") || t.startsWith(";")) {
			java.util.Arrays.fill(c, C_COMMENT);
			return;
		}
		if (t.startsWith("[")) {
			java.util.Arrays.fill(c, C_LITERAL);
			return;
		}
		int eq = -1;
		for (int i = 0; i < line.length(); i++) {
			if (line.charAt(i) == '=' || line.charAt(i) == ':') {
				eq = i;
				break;
			}
		}
		if (eq >= 0) {
			for (int p = 0; p < eq; p++) {
				c[p] = C_KEY;
			}
			c[eq] = C_PUNCT;
			for (int p = eq + 1; p < line.length(); p++) {
				c[p] = C_STRING;
			}
		}
	}

	/** Which lines start inside a GLSL block comment. */
	private void updateComments() {
		if (lang != Lang.GLSL || commentRevision == revision) {
			return;
		}
		commentRevision = revision;
		commentStart = new boolean[lines.size()];
		boolean in = false;
		for (int l = 0; l < lines.size(); l++) {
			commentStart[l] = in;
			String line = lines.get(l);
			int i = 0;
			while (i < line.length()) {
				if (in) {
					int end = line.indexOf("*/", i);
					if (end < 0) {
						break;
					}
					in = false;
					i = end + 2;
				} else {
					int lineComment = line.indexOf("//", i);
					int start = line.indexOf("/*", i);
					if (start < 0 || (lineComment >= 0 && lineComment < start)) {
						break;
					}
					in = true;
					i = start + 2;
				}
			}
		}
	}

	// ---- Checking & formatting ----

	private void runChecks() {
		if (lang != Lang.JSON || checkedRevision == revision || System.currentTimeMillis() < checkDue) {
			return;
		}
		checkedRevision = revision;
		errorLine = -1;
		errorText = null;
		try {
			JsonReader reader = new JsonReader(new StringReader(text()));
			reader.setLenient(false);
			com.google.gson.TypeAdapter<JsonElement> adapter = new com.google.gson.Gson().getAdapter(JsonElement.class);
			adapter.read(reader);
			if (reader.peek() != JsonToken.END_DOCUMENT) {
				throw new IOException("Extra text after the end at line " + lines.size() + " column 1");
			}
		} catch (Exception e) {
			String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			Matcher m = JSON_ERROR.matcher(message);
			if (m.find()) {
				errorLine = Math.max(0, Math.min(lines.size() - 1, Integer.parseInt(m.group(1)) - 1));
			} else {
				errorLine = lines.size() - 1;
			}
			String clean = message.replaceAll("\\s*at line \\d+ column \\d+.*", "").replace("com.google.gson.stream.MalformedJsonException: ", "");
			if (clean.startsWith("Use JsonReader.setLenient")) {
				clean = "Not valid JSON here";
			}
			errorText = clean.length() > 120 ? clean.substring(0, 120) : clean;
		}
	}

	private void formatJson() {
		runChecksNow();
		if (errorText != null) {
			setStatus("Fix the error first (line " + (errorLine + 1) + ")");
			return;
		}
		try {
			JsonElement json = com.google.gson.JsonParser.parseString(text());
			String formatted = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json);
			if (!indentUnit.equals("  ")) {
				StringBuilder sb = new StringBuilder();
				for (String line : formatted.split("\n", -1)) {
					int spaces = 0;
					while (spaces < line.length() && line.charAt(spaces) == ' ') {
						spaces++;
					}
					sb.append(indentUnit.repeat(spaces / 2)).append(line.substring(spaces)).append('\n');
				}
				formatted = sb.substring(0, sb.length() - 1);
			}
			beginEdit("other");
			setText(formatted);
			moveCaret(Math.min(caretLine, lines.size() - 1), 0, false);
			changed();
			setStatus("Formatted");
		} catch (RuntimeException e) {
			setStatus("Couldn't format: " + e.getMessage());
		}
	}

	private void runChecksNow() {
		checkDue = 0;
		runChecks();
	}

	// ---- Find & replace ----

	private void toggleFind() {
		findOpen = !findOpen;
		if (findOpen && hasSelection() && selection()[0] == selection()[2]) {
			findText = selectedText();
		}
		matchRevision = -1;
		layoutContent();
	}

	private void updateMatches() {
		if (matchRevision == revision && matchQuery.equals(findText + matchCase)) {
			return;
		}
		matchRevision = revision;
		matchQuery = findText + matchCase;
		matches.clear();
		if (findText.isEmpty()) {
			return;
		}
		String q = matchCase ? findText : findText.toLowerCase(Locale.ROOT);
		for (int l = 0; l < lines.size() && matches.size() < 5000; l++) {
			String line = matchCase ? lines.get(l) : lines.get(l).toLowerCase(Locale.ROOT);
			int i = line.indexOf(q);
			while (i >= 0) {
				matches.add(new int[] {l, i});
				i = line.indexOf(q, i + Math.max(1, q.length()));
			}
		}
	}

	private void findNext(int dir) {
		updateMatches();
		if (matches.isEmpty()) {
			return;
		}
		int best = -1;
		if (dir > 0) {
			for (int i = 0; i < matches.size(); i++) {
				int[] m = matches.get(i);
				if (m[0] > caretLine || (m[0] == caretLine && m[1] >= caretCol)) {
					best = i;
					break;
				}
			}
			if (best < 0) {
				best = 0;
			}
		} else {
			int[] s = hasSelection() ? selection() : new int[] {caretLine, caretCol};
			for (int i = matches.size() - 1; i >= 0; i--) {
				int[] m = matches.get(i);
				if (m[0] < s[0] || (m[0] == s[0] && m[1] < s[1])) {
					best = i;
					break;
				}
			}
			if (best < 0) {
				best = matches.size() - 1;
			}
		}
		int[] m = matches.get(best);
		moveCaret(m[0], m[1], false);
		moveCaret(m[0], m[1] + findText.length(), true);
	}

	private void replaceOne() {
		if (hasSelection() && selectedText().equalsIgnoreCase(findText) && (!matchCase || selectedText().equals(findText))) {
			beginEdit("other");
			insert(replaceText);
		}
		findNext(1);
	}

	private void replaceAll() {
		updateMatches();
		if (matches.isEmpty()) {
			return;
		}
		beginEdit("other");
		Pattern p = Pattern.compile(Pattern.quote(findText), matchCase ? 0 : Pattern.CASE_INSENSITIVE);
		int count = matches.size();
		for (int l = 0; l < lines.size(); l++) {
			lines.set(l, p.matcher(lines.get(l)).replaceAll(Matcher.quoteReplacement(replaceText)));
		}
		moveCaret(caretLine, caretCol, false);
		changed();
		setStatus("Replaced " + count);
	}

	// ---- History & saving ----

	private boolean dirty() {
		return revision != savedRevision;
	}

	private void undo() {
		if (undo.isEmpty()) {
			setStatus("Nothing to undo");
			return;
		}
		redo.push(new Snapshot(text(), caretLine, caretCol));
		Snapshot s = undo.pop();
		setText(s.text());
		lastEditKind = "";
		changed();
		moveCaret(s.line(), s.col(), false);
	}

	private void redo() {
		if (redo.isEmpty()) {
			setStatus("Nothing to redo");
			return;
		}
		undo.push(new Snapshot(text(), caretLine, caretCol));
		Snapshot s = redo.pop();
		setText(s.text());
		lastEditKind = "";
		changed();
		moveCaret(s.line(), s.col(), false);
	}

	private boolean save() {
		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, text(), StandardCharsets.UTF_8);
			savedRevision = revision;
			browser.onSaved(key);
			runChecksNow();
			setStatus(errorText != null && lang == Lang.JSON ? "Saved - but the JSON has an error" : "Saved");
			return true;
		} catch (IOException e) {
			setStatus("Couldn't save: " + e.getMessage());
			return false;
		}
	}

	private void saveAndReload() {
		if (save()) {
			browser.applyAndReload();
		}
	}

	private void setStatus(String s) {
		status = s;
		statusAt = System.currentTimeMillis();
	}

	// ---- Input ----

	private int[] posAt(double mouseX, double mouseY) {
		int line = (int) Math.floor((mouseY - edY - 4 + scroll * lineH) / lineH);
		line = Math.max(0, Math.min(lines.size() - 1, line));
		int x = (int) Math.round(mouseX - (edX + gutterW + 8) + scrollX);
		return new int[] {line, colAt(lines.get(line), x)};
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		for (VeloUi.Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				return true;
			}
		}
		if (super.mouseClicked(click, doubled)) {
			return true;
		}
		if (VeloUi.inside(click.x(), click.y(), edX, edY, edW, edH)) {
			setFocused(null);
			int[] p = posAt(click.x(), click.y());
			boolean shift = Keys.isKeyDown(Keys.LEFT_SHIFT) || Keys.isKeyDown(Keys.RIGHT_SHIFT);
			if (doubled) {
				String line = lines.get(p[0]);
				moveCaret(p[0], wordLeft(line, Math.min(line.length(), p[1] + 1)), false);
				moveCaret(p[0], wordRight(line, p[1]), true);
			} else {
				moveCaret(p[0], p[1], shift);
				selecting = true;
			}
			preferredX = -1;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(Click click, double offsetX, double offsetY) {
		if (selecting) {
			int[] p = posAt(click.x(), click.y());
			moveCaret(p[0], p[1], true);
			return true;
		}
		return super.mouseDragged(click, offsetX, offsetY);
	}

	@Override
	public boolean mouseReleased(Click click) {
		selecting = false;
		return super.mouseReleased(click);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (VeloUi.inside(mouseX, mouseY, edX, edY, edW, edH)) {
			if (Keys.isKeyDown(Keys.LEFT_SHIFT) || horizontalAmount != 0) {
				double amount = horizontalAmount != 0 ? -horizontalAmount : verticalAmount;
				scrollX = Math.max(0, scrollX - (int) Math.round(amount * 30));
			} else {
				scrollTarget -= (float) verticalAmount * 3;
			}
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	private boolean fieldFocused() {
		return (findField != null && findField.isFocused()) || (replaceField != null && replaceField.isFocused());
	}

	//? if <26.1 {
	@Override
	public boolean charTyped(net.minecraft.client.input.CharInput input) {
		return typed(input.codepoint()) || super.charTyped(input);
	}
	//?} else {
	/*@Override
	public boolean charTyped(net.minecraft.client.input.CharacterEvent input) {
		return typed(input.codepoint()) || super.charTyped(input);
	}
	*///?}

	private boolean typed(int codepoint) {
		if (fieldFocused() || loadError != null || codepoint < 32 || codepoint == 127) {
			return false;
		}
		String s = new String(Character.toChars(codepoint));
		char ch = s.charAt(0);
		String line = lines.get(caretLine);
		char next = caretCol < line.length() ? line.charAt(caretCol) : 0;
		// Typing a closing bracket / quote that's already there just steps over it.
		if (!hasSelection() && (ch == '}' || ch == ']' || ch == ')' || ch == '"') && next == ch) {
			moveCaret(caretLine, caretCol + 1, false);
			return true;
		}
		beginEdit("type");
		String pair = switch (ch) {
			case '{' -> "}";
			case '[' -> "]";
			case '(' -> ")";
			case '"' -> lang == Lang.JSON || lang == Lang.GLSL ? "\"" : null;
			default -> null;
		};
		boolean closeable = next == 0 || Character.isWhitespace(next) || ",:;)]}".indexOf(next) >= 0;
		if (pair != null && closeable && !hasSelection()) {
			insert(s + pair);
			moveCaret(caretLine, caretCol - 1, false);
		} else if (pair != null && hasSelection() && ch != '"') {
			String inner = selectedText();
			insert(s + inner + pair);
		} else {
			insert(s);
		}
		ensureCaretVisible();
		return true;
	}

	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyInput input) {
		int key = input.key();
		boolean ctrl = (input.modifiers() & Keys.MOD_CONTROL) != 0;
		boolean shift = (input.modifiers() & Keys.MOD_SHIFT) != 0;
		if (ctrl && key == Keys.letter('F')) {
			if (!findOpen) {
				toggleFind();
			} else if (findField != null) {
				setFocused(findField);
				findField.setFocused(true);
			}
			return true;
		}
		if (fieldFocused()) {
			if (key == Keys.ENTER || key == Keys.KP_ENTER) {
				findNext(shift ? -1 : 1);
				return true;
			}
			if (key == Keys.ESCAPE) {
				findOpen = false;
				setFocused(null);
				layoutContent();
				return true;
			}
			return super.keyPressed(input);
		}
		if (key == Keys.ESCAPE) {
			if (findOpen) {
				findOpen = false;
				layoutContent();
				return true;
			}
			if (hasSelection()) {
				moveCaret(caretLine, caretCol, false);
				return true;
			}
			return super.keyPressed(input);
		}
		if (loadError != null) {
			return super.keyPressed(input);
		}
		if (ctrl) {
			if (key == Keys.Z) {
				if (shift) {
					redo();
				} else {
					undo();
				}
				return true;
			}
			if (key == Keys.Y) {
				redo();
				return true;
			}
			if (key == Keys.letter('S')) {
				if (shift) {
					saveAndReload();
				} else {
					save();
				}
				return true;
			}
			if (key == Keys.letter('A')) {
				anchorLine = 0;
				anchorCol = 0;
				moveCaret(lines.size() - 1, lines.get(lines.size() - 1).length(), true);
				return true;
			}
			if (key == Keys.C || key == Keys.letter('X')) {
				String text = hasSelection() ? selectedText() : lines.get(caretLine) + "\n";
				PackBridge.setClipboard(text);
				if (key == Keys.letter('X')) {
					beginEdit("other");
					if (hasSelection()) {
						deleteSelection();
					} else if (lines.size() > 1) {
						lines.remove(caretLine);
						moveCaret(caretLine, 0, false);
					} else {
						lines.set(0, "");
						moveCaret(0, 0, false);
					}
					changed();
				}
				return true;
			}
			if (key == Keys.V) {
				String clip = PackBridge.clipboard();
				if (clip != null && !clip.isEmpty()) {
					beginEdit("other");
					insert(clip);
					ensureCaretVisible();
				}
				return true;
			}
			if (key == Keys.letter('D')) {
				beginEdit("other");
				lines.add(caretLine + 1, lines.get(caretLine));
				moveCaret(caretLine + 1, caretCol, false);
				changed();
				return true;
			}
			if (key == Keys.SLASH && lang == Lang.GLSL) {
				toggleComment();
				return true;
			}
			if (key == Keys.HOME) {
				moveCaret(0, 0, shift);
				return true;
			}
			if (key == Keys.END) {
				moveCaret(lines.size() - 1, lines.get(lines.size() - 1).length(), shift);
				return true;
			}
		}
		String line = lines.get(caretLine);
		if (key == Keys.LEFT) {
			if (hasSelection() && !shift) {
				int[] s = selection();
				moveCaret(s[0], s[1], false);
			} else if (caretCol > 0) {
				moveCaret(caretLine, ctrl ? wordLeft(line, caretCol) : caretCol - 1, shift);
			} else if (caretLine > 0) {
				moveCaret(caretLine - 1, lines.get(caretLine - 1).length(), shift);
			}
			preferredX = -1;
			return true;
		}
		if (key == Keys.RIGHT) {
			if (hasSelection() && !shift) {
				int[] s = selection();
				moveCaret(s[2], s[3], false);
			} else if (caretCol < line.length()) {
				moveCaret(caretLine, ctrl ? wordRight(line, caretCol) : caretCol + 1, shift);
			} else if (caretLine < lines.size() - 1) {
				moveCaret(caretLine + 1, 0, shift);
			}
			preferredX = -1;
			return true;
		}
		if (key == Keys.UP || key == Keys.DOWN || key == Keys.PAGE_UP || key == Keys.PAGE_DOWN) {
			if (preferredX < 0) {
				preferredX = width(display(line.substring(0, caretCol)));
			}
			int page = Math.max(1, edH / lineH - 2);
			int delta = key == Keys.UP ? -1 : key == Keys.DOWN ? 1 : key == Keys.PAGE_UP ? -page : page;
			int target = Math.max(0, Math.min(lines.size() - 1, caretLine + delta));
			int keep = preferredX;
			moveCaret(target, colAt(lines.get(target), keep), shift);
			preferredX = keep;
			if (key == Keys.PAGE_UP || key == Keys.PAGE_DOWN) {
				scrollTarget += delta;
			}
			return true;
		}
		if (key == Keys.HOME) {
			int indent = 0;
			while (indent < line.length() && Character.isWhitespace(line.charAt(indent))) {
				indent++;
			}
			moveCaret(caretLine, caretCol == indent ? 0 : indent, shift);
			return true;
		}
		if (key == Keys.END) {
			moveCaret(caretLine, line.length(), shift);
			return true;
		}
		if (key == Keys.BACKSPACE) {
			beginEdit("delete");
			if (hasSelection()) {
				deleteSelection();
			} else if (caretCol > 0) {
				int from = ctrl ? wordLeft(line, caretCol) : caretCol - 1;
				// Backspace in the leading indent removes one indent step.
				if (!ctrl && line.substring(0, caretCol).isBlank() && indentUnit.length() > 1 && caretCol >= indentUnit.length()
						&& !line.substring(0, caretCol).contains("\t")) {
					from = caretCol - ((caretCol - 1) % indentUnit.length() + 1);
				}
				// Deleting an opening bracket right before its empty pair removes both.
				String pair = caretCol < line.length() ? line.substring(caretCol - 1, caretCol + 1) : "";
				int to = caretCol + (from == caretCol - 1 && (pair.equals("{}") || pair.equals("[]") || pair.equals("()") || pair.equals("\"\"")) ? 1 : 0);
				lines.set(caretLine, line.substring(0, from) + line.substring(to));
				moveCaret(caretLine, from, false);
			} else if (caretLine > 0) {
				String prev = lines.get(caretLine - 1);
				lines.set(caretLine - 1, prev + line);
				lines.remove(caretLine);
				moveCaret(caretLine - 1, prev.length(), false);
			}
			changed();
			return true;
		}
		if (key == Keys.DELETE) {
			beginEdit("delete");
			if (hasSelection()) {
				deleteSelection();
			} else if (caretCol < line.length()) {
				int to = ctrl ? wordRight(line, caretCol) : caretCol + 1;
				lines.set(caretLine, line.substring(0, caretCol) + line.substring(to));
			} else if (caretLine < lines.size() - 1) {
				lines.set(caretLine, line + lines.get(caretLine + 1));
				lines.remove(caretLine + 1);
			}
			changed();
			return true;
		}
		if (key == Keys.ENTER || key == Keys.KP_ENTER) {
			beginEdit("other");
			deleteSelection();
			line = lines.get(caretLine);
			int indent = 0;
			while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) {
				indent++;
			}
			String lead = line.substring(0, Math.min(indent, caretCol));
			char before = caretCol > 0 ? line.charAt(caretCol - 1) : 0;
			char after = caretCol < line.length() ? line.charAt(caretCol) : 0;
			boolean opens = before == '{' || before == '[' || before == '(';
			if (opens && (after == '}' || after == ']' || after == ')')) {
				insert("\n" + lead + indentUnit + "\n" + lead);
				moveCaret(caretLine - 1, lead.length() + indentUnit.length(), false);
			} else {
				insert("\n" + lead + (opens ? indentUnit : ""));
			}
			ensureCaretVisible();
			return true;
		}
		if (key == Keys.TAB) {
			beginEdit("other");
			if (hasSelection() && selection()[0] != selection()[2] || shift) {
				indentLines(!shift);
			} else {
				insert(indentUnit);
			}
			return true;
		}
		return super.keyPressed(input);
	}

	/** Tab / Shift+Tab on the selected lines. */
	private void indentLines(boolean in) {
		int[] s = hasSelection() ? selection() : new int[] {caretLine, caretCol, caretLine, caretCol};
		for (int l = s[0]; l <= s[2]; l++) {
			String line = lines.get(l);
			if (in) {
				lines.set(l, indentUnit + line);
			} else if (line.startsWith(indentUnit)) {
				lines.set(l, line.substring(indentUnit.length()));
			} else if (line.startsWith("\t") || line.startsWith(" ")) {
				lines.set(l, line.substring(1));
			}
		}
		anchorLine = s[0];
		anchorCol = 0;
		moveCaret(s[2], lines.get(s[2]).length(), true);
		changed();
	}

	/** Ctrl+/ for GLSL: comments the selected lines out, or back in. */
	private void toggleComment() {
		beginEdit("other");
		int[] s = hasSelection() ? selection() : new int[] {caretLine, caretCol, caretLine, caretCol};
		boolean all = true;
		for (int l = s[0]; l <= s[2]; l++) {
			if (!lines.get(l).stripLeading().startsWith("//") && !lines.get(l).isBlank()) {
				all = false;
			}
		}
		for (int l = s[0]; l <= s[2]; l++) {
			String line = lines.get(l);
			if (all) {
				int i = line.indexOf("//");
				if (i >= 0) {
					String rest = line.substring(i + 2);
					lines.set(l, line.substring(0, i) + (rest.startsWith(" ") ? rest.substring(1) : rest));
				}
			} else if (!line.isBlank()) {
				int indent = line.length() - line.stripLeading().length();
				lines.set(l, line.substring(0, indent) + "// " + line.substring(indent));
			}
		}
		moveCaret(caretLine, Math.min(caretCol, lines.get(caretLine).length()), false);
		changed();
	}

	// ---- Lifecycle ----

	// ---- Dev-only (screenshot tour) ----

	public void caretForTour(int line, int col) {
		moveCaret(line, col, false);
	}

	public void typeForTour(String text) {
		beginEdit("Type");
		insert(text);
	}

	@Override
	protected void requestClose() {
		if (dirty() && !discardConfirmed && loadError == null) {
			this.client.setScreen(new VeloConfirmScreen(this, "Unsaved changes", "Close without saving your changes to " + file.getFileName() + "?",
					"Discard", () -> {
						discardConfirmed = true;
						requestClose();
					}));
			return;
		}
		super.requestClose();
	}
}
