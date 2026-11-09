package net.veloclient.velo.dev.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.Text;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.config.VeloPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The server's mod hints with something to do about them: "Remove & relaunch" moves the forbidden mods out of the
 * mods folder, "Add & relaunch" installs the recommended ones from Modrinth - both done by the Velo launcher after the
 * game closes (jar files can't be swapped while the game uses them), which then starts the profile again and rejoins
 * the server. Without the launcher (other launchers) it's only the notice.
 */
final class BridgeModsScreen extends VeloWindow {

	private final List<BridgeModRules.Hit> bad;
	private final List<BridgeModRules.Hit> missing;
	private final String message;
	private final Runnable hide;
	private List<String> lines = List.of();

	BridgeModsScreen(String title, List<BridgeModRules.Hit> bad, List<BridgeModRules.Hit> missing, String message, Runnable hide) {
		super(Text.literal(title), 360, height(bad, missing, message));
		this.bad = bad;
		this.missing = missing;
		this.message = message == null ? "" : message;
		this.hide = hide;
	}

	private static int height(List<BridgeModRules.Hit> bad, List<BridgeModRules.Hit> missing, String message) {
		int text = (bad.isEmpty() ? 0 : 2 + (message == null ? 0 : message.length() / 50)) + (missing.isEmpty() ? 0 : 2) + 2;
		int buttons = (bad.isEmpty() ? 0 : 1) + (installable(missing).isEmpty() ? 0 : 1) + 1;
		return Math.min(320, 70 + text * 11 + buttons * 24);
	}

	private static List<BridgeModRules.Hit> installable(List<BridgeModRules.Hit> missing) {
		List<BridgeModRules.Hit> out = new ArrayList<>();
		for (BridgeModRules.Hit h : missing) {
			if (h.modrinth() != null && !h.modrinth().isBlank()) {
				out.add(h);
			}
		}
		return out;
	}

	private static String names(List<BridgeModRules.Hit> hits) {
		List<String> n = new ArrayList<>();
		hits.forEach(h -> n.add(h.label()));
		return String.join(", ", n);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		StringBuilder body = new StringBuilder();
		if (!bad.isEmpty()) {
			body.append("Not allowed here: ").append(names(bad)).append('.');
			if (!message.isBlank()) {
				body.append(' ').append(message);
			}
		}
		if (!missing.isEmpty()) {
			body.append(body.isEmpty() ? "" : "\n\n").append("Recommended here: ").append(names(missing)).append('.');
		}
		boolean launcher = net.veloclient.velo.client.hosting.WorldMods.launchedByVelo();
		body.append("\n\n").append(launcher ? "Velo can fix this for you - the game restarts and joins again."
				: "This comes from the server - change your mods folder to follow it.");
		lines = VeloUi.wrap(body.toString(), contentWidth());
		int w = contentWidth();
		int y = contentBottom() - 20;
		int half = (w - 8) / 2;
		addDrawableChild(new VeloButton(contentX(), y, half, 20, Text.literal("Got it"), b -> requestClose()));
		addDrawableChild(new VeloButton(contentX() + half + 8, y, half, 20, Text.literal("Don't show again"), b -> {
			hide.run();
			requestClose();
		}));
		if (!launcher) {
			return;
		}
		List<BridgeModRules.Hit> add = installable(missing);
		if (!add.isEmpty()) {
			y -= 24;
			addDrawableChild(new VeloButton(contentX(), y, w, 20, Text.literal("Add " + names(add) + " & relaunch"), b -> relaunch(List.of(), add)).primary());
		}
		if (!bad.isEmpty() && bad.stream().anyMatch(h -> !h.files().isEmpty())) {
			y -= 24;
			addDrawableChild(new VeloButton(contentX(), y, w, 20, Text.literal("Remove " + names(bad) + " & relaunch"), b -> relaunch(bad, List.of())).primary());
		}
	}

	/** Leaves the launcher a note (what to move away, what to install, where to rejoin) and closes the game. */
	private void relaunch(List<BridgeModRules.Hit> remove, List<BridgeModRules.Hit> add) {
		try {
			JsonObject note = new JsonObject();
			note.addProperty("at", System.currentTimeMillis());
			note.addProperty("pid", ProcessHandle.current().pid());
			note.addProperty("gameDir", net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize().toString());
			note.addProperty("mc", net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("minecraft")
					.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse(""));
			String server = net.veloclient.velo.client.util.ClientCompat.currentServerAddress();
			if (server != null) {
				note.addProperty("server", server);
			}
			JsonArray rm = new JsonArray();
			for (BridgeModRules.Hit h : remove) {
				for (Path f : h.files()) {
					rm.add(f.getFileName().toString());
				}
			}
			note.add("remove", rm);
			JsonArray in = new JsonArray();
			for (BridgeModRules.Hit h : add) {
				JsonObject o = new JsonObject();
				o.addProperty("modrinth", h.modrinth());
				o.addProperty("label", h.label());
				in.add(o);
			}
			note.add("add", in);
			Files.createDirectories(VeloPaths.config());
			Files.writeString(VeloPaths.config().resolve("pending-mod-fix.json"), note.toString(), StandardCharsets.UTF_8);
		} catch (Exception e) {
			requestClose();
			return;
		}
		//? if <26.1 {
		MinecraftClient.getInstance().scheduleStop();
		//?} else {
		/*MinecraftClient.getInstance().stop();
		*///?}
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		int y = contentY();
		for (String line : lines) {
			context.drawTextWithShadow(this.textRenderer, line, contentX(), y, VeloStyle.text());
			y += 11;
		}
	}
}
