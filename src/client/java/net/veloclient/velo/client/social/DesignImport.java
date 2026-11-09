package net.veloclient.velo.client.social;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.veloclient.velo.client.crosshair.CrosshairManager;
import net.veloclient.velo.client.hud.StatusBarIcons;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.Module;
import net.veloclient.velo.module.ModuleRegistry;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

/**
 * {@code /velo import <code>}: a crosshair or heart / food / armor icon designed on velo-client.com/library/hud (or a
 * community preset from there) - downloaded, saved like the in-game editors save, switched on.
 */
public final class DesignImport {

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();

	private DesignImport() {
	}

	public static void run(String code) {
		String c = code.trim();
		String path;
		if (c.matches("[0-9a-f]{12}")) {
			path = "presets/" + c;
		} else if (c.matches("[0-9a-z]{6,10}")) {
			path = "designs/" + c;
		} else {
			VeloChat.error("That isn't an import code - copy it from velo-client.com/library/hud");
			return;
		}
		VeloChat.info("Getting design " + c + "...");
		Thread.ofVirtual().name("velo-design-import").start(() -> {
			try {
				HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("https://velo-client.com/v2/" + path)).header("User-Agent", "VeloClient")
						.timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
				if (r.statusCode() != 200) {
					MinecraftClient.getInstance().execute(() -> VeloChat.error(r.statusCode() == 404 ? "No design with that code." : "The Library didn't answer - try again soon."));
					return;
				}
				JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
				JsonObject design = o.has("data") && o.get("data").isJsonObject() ? o.getAsJsonObject("data") : o;
				String name = o.has("name") ? o.get("name").getAsString() : "Imported";
				MinecraftClient.getInstance().execute(() -> apply(design, name));
			} catch (Exception e) {
				MinecraftClient.getInstance().execute(() -> VeloChat.error("Couldn't import: " + e.getMessage()));
			}
		});
	}

	/** {@code /velo schem <id>}: a community schematic from velo-client.com/library/schematics, saved and loaded straight away. */
	public static void runSchematic(String id) {
		String c = id.trim().toLowerCase(java.util.Locale.ROOT);
		// 16 hex = a community schematic (velo-server), 6-10 letters = one made on the website (map art, pixel art)
		String url;
		if (c.matches("[0-9a-f]{16}")) {
			url = "https://velo-client.com/v1/schematics/" + c + "/file";
		} else if (c.matches("[a-z0-9]{6,10}")) {
			url = "https://velo-client.com/v2/schem/" + c;
		} else {
			VeloChat.error("That isn't a schematic code - use the button on velo-client.com/library/schematics");
			return;
		}
		VeloChat.info("Downloading schematic...");
		Thread.ofVirtual().name("velo-schem-import").start(() -> {
			try {
				HttpResponse<byte[]> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "VeloClient")
						.timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
				if (r.statusCode() != 200) {
					MinecraftClient.getInstance().execute(() -> VeloChat.error(r.statusCode() == 404 ? "No schematic with that code." : "Download failed (HTTP " + r.statusCode() + ")."));
					return;
				}
				String served = r.headers().firstValue("X-Velo-File-Name").orElse("schematic.veloschem");
				boolean litematic = served.toLowerCase(java.util.Locale.ROOT).endsWith(".litematic");
				String base = served.replaceAll("\\.(litematic|veloschem)$", "").replaceAll("[^A-Za-z0-9 _.-]", "_").replace("..", "_").strip();
				Path folder = litematic ? net.veloclient.velo.client.redstone.SchematicIO.litematicFolder() : net.veloclient.velo.client.redstone.SchematicIO.veloFolder();
				Files.createDirectories(folder);
				String ext = litematic ? ".litematic" : ".veloschem";
				Path target = folder.resolve((base.isEmpty() ? "schematic" : base) + ext);
				for (int n = 2; Files.exists(target); n++) {
					target = folder.resolve((base.isEmpty() ? "schematic" : base) + " (" + n + ")" + ext);
				}
				Files.write(target, r.body());
				Path saved = target;
				MinecraftClient.getInstance().execute(() -> {
					VeloChat.info("Saved " + saved.getFileName() + " - loading it in Redstone Mode.");
					net.veloclient.velo.client.redstone.SchematicIO.loadToClipboard(saved);
				});
			} catch (Exception e) {
				MinecraftClient.getInstance().execute(() -> VeloChat.error("Couldn't download: " + e.getMessage()));
			}
		});
	}

	private static byte[] png(JsonObject images, String key) {
		if (images == null || !images.has(key) || !images.get(key).isJsonPrimitive()) {
			return null;
		}
		String v = images.get(key).getAsString().replaceFirst("^data:image/png;base64,", "");
		byte[] b = Base64.getDecoder().decode(v);
		return b.length > 256 * 1024 ? null : b;
	}

	private static void apply(JsonObject d, String name) {
		try {
			String type = d.has("type") ? d.get("type").getAsString() : "";
			JsonObject images = d.has("images") && d.get("images").isJsonObject() ? d.getAsJsonObject("images") : null;
			switch (type) {
				case "crosshair" -> {
					byte[] idle = png(images, "idle");
					if (idle == null) {
						throw new IllegalArgumentException("no picture in it");
					}
					Path tmp = Files.createTempFile("velo-crosshair", ".png");
					Files.write(tmp, idle);
					var def = CrosshairManager.importFromPng(name, tmp);
					Files.deleteIfExists(tmp);
					CrosshairManager.equip(def.id());
					enable("custom-crosshair", null);
					VeloChat.info("Crosshair \"" + name + "\" imported and equipped.");
				}
				case "heart", "food", "armor" -> {
					Map<StatusBarIcons.State, NativeImage> map = new EnumMap<>(StatusBarIcons.State.class);
					for (StatusBarIcons.State s : StatusBarIcons.State.values()) {
						byte[] b = png(images, s.file);
						if (b != null) {
							map.put(s, NativeImage.read(new ByteArrayInputStream(b)));
						}
					}
					if (map.isEmpty()) {
						throw new IllegalArgumentException("no picture in it");
					}
					StatusBarIcons.save(type, map);
					enable(type.equals("heart") ? "hearts" : type.equals("food") ? "hunger" : "armor-bar", "Custom Drawing");
					VeloChat.info((type.equals("heart") ? "Hearts" : type.equals("food") ? "Food icons" : "Armor icons") + " \"" + name + "\" imported and switched on.");
				}
				default -> throw new IllegalArgumentException("unknown kind of design");
			}
			net.veloclient.velo.client.profile.VeloProfileStore.saveActive();
		} catch (Exception e) {
			VeloChat.error("Couldn't import: " + e.getMessage());
		}
	}

	/** Turns the module on and (for status bars) picks its "Custom Drawing" style through its own settings. */
	private static void enable(String moduleId, String style) {
		Module m = ModuleRegistry.get(moduleId).orElse(null);
		if (m == null) {
			return;
		}
		if (style != null && m instanceof Configurable c) {
			for (ConfigField f : c.configFields()) {
				if (f instanceof ConfigField.ChoiceField choice && choice.options().contains(style)) {
					choice.set().accept(style);
				}
			}
		}
		if (!m.isEnabled()) {
			m.setEnabled(true);
		}
	}
}
