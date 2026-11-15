package net.veloclient.legacy.feature;

import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;

/** 1.8.9 PvP modules with game hooks: Freelook, Hit Color and 1.7 Animations (mixins read the static state here). */
public final class PvpFeatures {

	private PvpFeatures() {
	}

	// ---------------------------------------------------------------- Freelook

	public static final class Freelook extends LegacyModule {
		static Freelook instance;
		final Setting.Toggle toggleMode = add(new Setting.Toggle("Toggle Instead Of Hold", false));
		final Setting.Toggle invert = add(new Setting.Toggle("Invert Up/Down", false));
		/** Freelook is on right now: the camera turns, the player keeps looking where they were. */
		public static boolean active;
		public static float yaw;
		public static float pitch;
		private int perspectiveBefore;
		private boolean keyWas;

		Freelook() {
			super("freelook", "Freelook", "PvP", "Hold Left Alt to look around your player without turning.", false, false, 0, 0);
			instance = this;
		}

		@Override
		public void tick(Minecraft mc) {
			boolean down = mc.screen == null && mc.player != null && Keyboard.isKeyDown(Keyboard.KEY_LMENU);
			boolean want = toggleMode.value ? active ^ (down && !keyWas) : down;
			keyWas = down;
			if (want != active) {
				set(mc, want);
			}
		}

		private void set(Minecraft mc, boolean on) {
			if (on && mc.player != null) {
				yaw = mc.player.yaw;
				pitch = mc.player.pitch;
				perspectiveBefore = mc.options.perspective;
				mc.options.perspective = 1;
				active = true;
			} else if (!on && active) {
				active = false;
				mc.options.perspective = perspectiveBefore;
			}
		}

		@Override
		protected void onToggle(boolean on) {
			if (!on) {
				set(Minecraft.getInstance(), false);
			}
		}

		/** The mouse moved while Freelook is on (Entity.updateLocalPlayerCamera). */
		public static void turn(float dx, float dy) {
			yaw += dx * 0.15f;
			pitch += (instance != null && instance.invert.value ? dy : -dy) * 0.15f;
			pitch = Math.max(-90f, Math.min(90f, pitch));
		}
	}

	// ---------------------------------------------------------------- Hit Color

	public static final class HitColor extends LegacyModule {
		static HitColor instance;
		final Setting.Slider red = add(new Setting.Slider("Red", 0, 255, 1, 255, ""));
		final Setting.Slider green = add(new Setting.Slider("Green", 0, 255, 1, 0, ""));
		final Setting.Slider blue = add(new Setting.Slider("Blue", 0, 255, 1, 0, ""));
		final Setting.Slider strength = add(new Setting.Slider("Strength", 0, 100, 5, 30, "%"));
		final Setting.Toggle armor = add(new Setting.Toggle("Color Armor Too (1.7)", true));

		HitColor() {
			super("hit-color", "Hit Color", "PvP", "Pick the colour and strength of the flash when something is hit.", false, false, 0, 0);
			instance = this;
		}

		/** {r, g, b, a} for the hurt flash, or null to keep vanilla. */
		public static float[] color() {
			HitColor m = instance;
			if (m == null || !m.enabled) {
				return null;
			}
			return new float[] {(float) m.red.value / 255f, (float) m.green.value / 255f, (float) m.blue.value / 255f, (float) m.strength.value / 100f};
		}

		public static boolean tintArmor() {
			HitColor m = instance;
			return m != null && m.enabled && m.armor.value || OldAnimations.on(OldAnimations.instance == null ? null : OldAnimations.instance.armor);
		}
	}

	// ---------------------------------------------------------------- 1.7 Animations

	public static final class OldAnimations extends LegacyModule {
		static OldAnimations instance;
		final Setting.Toggle blockHit = add(new Setting.Toggle("Block-Hitting", true));
		final Setting.Toggle armor = add(new Setting.Toggle("Red Armor On Hit", true));
		public static float tickDelta;

		OldAnimations() {
			super("old-animations", "1.7 Animations", "PvP", "The 1.7 feel: swing while blocking (block-hitting) and armour flashing red on hits.", false, false, 0, 0);
			instance = this;
		}

		static boolean on(Setting.Toggle t) {
			return instance != null && instance.enabled && t != null && t.value;
		}

		public static boolean blockHit() {
			return on(instance == null ? null : instance.blockHit);
		}

		@Override
		public void tick(Minecraft mc) {
			// 1.8 doesn't swing the arm while you block; 1.7 did. Only the look - nothing extra is sent to the server.
			if (!blockHit.value || mc.player == null || mc.screen != null || mc.player.getItemUseTimer() <= 0 || !mc.options.attackKey.isPressed()) {
				return;
			}
			if (!mc.player.armSwinging || mc.player.armSwingingTicks >= 3 || mc.player.armSwingingTicks < 0) {
				mc.player.armSwingingTicks = -1;
				mc.player.armSwinging = true;
			}
		}
	}
}
