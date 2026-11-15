package net.veloclient.legacy.feature;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.network.PlayerInfo;
import net.minecraft.client.options.KeyBinding;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.client.render.platform.Lighting;
import net.minecraft.entity.living.effect.StatusEffect;
import net.minecraft.entity.living.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.world.HitResult;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** The 1.8.9 modules - the PvP/HUD essentials players expect on 1.8, plus two performance helpers. */
final class LegacyFeatures {

	static final int TEXT = 0xFFFFFFFF;
	static final int MUTED = 0xFFB4B4C0;
	static final int PANEL = 0x90101014;

	private LegacyFeatures() {
	}

	// ---------------------------------------------------------------- drawing helpers

	static int line(Minecraft mc, String text, int x, int y, int color, boolean background) {
		TextRenderer tr = mc.textRenderer;
		int w = tr.getWidth(text);
		if (background) {
			GuiElement.fill(x, y, x + w + 6, y + 13, PANEL);
			tr.drawWithShadow(text, x + 3, y + 3, color);
			return w + 6;
		}
		tr.drawWithShadow(text, x, y, color);
		return w;
	}

	abstract static class TextModule extends LegacyModule {
		final Setting.Toggle background;

		TextModule(String id, String name, String description, float hx, float hy) {
			super(id, name, "HUD", description, true, false, hx, hy);
			background = add(new Setting.Toggle("Background", true));
		}

		abstract String text(Minecraft mc, boolean preview);

		@Override
		public int[] render(Minecraft mc, int x, int y, boolean preview) {
			String t = text(mc, preview);
			if (t == null) {
				return new int[] {0, 0};
			}
			int w = line(mc, t, x, y, TEXT, background.value);
			return new int[] {w, background.value ? 13 : 9};
		}
	}

	// ---------------------------------------------------------------- PvP / utility

	static final class ToggleSprint extends LegacyModule {
		private final Setting.Toggle showText = add(new Setting.Toggle("Show On HUD", true));

		ToggleSprint() {
			super("toggle-sprint", "Toggle Sprint", "PvP", "Always sprint while you walk forward - no holding Ctrl.", true, true, 0.005f, 0.94f);
		}

		@Override
		public void tick(Minecraft mc) {
			if (mc.player != null && mc.screen == null) {
				KeyBinding.set(mc.options.sprintKey.getKeyCode(), true);
			}
		}

		@Override
		protected void onToggle(boolean on) {
			if (!on) {
				KeyBinding.set(Minecraft.getInstance().options.sprintKey.getKeyCode(), false);
			}
		}

		@Override
		public int[] render(Minecraft mc, int x, int y, boolean preview) {
			if (!showText.value) {
				return new int[] {0, 0};
			}
			int w = line(mc, "[Sprinting (Toggled)]", x, y, MUTED, false);
			return new int[] {w, 9};
		}
	}

	static final class Fullbright extends LegacyModule {
		private float before = -1;

		Fullbright() {
			super("fullbright", "Fullbright", "Utility", "See in caves and at night without torches.", false, false, 0, 0);
		}

		@Override
		protected void onToggle(boolean on) {
			Minecraft mc = Minecraft.getInstance();
			if (on) {
				if (mc.options.gamma <= 1f) {
					before = mc.options.gamma;
				}
				mc.options.gamma = 100f;
			} else {
				mc.options.gamma = before >= 0 ? before : 1f;
			}
		}

		@Override
		public void tick(Minecraft mc) {
			mc.options.gamma = 100f;
		}
	}

	static final class Zoom extends LegacyModule {
		final Setting.Slider factor = add(new Setting.Slider("Zoom Amount", 2, 10, 0.5, 4, "x"));
		final Setting.Toggle smooth = add(new Setting.Toggle("Smooth Camera", true));
		static boolean zooming;
		private boolean smoothBefore;

		Zoom() {
			super("zoom", "Zoom", "Utility", "Hold C to zoom in, like OptiFine.", false, true, 0, 0);
		}

		@Override
		public void frame(Minecraft mc) {
			boolean down = mc.screen == null && org.lwjgl.input.Keyboard.isKeyDown(org.lwjgl.input.Keyboard.KEY_C);
			if (down != zooming) {
				zooming = down;
				if (smooth.value) {
					if (down) {
						smoothBefore = mc.options.smoothCamera;
						mc.options.smoothCamera = true;
					} else {
						mc.options.smoothCamera = smoothBefore;
					}
				}
			}
		}

		@Override
		protected void onToggle(boolean on) {
			if (!on && zooming) {
				zooming = false;
				Minecraft.getInstance().options.smoothCamera = smoothBefore;
			}
		}

		/** Applied to the game's field of view (GameRendererFovMixin). */
		static float fov(float fov) {
			for (LegacyModule m : Modules.all()) {
				if (m instanceof Zoom z && z.enabled && zooming) {
					return (float) (fov / z.factor.value);
				}
			}
			return fov;
		}
	}

	// ---------------------------------------------------------------- HUD

	static final class FpsDisplay extends TextModule {
		FpsDisplay() {
			super("fps", "FPS", "Your frames per second.", 0.005f, 0.01f);
			enabled = true;
		}

		@Override
		String text(Minecraft mc, boolean preview) {
			return Minecraft.getCurrentFps() + " FPS";
		}
	}

	static final class CpsDisplay extends TextModule {
		private final ArrayDeque<Long> left = new ArrayDeque<>();
		private final ArrayDeque<Long> right = new ArrayDeque<>();
		private boolean wasLeft;
		private boolean wasRight;

		CpsDisplay() {
			super("cps", "CPS", "Clicks per second (left | right).", 0.005f, 0.05f);
			enabled = true;
		}

		@Override
		public void frame(Minecraft mc) {
			long now = System.currentTimeMillis();
			boolean l = org.lwjgl.input.Mouse.isButtonDown(0) && mc.screen == null;
			boolean r = org.lwjgl.input.Mouse.isButtonDown(1) && mc.screen == null;
			if (l && !wasLeft) {
				left.add(now);
			}
			if (r && !wasRight) {
				right.add(now);
			}
			wasLeft = l;
			wasRight = r;
			while (!left.isEmpty() && now - left.peekFirst() > 1000) {
				left.pollFirst();
			}
			while (!right.isEmpty() && now - right.peekFirst() > 1000) {
				right.pollFirst();
			}
		}

		@Override
		String text(Minecraft mc, boolean preview) {
			return (preview && left.isEmpty() ? 9 : left.size()) + " | " + right.size() + " CPS";
		}
	}

	static final class Keystrokes extends LegacyModule {
		Keystrokes() {
			super("keystrokes", "Keystrokes", "HUD", "W A S D, mouse buttons and space on screen.", true, true, 0.005f, 0.12f);
		}

		private static void key(Minecraft mc, String label, int x, int y, int w, int h, boolean down) {
			GuiElement.fill(x, y, x + w, y + h, down ? 0xC0FFFFFF : PANEL);
			TextRenderer tr = mc.textRenderer;
			tr.draw(label, x + (w - tr.getWidth(label)) / 2, y + (h - 8) / 2, down ? 0xFF101014 : TEXT, !down);
		}

		@Override
		public int[] render(Minecraft mc, int x, int y, boolean preview) {
			int s = 22;
			int g = 2;
			key(mc, "W", x + s + g, y, s, s, mc.options.forwardKey.isPressed());
			key(mc, "A", x, y + s + g, s, s, mc.options.leftKey.isPressed());
			key(mc, "S", x + s + g, y + s + g, s, s, mc.options.backKey.isPressed());
			key(mc, "D", x + 2 * (s + g), y + s + g, s, s, mc.options.rightKey.isPressed());
			int half = (3 * s + 2 * g - g) / 2;
			key(mc, "LMB", x, y + 2 * (s + g), half, s - 4, mc.options.attackKey.isPressed());
			key(mc, "RMB", x + half + g, y + 2 * (s + g), 3 * s + 2 * g - half - g, s - 4, mc.options.useKey.isPressed());
			GuiElement.fill(x, y + 3 * (s + g) - 4, x + 3 * s + 2 * g, y + 3 * (s + g) + 6 - 4, mc.options.jumpKey.isPressed() ? 0xC0FFFFFF : PANEL);
			return new int[] {3 * s + 2 * g, 3 * (s + g) + 2};
		}
	}

	static final class Coordinates extends TextModule {
		private final Setting.Toggle facing = add(new Setting.Toggle("Show Direction", true));

		Coordinates() {
			super("coordinates", "Coordinates", "Where you are (X Y Z) and which way you face.", 0.005f, 0.30f);
		}

		@Override
		String text(Minecraft mc, boolean preview) {
			if (mc.player == null) {
				return preview ? "XYZ 120 64 -340  N" : null;
			}
			String t = "XYZ " + (int) Math.floor(mc.player.x) + " " + (int) Math.floor(mc.player.y) + " " + (int) Math.floor(mc.player.z);
			if (facing.value) {
				float yaw = ((mc.player.yaw % 360) + 360) % 360;
				String[] names = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
				t += "  " + names[Math.round(yaw / 45f) % 8];
			}
			return t;
		}
	}

	static final class PingDisplay extends TextModule {
		PingDisplay() {
			super("ping", "Ping", "Your connection's delay to the server.", 0.005f, 0.09f);
		}

		@Override
		String text(Minecraft mc, boolean preview) {
			if (mc.player == null || mc.getNetworkHandler() == null) {
				return preview ? "42 ms" : null;
			}
			PlayerInfo info = mc.getNetworkHandler().getOnlinePlayer(mc.player.getUuid());
			return info == null ? null : info.getPing() + " ms";
		}
	}

	static final class Clock extends TextModule {
		private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("HH:mm");

		Clock() {
			super("clock", "Clock", "The real time.", 0.94f, 0.94f);
		}

		@Override
		String text(Minecraft mc, boolean preview) {
			return LocalTime.now().format(FORMAT);
		}
	}

	static final class ReachDisplay extends TextModule {
		private double last = -1;
		private long at;
		private boolean wasDown;

		ReachDisplay() {
			super("reach-display", "Reach Display", "How far away your last hit landed (blocks).", 0.005f, 0.34f);
		}

		@Override
		public void frame(Minecraft mc) {
			boolean down = org.lwjgl.input.Mouse.isButtonDown(0) && mc.screen == null;
			if (down && !wasDown && mc.player != null && mc.crosshairTarget != null && mc.crosshairTarget.type == HitResult.Type.ENTITY
					&& mc.crosshairTarget.facePos != null) {
				last = mc.player.getEyePosition(1f).distanceTo(mc.crosshairTarget.facePos);
				at = System.currentTimeMillis();
			}
			wasDown = down;
		}

		@Override
		String text(Minecraft mc, boolean preview) {
			if (last < 0 || System.currentTimeMillis() - at > 4000) {
				return preview ? "2.87 blocks" : null;
			}
			return String.format(java.util.Locale.ROOT, "%.2f blocks", last);
		}
	}

	static final class ArmorStatus extends LegacyModule {
		private final Setting.Toggle showDurability = add(new Setting.Toggle("Show Durability", true));

		ArmorStatus() {
			super("armor-status", "Armor Status", "HUD", "Your armour and held item with durability.", true, true, 0.005f, 0.42f);
		}

		@Override
		public int[] render(Minecraft mc, int x, int y, boolean preview) {
			if (mc.player == null) {
				return new int[] {60, 68};
			}
			List<ItemStack> items = new ArrayList<>();
			for (int i = 3; i >= 0; i--) {
				ItemStack s = mc.player.inventory.armor[i];
				if (s != null) {
					items.add(s);
				}
			}
			ItemStack held = mc.player.inventory.getSelectedItem();
			if (held != null) {
				items.add(held);
			}
			int w = 18;
			GlStateManager.pushMatrix();
			Lighting.turnOnGui();
			for (int i = 0; i < items.size(); i++) {
				ItemStack s = items.get(i);
				int iy = y + i * 17;
				mc.getItemRenderer().renderGuiItem(s, x, iy);
				mc.getItemRenderer().renderGuiItemDecorations(mc.textRenderer, s, x, iy, null);
			}
			Lighting.turnOff();
			GlStateManager.popMatrix();
			for (int i = 0; i < items.size(); i++) {
				ItemStack s = items.get(i);
				if (showDurability.value && s.isDamageable() && s.getMaxDamage() > 0) {
					int left = s.getMaxDamage() - s.getDamage();
					float f = left / (float) s.getMaxDamage();
					int color = f > 0.5f ? 0xFF6FE39A : f > 0.2f ? 0xFFFFD56A : 0xFFFF6A6A;
					String t = String.valueOf(left);
					mc.textRenderer.drawWithShadow(t, x + 19, y + i * 17 + 5, color);
					w = Math.max(w, 20 + mc.textRenderer.getWidth(t));
				}
			}
			return new int[] {w, Math.max(17, items.size() * 17)};
		}
	}

	static final class PotionStatus extends LegacyModule {
		PotionStatus() {
			super("potion-status", "Potion Status", "HUD", "Active potion effects and how long they last.", true, true, 0.005f, 0.60f);
		}

		@Override
		public int[] render(Minecraft mc, int x, int y, boolean preview) {
			List<String> lines = new ArrayList<>();
			if (mc.player != null) {
				Collection<StatusEffectInstance> effects = mc.player.getStatusEffects();
				for (StatusEffectInstance e : effects) {
					StatusEffect type = StatusEffect.BY_ID[e.getId()];
					if (type == null) {
						continue;
					}
					String name = net.minecraft.client.resource.language.I18n.translate(type.getTranslationKey());
					if (e.getAmplifier() > 0) {
						name += " " + (e.getAmplifier() + 1);
					}
					lines.add(name + "  §7" + StatusEffect.getDurationString(e));
				}
			}
			if (lines.isEmpty() && preview) {
				lines.add("Speed 2  §71:30");
				lines.add("Strength  §70:45");
			}
			int w = 0;
			for (int i = 0; i < lines.size(); i++) {
				w = Math.max(w, line(mc, lines.get(i), x, y + i * 11, TEXT, false));
			}
			return new int[] {Math.max(w, 10), Math.max(9, lines.size() * 11)};
		}
	}

	// ---------------------------------------------------------------- performance

	static final class DynamicFps extends LegacyModule {
		private final Setting.Slider unfocused = add(new Setting.Slider("FPS When Tabbed Out", 5, 60, 5, 15, " fps"));
		private int before = -1;

		DynamicFps() {
			super("dynamic-fps", "Dynamic FPS", "Performance", "Caps the frame rate while the game is in the background - cooler PC, more FPS for other apps.", false, true, 0, 0);
		}

		@Override
		public void tick(Minecraft mc) {
			boolean background = !mc.focused;
			if (background && before < 0) {
				before = mc.options.fpsLimit;
				mc.options.fpsLimit = (int) unfocused.value;
			} else if (!background && before >= 0) {
				mc.options.fpsLimit = before;
				before = -1;
			}
		}

		@Override
		protected void onToggle(boolean on) {
			if (!on && before >= 0) {
				Minecraft.getInstance().options.fpsLimit = before;
				before = -1;
			}
		}
	}

	static final class FpsBoost extends LegacyModule {
		FpsBoost() {
			super("fps-boost", "FPS Boost", "Performance",
					"Turns on the video settings that cost the most FPS in PvP: fast graphics, no clouds, no entity shadows, VBOs on, smooth lighting low.",
					false, false, 0, 0);
		}

		@Override
		protected void onToggle(boolean on) {
			Minecraft mc = Minecraft.getInstance();
			if (on) {
				mc.options.fancyGraphics = false;
				mc.options.cloudRenderMode = 0;
				mc.options.entityShadows = false;
				mc.options.useVbo = true;
				mc.options.ambientOcclusion = 1;
				mc.options.save();
				if (mc.worldRenderer != null) {
					mc.worldRenderer.reload();
				}
			}
		}
	}
}
