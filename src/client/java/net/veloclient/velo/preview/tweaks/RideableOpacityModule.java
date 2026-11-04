package net.veloclient.velo.preview.tweaks;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Rideable Opacity (dev build): the horse, pig, strider, camel or happy ghast you ride turns see-through, so it stops
 * blocking the view below you. The renderer draws the mount with vanilla's translucent entity layer (the one spectators
 * see invisible mobs with) at the alpha set here - see RideableAlphaRenderMixin.
 */
public final class RideableOpacityModule extends AbstractModule implements Configurable {

	private static RideableOpacityModule instance;

	private double opacity = 0.35;
	private boolean firstPersonOnly = true;

	public RideableOpacityModule() {
		super("rideable-opacity", "Rideable Opacity", "Makes the horse, pig or camel you ride see-through, so it doesn't block your view.",
				ModuleCategory.QOL, SafetyTag.COSMETIC_ONLY, false);
		instance = this;
	}

	/** How opaque {@code entity} should be drawn: 1 = normal. */
	public static float alphaFor(Entity entity) {
		RideableOpacityModule m = instance;
		if (m == null || !m.isEnabled()) {
			return 1f;
		}
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null || entity != client.player.getVehicle()) {
			return 1f;
		}
		if (m.firstPersonOnly && !client.options.getPerspective().isFirstPerson()) {
			return 1f;
		}
		return (float) m.opacity;
	}

	/** Current settings for previews (the first-start guide): opacity 0..1. */
	public static float previewOpacity() {
		RideableOpacityModule m = instance;
		return m == null ? 0.35f : (float) m.opacity;
	}

	public static void setPreviewOpacity(double value) {
		if (instance != null) {
			instance.opacity = Math.max(0.05, Math.min(1.0, value));
		}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.SliderField("Opacity", 0.05, 1.0, () -> opacity, v -> opacity = Math.round(v * 100) / 100.0,
						v -> Math.round(v * 100) + "%"),
				new ConfigField.ToggleField("Only In First Person", () -> firstPersonOnly, v -> firstPersonOnly = v));
	}
}
