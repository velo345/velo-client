package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.report.BugReporter;
import net.veloclient.velo.client.report.CrashCheck;
import net.veloclient.velo.client.report.SpikeRecorder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * "Report a bug" (pause menu) and "Minecraft crashed last time" (title screen, after a crash):
 * the player describes what happened, and the report goes to the Velo team together with the
 * technical details - versions, system and GPU, where they were, mods, enabled modules, the end
 * of the game log and the crash report. Everything that's sent is listed under "What gets sent".
 */
public final class BugReportScreen extends VeloWindow {

	private final Path crashFile;
	private final String crashCause;
	private final Map<String, String> system;
	private final List<VeloUi.Hit> hits = new ArrayList<>();
	private TextFieldWidget titleBox;
	private ClickableWidget messageBox;
	private Supplier<String> messageText = () -> "";
	private boolean includeLogs = true;
	private boolean showDetails;
	private boolean sending;
	private String sentId;
	private String status = "";
	private boolean attachSpikes = true;
	// what was typed before "Record lag spikes" closed the window, so it's back afterwards
	private static String draftTitle = "";
	private static String draftMessage = "";

	public BugReportScreen(Screen parent) {
		this(parent, null);
	}

	/** {@code crashFile} non-null: the "it crashed last time" variant with that crash report attached. */
	public BugReportScreen(Screen parent, Path crashFile) {
		super(Text.literal(crashFile != null ? "Minecraft crashed last time" : "Report a bug"), 470, 340);
		this.crashFile = crashFile;
		this.crashCause = crashFile == null ? null : CrashCheck.cause(crashFile);
		this.system = BugReporter.system();
		returnTo(parent);
	}

	private int introHeight() {
		return crashFile != null ? 44 : 26;
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		if (sentId != null) {
			return;
		}
		String previousTitle = titleBox != null ? titleBox.getText() : draftTitle;
		String previousMessage = titleBox != null ? messageText.get() : draftMessage;
		int x = contentX();
		int y = contentY() + introHeight();
		int w = contentWidth();
		titleBox = new TextFieldWidget(this.textRenderer, x, y, w, 18, Text.literal("Summary"));
		titleBox.setMaxLength(160);
		titleBox.setPlaceholder(net.veloclient.velo.client.gui.widget.VeloUi.placeholder(crashFile != null ? "Short summary (optional)" : "Short summary - e.g. \"World map is black\""));
		titleBox.setText(previousTitle);
		addDrawableChild(titleBox);
		int boxY = y + 24;
		int boxH = Math.max(40, contentBottom() - 78 - boxY);   // room for the box's own character counter above the buttons
		//? if <26.1 {
		//? if <1.21.2 {
		/*net.minecraft.client.gui.widget.EditBoxWidget box = new net.minecraft.client.gui.widget.EditBoxWidget(this.textRenderer, x + 2, boxY + 2, w - 4, boxH - 4, Text.literal("What happened"), Text.empty());
		*///?} else {
		net.minecraft.client.gui.widget.EditBoxWidget box = net.minecraft.client.gui.widget.EditBoxWidget.builder().x(x + 2).y(boxY + 2)
				.hasBackground(false).build(this.textRenderer, w - 4, boxH - 4, Text.literal("What happened"));
		//?}
		box.setMaxLength(4000);
		//?} else {
		/*net.minecraft.client.gui.components.MultiLineEditBox box = net.minecraft.client.gui.components.MultiLineEditBox.builder().setX(x + 2).setY(boxY + 2)
				.setShowBackground(false).build(this.textRenderer, w - 4, boxH - 4, Text.literal("What happened"));
		box.setCharacterLimit(4000);
		*///?}
		box.setText(previousMessage);
		messageText = () -> box.getText();
		messageBox = box;
		addDrawableChild(box);
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		int x = contentX();
		int y = contentY();
		int w = contentWidth();
		if (sentId != null) {
			renderThanks(context, mouseX, mouseY);
			return;
		}
		if (crashFile != null) {
			VeloDraw.fillRounded(context, x, y, w, 36, 8, 0x40E5484D);
			VeloDraw.strokeRounded(context, x, y, w, 36, 8, 0x80E5484D);
			context.drawTextWithShadow(this.textRenderer, "Sending a report helps us fix it. What were you doing?", x + 8, y + 6, VeloStyle.text());
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(crashCause == null ? "No details in the crash report." : crashCause, w - 16),
					x + 8, y + 20, 0xFFFF9A9C);
		} else {
			context.drawTextWithShadow(this.textRenderer, "Tell us what happened and how to make it happen again.", x, y + 4, VeloStyle.textMuted());
		}
		if (messageBox != null) {
			boolean hovered = messageBox.isMouseOver(mouseX, mouseY);
			VeloStyle.drawInputField(context, messageBox.getX() - 2, messageBox.getY() - 2, messageBox.getWidth() + 4, messageBox.getHeight() + 4,
					messageBox.isFocused(), hovered);
		}
		if (messageBox != null && messageText.get().isEmpty() && !messageBox.isFocused()) {
			context.drawTextWithShadow(this.textRenderer, crashFile != null ? "e.g. \"I opened the world map while flying\"" : "What did you do, what did you expect, what happened instead?",
					messageBox.getX() + 5, messageBox.getY() + 5, VeloStyle.textFaint());
		}

		int rowY = contentBottom() - 58;
		hits.add(VeloUi.pill(context, x, rowY, 150, 16, includeLogs ? "Log & crash report: on" : "Log & crash report: off",
				includeLogs ? 1 : 0, mouseX, mouseY, () -> includeLogs = !includeLogs));
		hits.add(VeloUi.pill(context, x + 156, rowY, 110, 16, showDetails ? "Hide details" : "What gets sent", 0, mouseX, mouseY,
				() -> showDetails = !showDetails));
		if (crashFile == null) {
			SpikeRecorder.Result spikes = SpikeRecorder.last();
			String spikeLabel = SpikeRecorder.busy() ? (SpikeRecorder.secondsLeft() >= 0 ? "Recording... " + SpikeRecorder.secondsLeft() + " s" : "Analysing...")
					: spikes != null ? (attachSpikes ? "Lag spikes: attached (" + spikes.spikes() + ")" : "Lag spikes: not attached")
					: "Record lag spikes (60 s)";
			hits.add(VeloUi.pill(context, x + 272, rowY, Math.min(w - 272, 160), 16, spikeLabel, spikes != null && attachSpikes ? 1 : 0, mouseX, mouseY, () -> {
				if (SpikeRecorder.busy()) {
					return;
				}
				if (spikes != null) {
					attachSpikes = !attachSpikes;
					return;
				}
				// keep what was typed, close the window and record while they play
				draftTitle = titleBox.getText();
				draftMessage = messageText.get();
				SpikeRecorder.start(60);
				this.client.setScreen(null);
			}));
		}
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(status, w), x, rowY + 22, status.startsWith("Couldn't") || status.startsWith("Too")
				? 0xFFFF8A8D : VeloStyle.textMuted());

		int buttonY = contentBottom() - 18;
		hits.add(VeloUi.pill(context, x, buttonY, 90, 18, crashFile != null ? "Not now" : "Cancel", 0, mouseX, mouseY, this::requestClose));
		hits.add(VeloUi.pill(context, x + w - 120, buttonY, 120, 18, sending ? "Sending..." : "Send report", 1, mouseX, mouseY, this::send));

	}

	@Override
	protected void renderOverlayLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		// over the text boxes (it was drawn under them, so they showed through)
		if (showDetails && sentId == null) {
			renderDetails(context);
		}
	}

	/** Floating list of everything that's sent, above the toggles. */
	private void renderDetails(DrawContext context) {
		List<String> lines = new ArrayList<>();
		system.forEach((k, v) -> lines.add(k + ": " + v));
		lines.add("Mods: " + BugReporter.mods().size() + ", Velo modules on: " + BugReporter.enabledModules().size());
		if (includeLogs) {
			lines.add("The end of your game log" + (crashFile != null ? " + the crash report" : ""));
		}
		if (crashFile == null && SpikeRecorder.last() != null && attachSpikes) {
			lines.add("The lag spike analysis (frame times, what the game did in slow frames - the recording stays on your PC)");
		} else if (crashFile == null) {
			lines.add("Laggy? \"Record lag spikes\" measures 60 s while you play and adds what caused each spike");
		}
		lines.add("Your Minecraft name (to reply to you)");
		int w = contentWidth();
		int h = lines.size() * 11 + 12;
		int x = contentX();
		int bottom = contentBottom() - 64;
		int y = Math.max(contentY(), bottom - h);
		h = bottom - y;
		VeloDraw.fillRounded(context, x, y, w, h, 10, 0xF51A1A1E);
		VeloDraw.strokeRounded(context, x, y, w, h, 10, VeloStyle.borderStrong());
		int ly = y + 6;
		for (String line : lines) {
			if (ly + 9 > y + h) {
				break;
			}
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(line, w - 14), x + 7, ly, VeloStyle.textMuted());
			ly += 11;
		}
	}

	private void renderThanks(DrawContext context, int mouseX, int mouseY) {
		int cx = contentX() + contentWidth() / 2;
		int y = contentY() + 50;
		VeloDraw.fillRounded(context, cx - 18, y, 36, 36, 18, 0xFF34B273);
		context.drawCenteredTextWithShadow(this.textRenderer, "✔", cx, y + 14, 0xFFFFFFFF);
		context.drawCenteredTextWithShadow(this.textRenderer, "Thanks - your report reached the Velo team!", cx, y + 52, VeloStyle.text());
		context.drawCenteredTextWithShadow(this.textRenderer, "Report #" + sentId + ". We read every one of them.", cx, y + 66, VeloStyle.textMuted());
		hits.add(VeloUi.pill(context, cx - 50, contentBottom() - 18, 100, 18, "Close", 1, mouseX, mouseY, this::requestClose));
	}

	private void send() {
		if (sending) {
			return;
		}
		String message = messageText.get().trim();
		String title = titleBox.getText().trim();
		if (crashFile == null && message.isEmpty() && title.isEmpty() && !(attachSpikes && SpikeRecorder.last() != null)) {
			status = "Describe the bug first - even one sentence helps.";
			return;
		}
		sending = true;
		status = "Sending...";
		SpikeRecorder.Result spikes = crashFile == null && attachSpikes ? SpikeRecorder.last() : null;
		if (title.isEmpty() && message.isEmpty() && spikes != null) {
			title = "Lag spikes (" + spikes.spikes() + " in 60 s)";
		}
		BugReporter.send(crashFile != null ? "crash" : "bug", title.isEmpty() && crashCause != null ? crashCause : title, message, system,
				includeLogs, crashFile, spikes == null ? null : spikes.summary(), id -> {
					sending = false;
					sentId = id;
					draftTitle = "";
					draftMessage = "";
					if (spikes != null) {
						SpikeRecorder.clear();
					}
					layoutContent();
				}, error -> {
					sending = false;
					status = "Couldn't send it: " + error;
				});
	}

	@Override
	protected void requestClose() {
		if (crashFile != null) {
			CrashCheck.markHandled(crashFile);
		}
		super.requestClose();
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
