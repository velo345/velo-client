package net.veloclient.velo.client.modules.qol;

import net.minecraft.client.gui.DrawContext;
import net.veloclient.velo.client.crosshair.CrosshairDefinition;
import net.veloclient.velo.client.crosshair.CrosshairManager;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Replaces vanilla's crosshair with the equipped one from the library
 * (managed via the "Crosshairs" sidebar button - see {@link
 * net.veloclient.velo.client.gui.CrosshairSelectScreen} and {@link
 * net.veloclient.velo.client.gui.CrosshairEditorScreen}). Actual suppression
 * of vanilla's own crosshair render + drawing this one happens in {@link
 * net.veloclient.velo.client.mixin.CustomCrosshairMixin}; this class only
 * holds the on-screen size setting, since that's the one thing that
 * naturally belongs in this module's own settings screen rather than the
 * crosshair library screen.
 */
public final class CustomCrosshairModule extends AbstractModule implements Configurable {

	private int size = 16;

	public CustomCrosshairModule() {
		super("custom-crosshair", "Custom Crosshair", "Replaces the vanilla crosshair with one from your library - draw your own or import a PNG.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, false);
	}

	public int size() {
		return size;
	}

	/** Renders the equipped crosshair centered on screen, swapping to its hit state if {@code hit}. Called from the mixin, not per-frame HUD iteration, since this isn't a draggable {@code HudModule}. */
	public static void renderIfEquipped(DrawContext context, int screenWidth, int screenHeight, boolean hit) {
		var module = net.veloclient.velo.module.ModuleRegistry.get("custom-crosshair").orElse(null);
		if (!(module instanceof CustomCrosshairModule crosshairModule) || !crosshairModule.isEnabled()) {
			return;
		}
		CrosshairDefinition definition = CrosshairManager.equipped().orElse(null);
		if (definition == null) {
			return;
		}
		boolean useHit = hit && definition.hitMode() != CrosshairDefinition.HitMode.NONE;
		net.minecraft.util.Identifier identifier = switch (useHit ? definition.hitMode() : CrosshairDefinition.HitMode.NONE) {
			case SEPARATE_IMAGE -> CrosshairManager.textureIdentifier(definition.id(), true);
			case COLOR_SWAP -> CrosshairManager.colorSwapTextureIdentifier(definition);
			case NONE -> CrosshairManager.textureIdentifier(definition.id(), false);
		};
		int size = crosshairModule.size;
		int x = (screenWidth - size) / 2;
		int y = (screenHeight - size) / 2;
		// The 12-arg overload is required here, not the 10-arg one: that
		// shorter form silently sets the sampled *texture region* (not just
		// the on-screen size) to (size, size) too, so scaling a small
		// texture up past its own resolution (e.g. a 16x16 crosshair drawn
		// at 32px) sampled UV coordinates past 1.0 and wrapped/repeated the
		// image in a grid instead of just scaling it.
		int canvas = definition.canvasSize();
		context.drawTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED, identifier, x, y, 0f, 0f,
				size, size, canvas, canvas, canvas, canvas);
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(new ConfigField.SliderField("Size", 4, 64, () -> size, v -> size = (int) v, v -> (int) v + "px"));
	}
}
