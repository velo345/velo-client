package net.veloclient.legacy.feature;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/** A Velo module on 1.8.9: switched on/off in the R-Shift menu, optionally a HUD element you can drag. */
public abstract class LegacyModule {

	public final String id;
	public final String name;
	public final String category;
	public final String description;
	public final boolean hud;
	public final List<Setting> settings = new ArrayList<>();
	boolean enabled;
	/** HUD position as fractions of the screen (top-left corner). */
	public float hx;
	public float hy;

	protected LegacyModule(String id, String name, String category, String description, boolean hud, boolean enabledByDefault, float hx, float hy) {
		this.id = id;
		this.name = name;
		this.category = category;
		this.description = description;
		this.hud = hud;
		this.enabled = enabledByDefault;
		this.hx = hx;
		this.hy = hy;
	}

	public boolean enabled() {
		return enabled;
	}

	public void setEnabled(boolean on) {
		if (on != enabled) {
			enabled = on;
			onToggle(on);
			Modules.saveSoon();
		}
	}

	protected <T extends Setting> T add(T setting) {
		settings.add(setting);
		return setting;
	}

	protected void onToggle(boolean on) {
	}

	/** Every client tick while enabled. */
	public void tick(Minecraft mc) {
	}

	/** Every frame while enabled (also for input that must not miss a click). */
	public void frame(Minecraft mc) {
	}

	/** HUD modules: draws at (x, y) and returns {width, height}; {@code preview} = HUD editor (show sample data). */
	public int[] render(Minecraft mc, int x, int y, boolean preview) {
		return new int[] {0, 0};
	}
}
