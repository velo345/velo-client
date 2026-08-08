package net.veloclient.velo.client.modules.qol;

import net.veloclient.velo.client.util.SmallCapsConverter;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * Renders chat, action bar and Velo's own HUD text in small capitals (see
 * {@link SmallCapsConverter}) instead of normal case, each independently
 * toggleable. Read by {@code SmallCapsChatMixin}/{@code
 * SmallCapsActionBarMixin}/{@code HudSmallCapsMixin} rather than doing any
 * rendering itself - this is purely the settings/state side.
 */
public final class SmallCapsModule extends AbstractModule implements Configurable {

	private static volatile SmallCapsModule instance;
	private static volatile boolean hudRenderActive;

	private boolean chatEnabled = true;
	private boolean actionBarEnabled = true;
	private boolean hudEnabled = true;

	public SmallCapsModule() {
		super("small-caps", "Small Caps",
				"Renders chat, action bar and Velo's own HUD text in small capitals instead of normal case, "
						+ "each toggleable separately.",
				ModuleCategory.QOL, SafetyTag.COSMETIC_ONLY, false);
		instance = this;
	}

	public static boolean shouldTransformChat() {
		SmallCapsModule module = instance;
		return module != null && module.isEnabled() && module.chatEnabled;
	}

	public static boolean shouldTransformActionBar() {
		SmallCapsModule module = instance;
		return module != null && module.isEnabled() && module.actionBarEnabled;
	}

	/**
	 * Set by {@code HudManager} around each Velo HUD module's own {@code
	 * render(...)} call - {@code true} for that exact duration only, so
	 * {@code HudSmallCapsMixin} can tell "text Velo's own HUD is drawing
	 * right now" apart from the constant stream of unrelated {@code
	 * drawText}/{@code text} calls the rest of the game makes (menus,
	 * tooltips, chat, every other mod's HUD/GUI, ...), which must never be
	 * touched by this.
	 */
	public static void setHudRenderActive(boolean active) {
		hudRenderActive = active;
	}

	public static boolean isHudRenderActive() {
		return hudRenderActive && isHudSmallCapsEnabled();
	}

	/**
	 * Same setting as {@link #isHudRenderActive()}, without the render-active
	 * gate - for Velo HUD-ish elements that aren't drawn through {@code
	 * HudManager}'s {@code DrawContext}-based loop at all and so can't rely
	 * on that flag, namely world-space label text drawn via {@code
	 * GizmoDrawing} ({@code TntTimerModule}'s countdown, {@code
	 * WaypointsModule}'s 3D labels). Those call this directly at the one
	 * place they build their own label string, rather than through a shared
	 * mixin - there's no risk of touching unrelated vanilla text the way the
	 * DrawContext mixin has to guard against, since it's this module's own
	 * code calling its own label-building code.
	 */
	public static boolean isHudSmallCapsEnabled() {
		SmallCapsModule module = instance;
		return module != null && module.isEnabled() && module.hudEnabled;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ToggleField("Small Caps in Chat", () -> chatEnabled, v -> chatEnabled = v),
				new ConfigField.ToggleField("Small Caps in Action Bar", () -> actionBarEnabled, v -> actionBarEnabled = v),
				new ConfigField.ToggleField("Small Caps in Velo HUD", () -> hudEnabled, v -> hudEnabled = v));
	}
}
