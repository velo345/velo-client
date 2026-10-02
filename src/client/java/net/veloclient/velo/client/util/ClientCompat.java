package net.veloclient.velo.client.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * The handful of client accessors whose name differs between this project's targets in ways the
 * Stonecutter text rules can't express (see stonecutter.gradle.kts) - kept in one place so the
 * friends/waypoint/queue code doesn't each grow its own three-way {@code //? if} blocks.
 */
public final class ClientCompat {

	private ClientCompat() {
	}

	public static Screen currentScreen() {
		MinecraftClient client = MinecraftClient.getInstance();
		//? if <26.1 {
		return client.currentScreen;
		//?} else if <26.2 {
		/*return client.screen;
		*///?} else {
		/*return client.gui.screen();
		*///?}
	}

	/** The integer GUI scale (screen pixels per GUI unit). */
	public static int guiScale() {
		//? if <26.1 {
		return MinecraftClient.getInstance().getWindow().getScaleFactor();
		//?} else {
		/*return MinecraftClient.getInstance().getWindow().getGuiScale();
		*///?}
	}

	public static boolean isSingleplayer() {
		//? if <26.1 {
		return MinecraftClient.getInstance().isInSingleplayer();
		//?} else {
		/*return MinecraftClient.getInstance().hasSingleplayerServer();
		*///?}
	}

	/** The open singleplayer world's save name, or null when not in singleplayer. */
	public static String singleplayerWorldName() {
		try {
			//? if <26.1 {
			var server = MinecraftClient.getInstance().getServer();
			return server == null ? null : server.getSaveProperties().getLevelName();
			//?} else {
			/*var server = MinecraftClient.getInstance().getSingleplayerServer();
			return server == null ? null : server.getWorldData().getLevelName();
			*///?}
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** The open singleplayer world's save folder (what the world list opens it by), or null. */
	public static String singleplayerFolderName() {
		try {
			//? if <26.1 {
			var server = MinecraftClient.getInstance().getServer();
			var root = server == null ? null : server.getSavePath(net.minecraft.util.WorldSavePath.ROOT);
			//?} else {
			/*var server = MinecraftClient.getInstance().getSingleplayerServer();
			var root = server == null ? null : server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
			*///?}
			if (root == null) {
				return null;
			}
			var folder = root.toAbsolutePath().normalize().getFileName();
			return folder == null ? null : folder.toString();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/** Address as typed in the server list ("play.example.net"), or null when not on a remote server. */
	public static String currentServerAddress() {
		//? if <26.1 {
		var entry = MinecraftClient.getInstance().getCurrentServerEntry();
		return entry == null ? null : entry.address;
		//?} else {
		/*var entry = MinecraftClient.getInstance().getCurrentServer();
		return entry == null ? null : entry.ip;
		*///?}
	}

	public static String currentServerName() {
		//? if <26.1 {
		var entry = MinecraftClient.getInstance().getCurrentServerEntry();
		//?} else {
		/*var entry = MinecraftClient.getInstance().getCurrentServer();
		*///?}
		return entry == null ? null : entry.name;
	}

	public static boolean isRealm() {
		//? if <26.1 {
		var entry = MinecraftClient.getInstance().getCurrentServerEntry();
		//?} else {
		/*var entry = MinecraftClient.getInstance().getCurrentServer();
		*///?}
		return entry != null && entry.isRealm();
	}

	/**
	 * Leaves whatever world/server you're in (saving singleplayer as usual) and connects to
	 * {@code address} - used by "Join" on a friend who's playing on a server.
	 */
	public static void joinServer(String address, String label) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.world != null) {
			//? if <26.1 {
			client.disconnect(net.minecraft.text.Text.literal("Joining a friend"));
			//?} else {
			/*client.disconnectFromWorld(net.minecraft.network.chat.Component.literal("Joining a friend"));
			*///?}
		}
		//? if <26.1 {
		var info = new net.minecraft.client.network.ServerInfo(label, address, net.minecraft.client.network.ServerInfo.ServerType.OTHER);
		net.minecraft.client.gui.screen.multiplayer.ConnectScreen.connect(new net.minecraft.client.gui.screen.TitleScreen(), client,
				net.minecraft.client.network.ServerAddress.parse(address), info, false, null);
		//?} else {
		/*var info = new net.minecraft.client.multiplayer.ServerData(label, address, net.minecraft.client.multiplayer.ServerData.Type.OTHER);
		net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new net.minecraft.client.gui.screens.TitleScreen(), client,
				net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address), info, false, null);
		*///?}
	}

	/** Every stack in the local player's inventory - main, hotbar, armor and offhand. */
	public static java.util.List<net.minecraft.item.ItemStack> inventoryStacks() {
		MinecraftClient client = MinecraftClient.getInstance();
		java.util.List<net.minecraft.item.ItemStack> stacks = new java.util.ArrayList<>();
		if (client.player == null) {
			return stacks;
		}
		var inventory = client.player.getInventory();
		//? if <26.1 {
		for (int i = 0; i < inventory.size(); i++) {
			stacks.add(inventory.getStack(i));
		}
		//?} else {
		/*for (int i = 0; i < inventory.getContainerSize(); i++) {
			stacks.add(inventory.getItem(i));
		}
		*///?}
		return stacks;
	}

	/** Total number of {@code item} across the whole inventory (offhand included). */
	public static int countInInventory(net.minecraft.item.Item item) {
		int total = 0;
		for (net.minecraft.item.ItemStack stack : inventoryStacks()) {
			if (!stack.isEmpty() && stack.getItem() == item) {
				total += stack.getCount();
			}
		}
		return total;
	}

	/** "minecraft:overworld" etc, or null outside a world. */
	public static String dimensionId() {
		MinecraftClient client = MinecraftClient.getInstance();
		return client.world == null ? null : client.world.getRegistryKey().getValue().toString();
	}

	/**
	 * Where a world position sits relative to the live render camera: {@code {viewX, viewY, viewZ,
	 * tanHalfFov}} in camera space (looking down -Z). {@link #project} turns it into screen
	 * coordinates; keeping the raw vector lets callers also point at things behind the camera.
	 */
	public static float[] cameraView(double x, double y, double z) {
		MinecraftClient client = MinecraftClient.getInstance();
		//? if <26.1 {
		var camera = client.gameRenderer.getCamera();
		var position = camera.getCameraPos();
		Quaternionf rotation = new Quaternionf(camera.getRotation());
		float fov = (float) client.options.getFov().getValue().intValue();
		if (client.player != null) {
			fov *= client.player.getFovMultiplier(client.options.getPerspective().isFirstPerson(),
					client.options.getFovEffectScale().getValue().floatValue());
		}
		//?} else if <26.2 {
		/*var camera = client.gameRenderer.getMainCamera();
		var position = camera.position();
		Quaternionf rotation = new Quaternionf(camera.rotation());
		// Method reference, not a call: a bare "getFov()" is rewritten by a Stonecutter rule meant for Options.
		java.util.function.DoubleSupplier fovSource = camera::getFov;
		float fov = (float) fovSource.getAsDouble();
		*///?} else {
		/*var camera = client.gameRenderer.mainCamera();
		var position = camera.position();
		Quaternionf rotation = new Quaternionf(camera.rotation());
		java.util.function.DoubleSupplier fovSource = camera::getFov;
		float fov = (float) fovSource.getAsDouble();
		*///?}
		Vector3f view = new Vector3f((float) (x - position.x), (float) (y - position.y), (float) (z - position.z));
		rotation.conjugate().transform(view);
		float tanHalf = (float) Math.tan(Math.toRadians(Math.max(1f, fov)) / 2.0);
		return new float[] {view.x, view.y, view.z, tanHalf};
	}

	/** Screen position of a {@link #cameraView} result, or null when it's behind the camera. */
	public static float[] project(float[] view, int screenWidth, int screenHeight) {
		if (view[2] >= -0.05f) {
			return null;
		}
		float aspect = screenWidth / (float) Math.max(1, screenHeight);
		float ndcX = view[0] / (-view[2] * view[3] * aspect);
		float ndcY = view[1] / (-view[2] * view[3]);
		return new float[] {(ndcX + 1f) / 2f * screenWidth, (1f - ndcY) / 2f * screenHeight};
	}
}
