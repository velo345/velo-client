package net.veloclient.velo.client.modules.servertools;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundCategory;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.hud.HudModule;
import net.veloclient.velo.client.hud.HudPosition;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

import java.util.List;

/**
 * A top-down radar circle showing which direction nearby sounds are coming
 * from, compass-style - blips are stored with an absolute world bearing and
 * their on-screen angle is recomputed against the player's *current* facing
 * every frame, so they visibly swing around the circle as the player turns
 * rather than staying fixed at whatever angle was current when the sound
 * started. Each blip shows a category icon and the short sound name, and
 * fades out after a few seconds. Backed by {@link SoundRadarTracker}, since
 * vanilla has no API to enumerate currently-playing sounds with position.
 */
public final class SoundDebugOverlayModule extends AbstractModule implements HudModule, Configurable {

	private static final int LABEL_OFFSET = 10;

	private final HudPosition position = new HudPosition(0.75f, 0.07f);
	private int radius = 36;
	private boolean showIcon = true;
	private boolean showText = true;
	private boolean showHostile = true;
	private boolean showNeutral = true;
	private boolean showPlayers = true;
	private boolean showBlocks = true;
	private boolean showWeatherAmbient = true;
	private boolean showNoteblocks = true;
	private boolean showRedstone = true;

	public SoundDebugOverlayModule() {
		super("sound-debug-overlay", "Sound Radar", "Shows the direction nearby sounds are coming from on a rotating radar circle.",
				ModuleCategory.SERVER_TOOLS, SafetyTag.ALWAYS_SAFE, false);
		// Registering deferred to onEnable(), not done here in the
		// constructor: every module is constructed during mod init, well
		// before MinecraftClient finishes setting up its sound engine -
		// calling getSoundManager() that early returns null and crashed the
		// whole client on startup.
	}

	@Override
	public void onEnable() {
		SoundRadarTracker.ensureRegistered();
	}

	@Override
	public HudPosition position() {
		return position;
	}

	@Override
	public void render(DrawContext context, int x, int y, float tickDelta) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (client.player == null) {
			return;
		}
		float playerYaw = client.player.getYaw();

		List<SoundRadarTracker.Blip> visible = SoundRadarTracker.activeBlips().stream()
				.filter(this::allowed)
				.toList();
		if (visible.isEmpty()) {
			// Stays fully invisible with no matching sounds nearby - a
			// persistent circle sitting on screen doing nothing would just
			// be visual noise the rest of the time.
			return;
		}

		int centerX = x + radius;
		int centerY = y + radius;

		VeloDraw.fillCircle(context, centerX, centerY, radius, 0x55000000);
		// Forward-direction tick at the top of the circle - this is what
		// "up = ahead" is anchored to; it never moves since the whole
		// circle represents the player's own point of view.
		context.fill(centerX - 1, y, centerX + 1, y + 4, 0x99FFFFFF);

		long now = System.currentTimeMillis();
		for (SoundRadarTracker.Blip blip : visible) {
			float age = (now - blip.startedAtMs()) / 3000f;
			int alpha = Math.max(0, 255 - Math.round(age * 255));
			if (alpha <= 0) {
				continue;
			}
			double relativeDegrees = wrapDegrees(blip.worldBearingDegrees() - playerYaw);
			double rad = Math.toRadians(relativeDegrees);
			int px = centerX + (int) Math.round(Math.sin(rad) * (radius - 6));
			int py = centerY - (int) Math.round(Math.cos(rad) * (radius - 6));

			int dotColor = (categoryColor(blip) & 0x00FFFFFF) | (alpha << 24);
			VeloDraw.fillRounded(context, px - 2, py - 2, 4, 4, 2, dotColor);

			if (showIcon) {
				ItemStack icon = new ItemStack(categoryIcon(blip));
				context.getMatrices().pushMatrix();
				context.getMatrices().translate(px + LABEL_OFFSET, py - 4);
				context.getMatrices().scale(0.5f, 0.5f);
				context.drawItemWithoutEntity(icon, 0, 0);
				context.getMatrices().popMatrix();
			}
			if (showText) {
				int textX = px + LABEL_OFFSET + (showIcon ? 10 : 0);
				context.drawTextWithShadow(client.textRenderer, blip.shortName(), textX, py - 4,
						0xFFFFFF | (alpha << 24));
			}
		}
	}

	// Vanilla files both noteblocks and every redstone-component sound
	// (pistons, dispensers, comparators, levers, buttons...) under the same
	// generic SoundCategory.BLOCKS - there's no dedicated category to filter
	// on, so these two are detected from the sound event's own id instead.
	private boolean allowed(SoundRadarTracker.Blip blip) {
		if (isNoteblock(blip.soundId())) {
			return showNoteblocks;
		}
		if (isRedstone(blip.soundId())) {
			return showRedstone;
		}
		SoundCategory category = blip.category();
		if (category == SoundCategory.HOSTILE) {
			return showHostile;
		}
		if (category == SoundCategory.NEUTRAL) {
			return showNeutral;
		}
		if (category == SoundCategory.PLAYERS || category == SoundCategory.VOICE) {
			return showPlayers;
		}
		if (category == SoundCategory.BLOCKS) {
			return showBlocks;
		}
		if (category == SoundCategory.WEATHER || category == SoundCategory.AMBIENT) {
			return showWeatherAmbient;
		}
		return true;
	}

	private static boolean isNoteblock(String soundId) {
		return soundId.contains("note_block");
	}

	private static boolean isRedstone(String soundId) {
		return soundId.contains("redstone") || soundId.contains("piston") || soundId.contains("dispenser")
				|| soundId.contains("comparator") || soundId.contains("lever") || soundId.contains("button")
				|| soundId.contains("pressure_plate");
	}

	private static double wrapDegrees(double degrees) {
		double d = degrees % 360;
		if (d < -180) {
			d += 360;
		}
		if (d > 180) {
			d -= 360;
		}
		return d;
	}

	private static int categoryColor(SoundRadarTracker.Blip blip) {
		if (isNoteblock(blip.soundId())) {
			return 0xFFFFAA00;
		}
		if (isRedstone(blip.soundId())) {
			return 0xFFFF4433;
		}
		SoundCategory category = blip.category();
		if (category == SoundCategory.HOSTILE) {
			return 0xFFFF5555;
		}
		if (category == SoundCategory.PLAYERS || category == SoundCategory.VOICE) {
			return 0xFF55FFFF;
		}
		if (category == SoundCategory.NEUTRAL) {
			return 0xFFFFFF55;
		}
		if (category == SoundCategory.WEATHER || category == SoundCategory.AMBIENT) {
			return 0xFF88AAFF;
		}
		return 0xFFCCCCCC;
	}

	private static Item categoryIcon(SoundRadarTracker.Blip blip) {
		if (isNoteblock(blip.soundId())) {
			return Items.NOTE_BLOCK;
		}
		if (isRedstone(blip.soundId())) {
			return Items.REDSTONE;
		}
		SoundCategory category = blip.category();
		if (category == SoundCategory.HOSTILE) {
			return Items.ZOMBIE_SPAWN_EGG;
		}
		if (category == SoundCategory.NEUTRAL) {
			return Items.COW_SPAWN_EGG;
		}
		if (category == SoundCategory.PLAYERS || category == SoundCategory.VOICE) {
			return Items.PLAYER_HEAD;
		}
		if (category == SoundCategory.BLOCKS) {
			return Items.STONE;
		}
		if (category == SoundCategory.WEATHER) {
			return Items.WATER_BUCKET;
		}
		if (category == SoundCategory.AMBIENT) {
			return Items.GLOW_INK_SAC;
		}
		if (category == SoundCategory.RECORDS || category == SoundCategory.MUSIC) {
			return Items.MUSIC_DISC_CAT;
		}
		return Items.NOTE_BLOCK;
	}

	@Override
	public int width() {
		return radius * 2 + (showText ? 70 : (showIcon ? 20 : 0));
	}

	@Override
	public int height() {
		return radius * 2;
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.SliderField("Radius", 20, 64, () -> radius, v -> radius = (int) v, v -> String.valueOf((int) v)),
				new ConfigField.ToggleField("Show Icon", () -> showIcon, v -> showIcon = v),
				new ConfigField.ToggleField("Show Text", () -> showText, v -> showText = v),
				new ConfigField.ToggleField("Show Hostile Mobs", () -> showHostile, v -> showHostile = v),
				new ConfigField.ToggleField("Show Neutral Mobs", () -> showNeutral, v -> showNeutral = v),
				new ConfigField.ToggleField("Show Players", () -> showPlayers, v -> showPlayers = v),
				new ConfigField.ToggleField("Show Blocks", () -> showBlocks, v -> showBlocks = v),
				new ConfigField.ToggleField("Show Weather/Ambient", () -> showWeatherAmbient, v -> showWeatherAmbient = v),
				new ConfigField.ToggleField("Show Noteblocks", () -> showNoteblocks, v -> showNoteblocks = v),
				new ConfigField.ToggleField("Show Redstone", () -> showRedstone, v -> showRedstone = v));
	}
}
