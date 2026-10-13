package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloScrollRegion;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.redstone.SchematicIO;
import net.veloclient.velo.client.redstone.SchematicPreview;
import net.veloclient.velo.client.schematics.CommunitySchematicsClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Share one of your own schematics with the community: pick it from your schematics (Velo's
 * .veloschem and Litematica's .litematic - no file browser, nothing else can be picked), and it
 * gets a preview picture rendered from its blocks. Give it a title, a description and a few tags
 * and upload it. Links aren't allowed anywhere (the server refuses them too).
 */
public final class ShareSchematicScreen extends VeloWindow {

	private static final ExecutorService IO = Executors.newVirtualThreadPerTaskExecutor();
	private static final long MAX_BYTES = 3L * 1024 * 1024;

	private final List<Path> files;
	private VeloScrollRegion list;
	private Path chosen;
	private byte[] previewPng;
	private volatile Identifier previewTexture;
	private boolean rendering;
	private boolean uploading;
	private TextFieldWidget title;
	private TextFieldWidget description;
	private TextFieldWidget tags;
	private String titleValue = "";
	private String descriptionValue = "";
	private String tagsValue = "";
	private String status = "";

	public ShareSchematicScreen(net.minecraft.client.gui.screen.Screen parent) {
		super(Text.literal("Share a Schematic"), 600, 400);
		returnTo(parent);
		files = SchematicIO.loadable();
	}

	@Override
	protected void layoutContent() {
		if (title != null) {
			titleValue = title.getText();
			descriptionValue = description.getText();
			tagsValue = tags.getText();
		}
		this.clearChildren();
		list = null;
		title = null;
		if (chosen == null) {
			layoutPick();
		} else {
			layoutForm();
		}
	}

	private void layoutPick() {
		int y = contentY() + 16;
		list = new VeloScrollRegion(contentX(), y, contentWidth(), contentBottom() - y - 26);
		for (Path file : files) {
			String name = file.getFileName().toString();
			long size = sizeOf(file);
			boolean tooBig = size > MAX_BYTES;
			String format = name.toLowerCase(java.util.Locale.ROOT).endsWith(".litematic") ? "Litematica" : "Velo";
			VeloButton row = new VeloButton(0, 0, contentWidth() - 10, 20,
					Text.literal(VeloUi.trim(name, contentWidth() - 150) + "   ·   " + format + "  ·  " + Math.max(1, size / 1024) + " KB"
							+ (tooBig ? "  (too big - max 3 MB)" : "")), b -> {
						if (!tooBig) {
							choose(file);
						}
					});
			addSelectableChild(row);
			list.addRow(row);
		}
		list.layoutGrid(1, contentWidth() - 10, 22, 2);
		addDrawableChild(new VeloButton(contentX(), contentBottom() - 20, 90, 20, Text.literal("Cancel"), b -> requestClose()));
	}

	private void choose(Path file) {
		chosen = file;
		previewPng = null;
		previewTexture = null;
		String name = file.getFileName().toString();
		int dot = name.lastIndexOf('.');
		titleValue = (dot > 0 ? name.substring(0, dot) : name).replace('_', ' ').strip();
		descriptionValue = "";
		tagsValue = "";
		rendering = true;
		status = "Rendering the preview...";
		IO.submit(() -> {
			try {
				byte[] png = SchematicPreview.render(file);
				MinecraftClient.getInstance().execute(() -> {
					previewPng = png;
					rendering = false;
					status = "";
					RemoteTextureLoader.fromBytes("share-preview-" + System.nanoTime(), png, id -> previewTexture = id);
				});
			} catch (Exception e) {
				MinecraftClient.getInstance().execute(() -> {
					rendering = false;
					status = "Couldn't read this schematic: " + e.getMessage();
				});
			}
		});
		layoutContent();
	}

	private void layoutForm() {
		int fx = contentX() + 290;
		int fw = contentWidth() - 290;
		int y = contentY() + 12;
		title = new TextFieldWidget(this.textRenderer, fx, y, fw, 16, Text.literal("Title"));
		title.setMaxLength(60);
		title.setText(titleValue);
		addDrawableChild(title);
		y += 34;
		description = new TextFieldWidget(this.textRenderer, fx, y, fw, 16, Text.literal("Description"));
		description.setMaxLength(600);
		description.setPlaceholder(Text.literal("What it does, rates, how to use it..."));
		description.setText(descriptionValue);
		addDrawableChild(description);
		y += 34;
		tags = new TextFieldWidget(this.textRenderer, fx, y, fw, 16, Text.literal("Tags"));
		tags.setMaxLength(130);
		tags.setPlaceholder(Text.literal("kelp, farm, 1.21"));
		tags.setText(tagsValue);
		addDrawableChild(tags);
		VeloButton upload = new VeloButton(fx, contentBottom() - 20, fw, 20, Text.literal(uploading ? "Uploading..." : "Share with the community"),
				b -> upload());
		upload.primary();
		addDrawableChild(upload);
		addDrawableChild(new VeloButton(contentX(), contentBottom() - 20, 120, 20, Text.literal("< Pick another"), b -> {
			chosen = null;
			status = "";
			layoutContent();
		}));
	}

	private void upload() {
		if (uploading || chosen == null) {
			return;
		}
		if (rendering || previewPng == null) {
			status = "Wait for the preview first";
			return;
		}
		String t = title.getText().strip();
		if (t.length() < 3) {
			status = "Give it a title (at least 3 characters)";
			return;
		}
		List<String> tagList = new ArrayList<>();
		for (String tag : tags.getText().split(",")) {
			if (!tag.isBlank()) {
				tagList.add(tag.strip());
			}
		}
		if (tagList.size() > 6) {
			status = "At most 6 tags";
			return;
		}
		uploading = true;
		status = "Uploading...";
		Path file = chosen;
		String d = description.getText().strip();
		byte[] png = previewPng;
		layoutContent();
		IO.submit(() -> {
			try {
				CommunitySchematicsClient.Item item = CommunitySchematicsClient.upload(file, t, d, tagList, png);
				MinecraftClient.getInstance().execute(() -> {
					uploading = false;
					status = "Shared \"" + item.title() + "\" - thanks!";
					chosen = null;
					layoutContent();
				});
			} catch (Exception e) {
				String message = CommunitySchematicsClient.describe(e);
				MinecraftClient.getInstance().execute(() -> {
					uploading = false;
					status = message;
					layoutContent();
				});
			}
		});
	}

	private static long sizeOf(Path file) {
		try {
			return Files.size(file);
		} catch (Exception e) {
			return 0;
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (list != null && list.scroll(mouseX, mouseY, verticalAmount)) {
			list.layoutGrid(1, contentWidth() - 10, 22, 2);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		if (chosen == null) {
			context.drawTextWithShadow(this.textRenderer, files.isEmpty()
					? "No schematics yet - save one in Redstone Mode (File > Save selection) or put .litematic files in your schematics folder"
					: "Pick one of your schematics to share (.veloschem and .litematic)", contentX(), contentY(), VeloStyle.textMuted());
			if (list != null) {
				list.renderRows(context, mouseX, mouseY, delta);
				list.renderScrollbarGrid(context, 1, 22, 2);
			}
		} else {
			int imageW = 276;
			int imageH = 207;
			VeloDraw.fillRounded(context, contentX(), contentY(), imageW, imageH, 6, 0xFF1A1A22);
			if (previewTexture != null) {
				context.drawTexture(RenderPipelines.GUI_TEXTURED, previewTexture, contentX(), contentY(), 0f, 0f, imageW, imageH, imageW, imageH,
						imageW, imageH);
			}
			context.drawTextWithShadow(this.textRenderer, VeloUi.trim(chosen.getFileName().toString(), imageW), contentX(), contentY() + imageH + 6,
					VeloStyle.textMuted());
			List<String> rules = VeloUi.wrap("Shared with every Velo Client player. No links, ads or anything that isn't about the build - "
					+ "those uploads are refused. You can delete it any time under Community > Mine.", imageW);
			for (int i = 0; i < rules.size(); i++) {
				context.drawTextWithShadow(this.textRenderer, rules.get(i), contentX(), contentY() + imageH + 22 + i * 10, VeloStyle.textFaint());
			}
			int fx = contentX() + 290;
			for (String[] label : Arrays.asList(new String[] {"Title", "0"}, new String[] {"Description (optional)", "34"},
					new String[] {"Tags, separated by commas (optional, up to 6)", "68"})) {
				context.drawTextWithShadow(this.textRenderer, label[0], fx, contentY() + Integer.parseInt(label[1]), VeloStyle.textMuted());
			}
		}
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(status, contentWidth() - 130), contentX() + (chosen == null ? 100 : 0),
				contentBottom() - (chosen == null ? 13 : 34), VeloStyle.text());
	}
}
