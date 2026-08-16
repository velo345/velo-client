package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloLabel;
import net.veloclient.velo.client.gui.widget.VeloScrollRegion;
import net.veloclient.velo.client.gui.widget.VeloSchematicSearchTile;
import net.veloclient.velo.client.gui.widget.VeloSchematicTile;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.schematics.PhantomMarketClient;
import net.veloclient.velo.client.schematics.SchematicFormat;
import net.veloclient.velo.client.schematics.SchematicMatch;
import net.veloclient.velo.client.schematics.SchematicMatchCache;
import net.veloclient.velo.client.theme.Theme;
import net.veloclient.velo.client.theme.ThemeManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * In-game counterpart to the launcher's Schematics tab: browse/add/remove
 * {@code .litematic} (Litematica) or {@code .schem} (WorldEdit) files
 * straight from the r-shift menu, no alt-tabbing to the launcher needed -
 * including the same PhantomMarket "Browse online" search the launcher's
 * Schematics tab has (see {@link PhantomMarketClient}). Defaults to
 * whichever of the two mods is actually loaded this session (falls back to
 * Litematica if neither is, or if both are) - see
 * {@link SchematicFormat#defaultFormat()}.
 */
public final class SchematicsScreen extends VeloWindow {

	private static final int TILE_WIDTH = 84;
	private static final int TILE_ICON_HEIGHT = 62;
	private static final int TILE_TOTAL_HEIGHT = TILE_ICON_HEIGHT + 16;
	private static final int SEARCH_TILE_WIDTH = 108;
	private static final int SEARCH_TILE_HEIGHT = 108;
	private static final int TILE_GAP = 8;
	private static final int TOGGLE_ROW_HEIGHT = 20;
	private static final int SEARCH_ROW_HEIGHT = 20;

	private SchematicFormat format = SchematicFormat.defaultFormat();
	private boolean browseMode;
	private String searchQueryText = "";
	private TextFieldWidget searchField;
	private List<PhantomMarketClient.SearchHit> searchResults = List.of();
	private boolean searching;
	private String searchMessage = "Search PhantomMarket, or just press Search to see recent uploads.";

	private VeloScrollRegion scrollRegion;
	private int gridColumns = 1;
	private int currentTileWidth = TILE_WIDTH;
	private int currentTileHeight = TILE_TOTAL_HEIGHT;
	private Text status = Text.literal("");
	private final Set<String> identifyAttempted = new HashSet<>();

	public SchematicsScreen(Screen parent) {
		super(Text.literal("Schematics"), 520, 460);
		returnTo(parent);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();

		int toggleY = contentY();
		int toggleWidth = (contentWidth() - 8) / 2;
		addDrawableChild(new VeloButton(contentX(), toggleY, toggleWidth, TOGGLE_ROW_HEIGHT,
				Text.literal("Litematica"), b -> selectFormat(SchematicFormat.LITEMATICA)).selected(format == SchematicFormat.LITEMATICA));
		addDrawableChild(new VeloButton(contentX() + toggleWidth + 8, toggleY, toggleWidth, TOGGLE_ROW_HEIGHT,
				Text.literal("WorldEdit"), b -> selectFormat(SchematicFormat.WORLDEDIT)).selected(format == SchematicFormat.WORLDEDIT));

		int areaTop = toggleY + TOGGLE_ROW_HEIGHT + 6;

		if (browseMode) {
			int searchButtonWidth = 70;
			searchField = new TextFieldWidget(this.textRenderer, contentX(), areaTop, contentWidth() - searchButtonWidth - 6, SEARCH_ROW_HEIGHT, Text.literal("Search"));
			searchField.setPlaceholder(Text.literal("Search schematics..."));
			searchField.setText(searchQueryText);
			searchField.setChangedListener(s -> searchQueryText = s);
			addDrawableChild(searchField);
			addDrawableChild(new VeloButton(contentX() + contentWidth() - searchButtonWidth, areaTop, searchButtonWidth, SEARCH_ROW_HEIGHT,
					Text.literal(searching ? "..." : "Search"), b -> runSearch()));
			areaTop += SEARCH_ROW_HEIGHT + 6;
			if (searchResults.isEmpty()) {
				addDrawableChild(new VeloLabel(contentX(), areaTop, contentWidth(), 22, searchMessage, 0xFFAAAAAA));
				areaTop += 24;
			}
		} else if (!format.modInstalled()) {
			addDrawableChild(new VeloLabel(contentX(), areaTop, contentWidth(), 22,
					format.displayName() + " isn't installed - schematics still save to the right folder for when you add it.", 0xFFDDAA55));
			areaTop += 24;
		}

		int gridHeight = contentBottom() - areaTop - 36;
		int listWidth = contentWidth();
		currentTileWidth = browseMode ? SEARCH_TILE_WIDTH : TILE_WIDTH;
		currentTileHeight = browseMode ? SEARCH_TILE_HEIGHT : TILE_TOTAL_HEIGHT;
		gridColumns = Math.max(1, (listWidth + TILE_GAP) / (currentTileWidth + TILE_GAP));
		scrollRegion = new VeloScrollRegion(contentX(), areaTop, listWidth, gridHeight);

		if (browseMode) {
			for (PhantomMarketClient.SearchHit hit : searchResults) {
				VeloSchematicSearchTile tile = new VeloSchematicSearchTile(0, 0, SEARCH_TILE_WIDTH, SEARCH_TILE_WIDTH, hit,
						() -> openInBrowser(PhantomMarketClient.postUrl(hit.slug())));
				addSelectableChild(tile);
				scrollRegion.addRow(tile);
			}
		} else {
			Path folder = format.folder();
			Map<String, SchematicMatch> matches = SchematicMatchCache.load(folder);
			for (Path file : listFiles(folder)) {
				String fileName = file.getFileName().toString();
				int dot = fileName.lastIndexOf('.');
				String displayName = dot > 0 ? fileName.substring(0, dot) : fileName;
				SchematicMatch match = matches.get(fileName);
				VeloSchematicTile tile = new VeloSchematicTile(0, 0, TILE_WIDTH, TILE_ICON_HEIGHT, file, displayName, sizeOf(file), match,
						f -> {
							try {
								Files.deleteIfExists(f);
								status = Text.literal("Removed \"" + displayName + "\"");
							} catch (IOException e) {
								status = Text.literal("Couldn't remove: " + e.getMessage());
							}
							layoutContent();
						},
						match != null && match.found() ? () -> openInBrowser(match.postUrl()) : () -> { });
				addSelectableChild(tile);
				scrollRegion.addRow(tile);
				if (match == null && identifyAttempted.add(fileName)) {
					identifyAsync(folder, fileName);
				}
			}

			VeloButton importTile = new VeloButton(0, 0, TILE_WIDTH, TILE_TOTAL_HEIGHT - 4, Text.literal("+ Import"), b -> pickFile());
			addSelectableChild(importTile);
			scrollRegion.addRow(importTile);
		}

		scrollRegion.layoutGrid(gridColumns, currentTileWidth, currentTileHeight, TILE_GAP);

		int bottomY = contentBottom() - 20;
		int bottomWidth = (contentWidth() - 16) / 3;
		addDrawableChild(new VeloButton(contentX(), bottomY, bottomWidth, 20, Text.literal("Open Folder"), b -> openFolder()));
		addDrawableChild(new VeloButton(contentX() + bottomWidth + 8, bottomY, bottomWidth, 20,
				Text.literal(browseMode ? "My Schematics" : "Browse Online"), b -> toggleBrowseMode()));
		addDrawableChild(new VeloButton(contentX() + (bottomWidth + 8) * 2, bottomY, bottomWidth, 20, Text.literal("Done"), b -> requestClose()));
	}

	/** Best-effort, once per filename per screen instance - silently stays a plain filename tile if PhantomMarket doesn't know it or the lookup fails. */
	private void identifyAsync(Path folder, String fileName) {
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			try {
				SchematicMatch match = SchematicMatchCache.identify(fileName);
				Map<String, SchematicMatch> matches = SchematicMatchCache.load(folder);
				matches.put(fileName, match);
				SchematicMatchCache.save(folder, matches);
				MinecraftClient.getInstance().execute(this::layoutContent);
			} catch (IOException ignored) {
				// Network failure - leave uncached so it's retried next time this screen opens.
			}
		});
	}

	private void toggleBrowseMode() {
		browseMode = !browseMode;
		if (browseMode && searchResults.isEmpty() && !searching) {
			runSearch();
		} else {
			layoutContent();
		}
	}

	private void runSearch() {
		if (searching) {
			return;
		}
		searching = true;
		String query = searchQueryText;
		layoutContent();
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			try {
				List<PhantomMarketClient.SearchHit> results = PhantomMarketClient.search(query);
				MinecraftClient.getInstance().execute(() -> {
					searching = false;
					searchResults = results;
					searchMessage = results.isEmpty() ? "No results." : "";
					layoutContent();
				});
			} catch (IOException e) {
				MinecraftClient.getInstance().execute(() -> {
					searching = false;
					searchResults = List.of();
					searchMessage = "Search failed: " + e.getMessage();
					layoutContent();
				});
			}
		});
	}

	/**
	 * Raw OS-specific commands rather than vanilla's own {@code Util}
	 * opener - same reasoning as {@link FileManagerOpener} (see its javadoc):
	 * one less vanilla API surface to keep working across every supported
	 * Minecraft version/mapping set, for a one-off action that doesn't need
	 * anything Minecraft-specific anyway.
	 */
	private void openInBrowser(String url) {
		try {
			String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
			String[] command = osName.contains("win") ? new String[] {"cmd", "/c", "start", "", url}
					: osName.contains("mac") ? new String[] {"open", url}
					: new String[] {"xdg-open", url};
			new ProcessBuilder(command).start();
			status = Text.literal("Opened in your browser - use \"My Schematics > + Import\" once you've downloaded it.");
		} catch (Exception e) {
			status = Text.literal("Couldn't open browser: " + e.getMessage());
		}
	}

	private void selectFormat(SchematicFormat format) {
		this.format = format;
		layoutContent();
	}

	private static List<Path> listFiles(Path folder) {
		try {
			Files.createDirectories(folder);
		} catch (IOException ignored) {
			// Best-effort - the list below just comes back empty if this fails.
		}
		try (Stream<Path> stream = Files.list(folder)) {
			return stream.filter(Files::isRegularFile).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	private static String sizeOf(Path file) {
		try {
			long bytes = Files.size(file);
			return bytes >= 1024 * 1024
					? String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
					: String.format(Locale.ROOT, "%.0f KB", Math.max(1, bytes / 1024.0));
		} catch (IOException e) {
			return "";
		}
	}

	private void openFolder() {
		Path folder = format.folder();
		try {
			Files.createDirectories(folder);
		} catch (IOException ignored) {
			// FileManagerOpener below reports its own failure if the folder truly can't be reached.
		}
		FileManagerOpener.open(folder.toFile(), s -> status = Text.literal(s));
	}

	private void pickFile() {
		SchematicFormat pickedFormat = this.format;
		status = Text.literal("Opening file picker...");
		Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
			try {
				String extensionNoDot = pickedFormat.extension().substring(1);
				Path file = NativeFileDialog.pickFile("Choose a " + pickedFormat.displayName() + " schematic",
						pickedFormat.displayName() + " schematics", extensionNoDot);
				if (file == null) {
					MinecraftClient.getInstance().execute(() -> status = Text.literal(""));
					return;
				}
				MinecraftClient.getInstance().execute(() -> {
					try {
						Path folder = pickedFormat.folder();
						Files.createDirectories(folder);
						Files.copy(file, folder.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
						status = Text.literal("Imported \"" + file.getFileName() + "\"");
						layoutContent();
					} catch (IOException e) {
						status = Text.literal("Import failed: " + e.getMessage());
					}
				});
			} catch (Throwable t) {
				net.veloclient.velo.VeloClient.LOGGER.error("Schematic file picker failed to open", t);
				String message = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
				MinecraftClient.getInstance().execute(() -> status = Text.literal("File picker failed: " + message));
			}
		});
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if (scrollRegion != null && scrollRegion.scroll(mouseX, mouseY, verticalAmount)) {
			scrollRegion.layoutGrid(gridColumns, currentTileWidth, currentTileHeight, TILE_GAP);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		if (scrollRegion != null) {
			scrollRegion.renderRows(context, mouseX, mouseY, delta);
			scrollRegion.renderScrollbarGrid(context, gridColumns, currentTileHeight, TILE_GAP);
		}
		Theme theme = ThemeManager.active();
		context.drawTextWithShadow(this.textRenderer, status, contentX(), contentBottom() - 32, theme.text());
	}
}
