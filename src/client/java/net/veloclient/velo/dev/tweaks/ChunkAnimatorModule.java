package net.veloclient.velo.dev.tweaks;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;
import java.util.Locale;

/**
 * Chunk Animator (dev build, 26.2+ with Sodium): newly loaded chunks glide into place instead of popping in. Sodium
 * already records when each chunk section was first shown (for its fade-in) and skips sections close to you - the
 * terrain vertex shader is patched while it loads (ChunkAnimatorShaderMixin) to also move such a section from below or
 * above. The shader is built with the settings, so changing them reloads resources.
 */
public final class ChunkAnimatorModule extends AbstractModule implements Configurable {

	private static final List<String> STYLES = List.of("From Below", "From Above");
	private static final List<String> SPEEDS = List.of("Fast", "Normal", "Slow");
	private static final String ANCHOR = "vec3 position = _vert_position + translation;";

	private static ChunkAnimatorModule instance;

	private String style = "From Below";
	private String speed = "Normal";
	private double distance = 24;

	/** The settings the loaded shader was built with ("" = unpatched). */
	private static String built = "";
	private String wanted = "";
	private int reloadIn = -1;
	/** Sodium has built its terrain shader at least once (it only does in a world). */
	private static boolean shaderLoaded;

	public ChunkAnimatorModule() {
		super("chunk-animator", "Chunk Animator", "New chunks glide into place instead of popping in. Needs Sodium (comes with the launcher).",
				ModuleCategory.RENDERING, SafetyTag.COSMETIC_ONLY, false);
		instance = this;
		ClientTickEvents.END_CLIENT_TICK.register(client -> tick(client));
	}

	private String settings() {
		return isEnabled() ? style + "|" + speed + "|" + (int) distance : "";
	}

	/**
	 * Reloads resources (a moment after the last change) when the shader no longer matches the settings - only while a
	 * world's terrain shader is actually loaded. Sodium builds that shader when a world is joined, already patched with
	 * the current settings: a reload scheduled before that (game start, menu) used to run anyway on every join, a whole
	 * extra resource reload each time (1.5.x "launching / joining takes forever").
	 */
	private void tick(MinecraftClient client) {
		String now = settings();
		if (client.world == null || !shaderLoaded) {
			wanted = now;
			reloadIn = -1;
			return;
		}
		if (!now.equals(wanted)) {
			wanted = now;
			reloadIn = built.equals(now) ? -1 : 30;
		}
		if (built.equals(now)) {
			reloadIn = -1; // the shader was rebuilt with these settings meanwhile
		}
		if (reloadIn > 0 && --reloadIn == 0) {
			reloadIn = -1;
			client.reloadResources();
		}
	}

	/** Patches Sodium's terrain vertex shader source; anything else (or the module off) comes back unchanged. */
	public static String patch(String path, String source) {
		ChunkAnimatorModule m = instance;
		if (m == null || !path.endsWith("blocks/block_layer_opaque.vsh")) {
			return source;
		}
		String settings = m.settings();
		built = settings;
		shaderLoaded = true;
		m.wanted = settings;
		// Sodium 0.9 (26.x) keeps each section's first-shown time in u_SectionTimeInfo, Sodium 0.8 (1.21.11) in u_chunkFades
		boolean sodium08 = source.contains("u_chunkFades");
		if (settings.isEmpty() || !source.contains(ANCHOR) || !(sodium08 || source.contains("u_SectionTimeInfo")) || !source.contains("u_CurrentTime")) {
			return source;
		}
		float duration = switch (m.speed) {
			case "Fast" -> 450f;
			case "Slow" -> 1600f;
			default -> 900f;
		};
		float offset = (float) (m.style.equals("From Above") ? m.distance : -m.distance);
		String code = String.format(Locale.ROOT, """

				    // Velo Chunk Animator: sections Sodium fades in glide into place
				    {
				        %s
				        if (veloBuilt >= 0) {
				            float veloT = clamp(float(u_CurrentTime - veloBuilt) / %.1f, 0.0, 1.0);
				            position.y += pow(1.0 - veloT, 3.0) * %.2f;
				        }
				    }
				""", sodium08 ? "int veloBuilt = u_chunkFades[int(_draw_id) >> 2][int(_draw_id) & 3];"
						: "int veloBuilt = texelFetch(u_SectionTimeInfo, int((u_RegionID * 256u) + uint(_draw_id))).r;", duration, offset);
		return source.replace(ANCHOR, ANCHOR + code);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Style", STYLES, () -> style, v -> style = v),
				new ConfigField.ChoiceField("Speed", SPEEDS, () -> speed, v -> speed = v),
				new ConfigField.SliderField("Distance", 8, 64, () -> distance, v -> distance = Math.round(v / 4) * 4, v -> (int) Math.round(v) + " blocks"));
	}
}
