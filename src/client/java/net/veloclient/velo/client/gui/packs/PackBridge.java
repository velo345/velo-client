package net.veloclient.velo.client.gui.packs;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every Minecraft call the pack screens and the texture editor make, per mapping, in one place: the
 * resource pack list (vanilla's own selection model, so pinned / required packs, insert positions and
 * the high-contrast option behave exactly like the vanilla screen), pack icons, reading game assets
 * and baked entity models. The rest of the pack tools only see {@code Object} handles, strings and pixels.
 */
public final class PackBridge {

	private PackBridge() {
	}

	// ---- The pack list ----

	/** Vanilla's selection model plus the icons it loaded. */
	public static final class Model {
		final Object handle;
		final Map<String, Identifier> icons;

		private Model(Object handle, Map<String, Identifier> icons) {
			this.handle = handle;
			this.icons = icons;
		}
	}

	public static Model newModel(Runnable onChanged) {
		MinecraftClient client = MinecraftClient.getInstance();
		Map<String, Identifier> icons = new HashMap<>();
		//? if <26.1 {
		var organizer = new net.minecraft.client.gui.screen.pack.ResourcePackOrganizer(pack -> onChanged.run(),
				profile -> icons.computeIfAbsent(profile.getId(), id -> loadIcon(profile)), client.getResourcePackManager(),
				manager -> client.options.refreshResourcePacks(manager));
		//?} else {
		/*var organizer = new net.minecraft.client.gui.screens.packs.PackSelectionModel(pack -> onChanged.run(),
				pack -> icons.computeIfAbsent(pack.getId(), id -> loadIcon(pack)), client.getResourcePackRepository(),
				repository -> client.options.updateResourcePacks(repository));
		*///?}
		return new Model(organizer, icons);
	}

	/** Re-reads the packs folder (new / removed packs), like vanilla's directory watcher. */
	public static void rescan(Model model) {
		//? if <26.1 {
		((net.minecraft.client.gui.screen.pack.ResourcePackOrganizer) model.handle).refresh();
		//?} else {
		/*((net.minecraft.client.gui.screens.packs.PackSelectionModel) model.handle).findNewPacks();
		*///?}
		releaseIcons(model);
	}

	/** Applies the selection (saves options, reloads resources if the list changed). */
	public static void commit(Model model) {
		//? if <26.1 {
		((net.minecraft.client.gui.screen.pack.ResourcePackOrganizer) model.handle).apply();
		//?} else {
		/*((net.minecraft.client.gui.screens.packs.PackSelectionModel) model.handle).commit();
		*///?}
	}

	/** Selected packs, top (highest priority) first. */
	public static List<Object> selected(Model model) {
		List<Object> out = new ArrayList<>();
		//? if <26.1 {
		((net.minecraft.client.gui.screen.pack.ResourcePackOrganizer) model.handle).getEnabledPacks().forEach(out::add);
		//?} else {
		/*((net.minecraft.client.gui.screens.packs.PackSelectionModel) model.handle).getSelected().forEach(out::add);
		*///?}
		return out;
	}

	public static List<Object> available(Model model) {
		List<Object> out = new ArrayList<>();
		//? if <26.1 {
		((net.minecraft.client.gui.screen.pack.ResourcePackOrganizer) model.handle).getDisabledPacks().forEach(out::add);
		//?} else {
		/*((net.minecraft.client.gui.screens.packs.PackSelectionModel) model.handle).getUnselected().forEach(out::add);
		*///?}
		return out;
	}

	public static void releaseIcons(Model model) {
		for (Identifier id : model.icons.values()) {
			if (!id.equals(UNKNOWN_ICON)) {
				MinecraftClient.getInstance().getTextureManager().destroyTexture(id);
			}
		}
		model.icons.clear();
	}

	//? if <26.1 {
	private static net.minecraft.client.gui.screen.pack.ResourcePackOrganizer.Pack e(Object entry) {
		return (net.minecraft.client.gui.screen.pack.ResourcePackOrganizer.Pack) entry;
	}
	//?} else {
	/*private static net.minecraft.client.gui.screens.packs.PackSelectionModel.Entry e(Object entry) {
		return (net.minecraft.client.gui.screens.packs.PackSelectionModel.Entry) entry;
	}
	*///?}

	public static String id(Object entry) {
		//? if <26.1 {
		return e(entry).getName();
		//?} else {
		/*return e(entry).getId();
		*///?}
	}

	public static Text title(Object entry) {
		//? if <26.1 {
		return e(entry).getDisplayName();
		//?} else {
		/*return e(entry).getTitle();
		*///?}
	}

	public static Text description(Object entry) {
		return e(entry).getDescription();
	}

	public static Identifier icon(Object entry) {
		//? if <26.1 {
		return e(entry).getIconId();
		//?} else {
		/*return e(entry).getIconTexture();
		*///?}
	}

	public static boolean compatible(Object entry) {
		return e(entry).getCompatibility().isCompatible();
	}

	/** Why the pack is incompatible ("made for a newer version"), shown on its row. */
	public static Text compatibilityNote(Object entry) {
		//? if <26.1 {
		return e(entry).getCompatibility().getNotification();
		//?} else {
		/*return e(entry).getCompatibility().getDescription();
		*///?}
	}

	/** The question asked before selecting an incompatible pack. */
	public static Text compatibilityQuestion(Object entry) {
		//? if <26.1 {
		return e(entry).getCompatibility().getConfirmMessage();
		//?} else {
		/*return e(entry).getCompatibility().getConfirmation();
		*///?}
	}

	/** Pinned to its place (can't be moved past). */
	public static boolean fixed(Object entry) {
		//? if <26.1 {
		return e(entry).isPinned();
		//?} else {
		/*return e(entry).isFixedPosition();
		*///?}
	}

	/** Always selected (vanilla's own resources). */
	public static boolean required(Object entry) {
		//? if <26.1 {
		return e(entry).isAlwaysEnabled();
		//?} else {
		/*return e(entry).isRequired();
		*///?}
	}

	public static boolean isSelected(Object entry) {
		//? if <26.1 {
		return e(entry).isEnabled();
		//?} else {
		/*return e(entry).isSelected();
		*///?}
	}

	public static boolean canSelect(Object entry) {
		//? if <26.1 {
		return e(entry).canBeEnabled();
		//?} else {
		/*return e(entry).canSelect();
		*///?}
	}

	public static boolean canUnselect(Object entry) {
		//? if <26.1 {
		return e(entry).canBeDisabled();
		//?} else {
		/*return e(entry).canUnselect();
		*///?}
	}

	public static void select(Object entry) {
		//? if <26.1 {
		e(entry).enable();
		//?} else {
		/*e(entry).select();
		*///?}
	}

	public static void unselect(Object entry) {
		//? if <26.1 {
		e(entry).disable();
		//?} else {
		/*e(entry).unselect();
		*///?}
	}

	public static boolean canMoveUp(Object entry) {
		//? if <26.1 {
		return e(entry).canMoveTowardStart();
		//?} else {
		/*return e(entry).canMoveUp();
		*///?}
	}

	public static boolean canMoveDown(Object entry) {
		//? if <26.1 {
		return e(entry).canMoveTowardEnd();
		//?} else {
		/*return e(entry).canMoveDown();
		*///?}
	}

	public static void moveUp(Object entry) {
		//? if <26.1 {
		e(entry).moveTowardStart();
		//?} else {
		/*e(entry).moveUp();
		*///?}
	}

	public static void moveDown(Object entry) {
		//? if <26.1 {
		e(entry).moveTowardEnd();
		//?} else {
		/*e(entry).moveDown();
		*///?}
	}

	// ---- Icons ----

	//? if <26.1 {
	static final Identifier UNKNOWN_ICON = Identifier.ofVanilla("textures/misc/unknown_pack.png");
	//?} else {
	/*static final Identifier UNKNOWN_ICON = Identifier.withDefaultNamespace("textures/misc/unknown_pack.png");
	*///?}
	private static int iconCounter;

	//? if <26.1 {
	private static Identifier loadIcon(net.minecraft.resource.ResourcePackProfile profile) {
		try (net.minecraft.resource.ResourcePack pack = profile.createResourcePack()) {
			var supplier = pack.openRoot("pack.png");
			if (supplier == null) {
				return UNKNOWN_ICON;
			}
			try (InputStream in = supplier.get()) {
				return registerIcon(NativeImage.read(in));
			}
		} catch (Exception e) {
			return UNKNOWN_ICON;
		}
	}
	//?} else if <26.3 {
	/*private static Identifier loadIcon(net.minecraft.server.packs.repository.Pack profile) {
		try (net.minecraft.server.packs.PackResources pack = profile.open()) {
			var supplier = pack.getRootResource("pack.png");
			if (supplier == null) {
				return UNKNOWN_ICON;
			}
			try (InputStream in = supplier.get()) {
				return registerIcon(NativeImage.read(in));
			}
		} catch (Exception e) {
			return UNKNOWN_ICON;
		}
	}
	*///?} else {
	/*private static Identifier loadIcon(net.minecraft.server.packs.repository.Pack profile) {
		try (net.minecraft.server.packs.PackMetadataResources pack = profile.openMetadata()) {
			var supplier = pack.getRootResource("pack.png");
			if (supplier == null) {
				return UNKNOWN_ICON;
			}
			try (InputStream in = supplier.get()) {
				return registerIcon(NativeImage.read(in));
			}
		} catch (Exception e) {
			return UNKNOWN_ICON;
		}
	}
	*///?}

	private static Identifier registerIcon(NativeImage image) {
		Identifier id = Identifier.of("velo-client", "pack_icon/" + (iconCounter++));
		MinecraftClient.getInstance().getTextureManager().registerTexture(id, new NativeImageBackedTexture(() -> "velo pack icon", image));
		return id;
	}

	// ---- Screens, folders, reloads ----

	/** Vanilla's resource pack screen (not the data pack one, which shares the class). */
	public static boolean isResourcePackScreen(Screen screen) {
		//? if <26.1 {
		boolean packScreen = screen instanceof net.minecraft.client.gui.screen.pack.PackScreen;
		//?} else {
		/*boolean packScreen = screen instanceof net.minecraft.client.gui.screens.packs.PackSelectionScreen;
		*///?}
		return packScreen && screen.getTitle().getString().equals(Text.translatable("resourcePack.title").getString());
	}

	/** Closes vanilla's pack screen without leaving its folder watcher open (applies its unchanged selection). */
	public static void closeVanillaScreen(Screen screen) {
		//? if <26.1 {
		screen.close();
		//?} else {
		/*screen.onClose();
		*///?}
	}

	/** Opens vanilla's own resource pack screen ("Classic screen"), returning to {@code parent}. */
	public static void openVanillaScreen(Screen parent) {
		MinecraftClient client = MinecraftClient.getInstance();
		//? if <26.1 {
		client.setScreen(new net.minecraft.client.gui.screen.pack.PackScreen(client.getResourcePackManager(), manager -> {
			client.options.refreshResourcePacks(manager);
			client.setScreen(parent);
		}, client.getResourcePackDir(), Text.translatable("resourcePack.title")));
		//?} else {
		/*client.setScreen(new net.minecraft.client.gui.screens.packs.PackSelectionScreen(client.getResourcePackRepository(), repository -> {
			client.options.updateResourcePacks(repository);
			client.setScreen(parent);
		}, client.getResourcePackDirectory(), Text.translatable("resourcePack.title")));
		*///?}
	}

	public static Path packDir() {
		//? if <26.1 {
		return MinecraftClient.getInstance().getResourcePackDir();
		//?} else {
		/*return net.minecraft.client.Minecraft.getInstance().getResourcePackDirectory();
		*///?}
	}

	/** Reloads every resource (what F3+T does) - picks up edited files of packs that stay selected. */
	public static void reloadResources() {
		MinecraftClient client = MinecraftClient.getInstance();
		client.reloadResources();
	}

	/** The resource pack format number of this game version (for new packs' pack.mcmeta). */
	public static int packFormat() {
		//? if <26.1 {
		return net.minecraft.SharedConstants.getGameVersion().packVersion(net.minecraft.resource.ResourceType.CLIENT_RESOURCES).major();
		//?} else {
		/*return net.minecraft.SharedConstants.getCurrentVersion().packVersion(net.minecraft.server.packs.PackType.CLIENT_RESOURCES).major();
		*///?}
	}

	// ---- Game assets (everything currently loaded: vanilla + selected packs) ----

	/** "namespace:path" of every loaded asset under {@code folder} whose path ends with {@code suffix}. */
	public static List<String> listGame(String folder, String suffix) {
		List<String> out = new ArrayList<>();
		try {
			//? if <26.1 {
			var found = MinecraftClient.getInstance().getResourceManager().findResources(folder, id -> id.getPath().endsWith(suffix));
			//?} else {
			/*var found = net.minecraft.client.Minecraft.getInstance().getResourceManager().listResources(folder, id -> id.getPath().endsWith(suffix));
			*///?}
			for (var id : found.keySet()) {
				out.add(id.getNamespace() + ":" + id.getPath());
			}
		} catch (RuntimeException ignored) {
			// Reloading right now: nothing listed this time.
		}
		return out;
	}

	/** The bytes of a loaded asset ("namespace:path"), or null. */
	public static byte[] readGame(String nsPath) {
		int colon = nsPath.indexOf(':');
		String ns = colon < 0 ? "minecraft" : nsPath.substring(0, colon);
		String path = colon < 0 ? nsPath : nsPath.substring(colon + 1);
		try {
			Identifier id = Identifier.of(ns, path);
			//? if <26.1 {
			Optional<net.minecraft.resource.Resource> resource = MinecraftClient.getInstance().getResourceManager().getResource(id);
			if (resource.isEmpty()) {
				return null;
			}
			try (InputStream in = resource.get().getInputStream()) {
				return in.readAllBytes();
			}
			//?} else {
			/*Optional<net.minecraft.server.packs.resources.Resource> resource = net.minecraft.client.Minecraft.getInstance().getResourceManager().getResource(id);
			if (resource.isEmpty()) {
				return null;
			}
			try (InputStream in = resource.get().open()) {
				return in.readAllBytes();
			}
			*///?}
		} catch (Exception e) {
			return null;
		}
	}

	/** Every loaded copy of an asset, lowest priority (vanilla) first - for files the game merges, like sounds.json. */
	public static List<byte[]> readGameStack(String nsPath) {
		List<byte[]> out = new ArrayList<>();
		int colon = nsPath.indexOf(':');
		try {
			Identifier id = Identifier.of(nsPath.substring(0, colon), nsPath.substring(colon + 1));
			//? if <26.1 {
			for (net.minecraft.resource.Resource resource : MinecraftClient.getInstance().getResourceManager().getAllResources(id)) {
				try (InputStream in = resource.getInputStream()) {
					out.add(in.readAllBytes());
				}
			}
			//?} else {
			/*for (net.minecraft.server.packs.resources.Resource resource : net.minecraft.client.Minecraft.getInstance().getResourceManager().getResourceStack(id)) {
				try (InputStream in = resource.open()) {
					out.add(in.readAllBytes());
				}
			}
			*///?}
		} catch (Exception ignored) {
			// Whatever was read so far.
		}
		return out;
	}

	// ---- Clipboard ----

	public static String clipboard() {
		//? if <26.1 {
		return MinecraftClient.getInstance().keyboard.getClipboard();
		//?} else {
		/*return net.minecraft.client.Minecraft.getInstance().keyboardHandler.getClipboard();
		*///?}
	}

	public static void setClipboard(String text) {
		//? if <26.1 {
		MinecraftClient.getInstance().keyboard.setClipboard(text);
		//?} else {
		/*net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(text);
		*///?}
	}

	// ---- Entity models (for the 3D preview of entity textures) ----

	/** Every baked entity model layer as "namespace:path#layer". */
	public static List<String> entityLayers() {
		List<String> out = new ArrayList<>();
		//? if <26.1 {
		net.minecraft.client.render.entity.model.EntityModelLayers.getLayers().forEach(l -> out.add(l.id() + "#" + l.name()));
		//?} else {
		/*net.minecraft.client.model.geom.ModelLayers.getKnownLocations().forEach(l -> out.add(l.model() + "#" + l.layer()));
		*///?}
		return out;
	}

	/**
	 * The quads of an entity model layer in its default pose: per quad 4 vertices of (x, y, z, u, v), positions in
	 * blocks (y down, like the game's models), UVs 0..1. Null if the layer isn't known.
	 */
	public static float[] entityQuads(String layer) {
		List<Float> out = new ArrayList<>();
		try {
			//? if <26.1 {
			var key = net.minecraft.client.render.entity.model.EntityModelLayers.getLayers()
					.filter(l -> (l.id() + "#" + l.name()).equals(layer)).findFirst().orElse(null);
			if (key == null) {
				return null;
			}
			net.minecraft.client.model.ModelPart root = MinecraftClient.getInstance().getLoadedEntityModels().getModelPart(key);
			root.forEachCuboid(new net.minecraft.client.util.math.MatrixStack(), (pose, path, index, cuboid) -> {
				org.joml.Matrix4f matrix = pose.getPositionMatrix();
				for (net.minecraft.client.model.ModelPart.Quad quad : cuboid.sides) {
					for (net.minecraft.client.model.ModelPart.Vertex vertex : quad.vertices()) {
						org.joml.Vector3f p = matrix.transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), new org.joml.Vector3f());
						out.add(p.x);
						out.add(p.y);
						out.add(p.z);
						out.add(vertex.u());
						out.add(vertex.v());
					}
				}
			});
			//?} else {
			/*var key = net.minecraft.client.model.geom.ModelLayers.getKnownLocations()
					.filter(l -> (l.model() + "#" + l.layer()).equals(layer)).findFirst().orElse(null);
			if (key == null) {
				return null;
			}
			net.minecraft.client.model.geom.ModelPart root = net.minecraft.client.Minecraft.getInstance().getEntityModels().bakeLayer(key);
			root.visit(new com.mojang.blaze3d.vertex.PoseStack(), (pose, path, index, cube) -> {
				org.joml.Matrix4f matrix = pose.pose();
				for (net.minecraft.client.model.geom.ModelPart.Polygon polygon : cube.polygons) {
					for (net.minecraft.client.model.geom.ModelPart.Vertex vertex : polygon.vertices()) {
						org.joml.Vector3f p = matrix.transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), new org.joml.Vector3f());
						out.add(p.x);
						out.add(p.y);
						out.add(p.z);
						out.add(vertex.u());
						out.add(vertex.v());
					}
				}
			});
			*///?}
		} catch (RuntimeException e) {
			return null;
		}
		float[] quads = new float[out.size()];
		for (int i = 0; i < quads.length; i++) {
			quads[i] = out.get(i);
		}
		return quads;
	}
}
