package net.veloclient.velo.devbuild;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.veloclient.velo.client.pvp.PvpCompat;
import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ConfigField;
import net.veloclient.velo.module.Configurable;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

//? if <26.1 {
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
//?} else {
/*import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
*///?}

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shield Indicator: shields are tinted by whether they block right now - green while they work, red while an axe has
 * disabled them. "Smooth" fades red back to green as the cooldown runs out and flashes (white, glint, full bright) the
 * moment the shield works again; "Solid" switches hard between the two colours.
 * <p>Your own shield reads the exact item cooldown. Other players' cooldowns aren't sent to you, so their shields go red
 * when the shield-break sound plays at them and count down the vanilla 5 s.
 * <p>The tint reaches the shield renderer through the item's render data (see {@code ShieldTintMixin}): the item model
 * knows who holds the shield while the renderer only sees the item.
 */
public final class ShieldIndicatorModule extends AbstractModule implements Configurable {

	private static final String SMOOTH = "Smooth";
	private static final String SOLID = "Solid";
	private static final long FLASH_MS = 650;
	/** Vanilla disables a shield for 5 s when an axe hits it (the client doesn't learn other players' exact value). */
	private static final long OTHER_DISABLE_MS = 5000;

	private static volatile ShieldIndicatorModule instance;

	private String mode = SMOOTH;
	private boolean readyFlash = true;
	private boolean readySound = true;
	private boolean otherPlayers = true;
	private int readyColor = 0x4CFF6A;
	private int brokenColor = 0xFF3B3B;

	/** Entity id -> when (ms) another player's shield broke. */
	private final Map<Integer, Long> brokenAt = new HashMap<>();
	/** Entity id -> when (ms) its shield worked again (drives the flash). */
	private final Map<Integer, Long> readyAt = new HashMap<>();
	private boolean ownWasDown;

	public ShieldIndicatorModule() {
		super("shield-indicator", "Shield Indicator",
				"Green shield while it blocks, red while an axe disabled it - fades back and flashes when it works again.",
				ModuleCategory.PVP, SafetyTag.COSMETIC_ONLY, false);
		instance = this;
		ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
	}

	/** The module when it's on, else null. */
	public static ShieldIndicatorModule active() {
		ShieldIndicatorModule m = instance;
		return m != null && m.isEnabled() ? m : null;
	}

	private void tick() {
		if (!isEnabled()) {
			return;
		}
		LivingEntity self = PvpCompat.localPlayer();
		if (self == null) {
			brokenAt.clear();
			readyAt.clear();
			return;
		}
		ItemStack shield = shieldOf(self);
		boolean down = shield != null && ownCooldown(self, shield, 0f) > 0f;
		if (ownWasDown && !down) {
			readyAt.put(self.getId(), System.currentTimeMillis());
			if (readySound) {
				PvpCompat.playUiSound("minecraft:block.amethyst_block.chime", 1.7f, 1.0f);
			}
		}
		ownWasDown = down;
		long now = System.currentTimeMillis();
		brokenAt.values().removeIf(t -> now - t > OTHER_DISABLE_MS + FLASH_MS);
		readyAt.values().removeIf(t -> now - t > FLASH_MS);
	}

	/** Shield-break sound at (x, y, z): the player holding a shield right there just had it disabled. */
	public static void onShieldBreakSound(double x, double y, double z) {
		ShieldIndicatorModule m = active();
		if (m == null || !m.otherPlayers) {
			return;
		}
		LivingEntity self = PvpCompat.localPlayer();
		LivingEntity best = null;
		double bestD = 2.5 * 2.5;
		for (LivingEntity e : PvpCompat.otherPlayers()) {
			if (shieldOf(e) == null) {
				continue;
			}
			//? if <26.1 {
			double d = e.squaredDistanceTo(x, y, z);
			//?} else {
			/*double d = e.distanceToSqr(x, y, z);
			*///?}
			if (d < bestD) {
				bestD = d;
				best = e;
			}
		}
		if (best != null && best != self) {
			m.brokenAt.put(best.getId(), System.currentTimeMillis());
		}
	}

	/**
	 * The tint for a shield held by {@code holder}, packed as 0xFFRRGGBB with the flash strength (0-255) in the top byte
	 * instead of alpha; 0 = leave the shield alone.
	 */
	public int tintFor(Entity holder, ItemStack stack) {
		if (!(holder instanceof LivingEntity living)) {
			return 0;
		}
		long now = System.currentTimeMillis();
		float down;   // 1 = just disabled .. 0 = works
		//? if <26.1 {
		boolean isPlayer = living instanceof PlayerEntity;
		//?} else {
		/*boolean isPlayer = living instanceof Player;
		*///?}
		if (living == PvpCompat.localPlayer()) {
			down = ownCooldown(living, stack, 0f);
		} else if (otherPlayers && isPlayer) {
			Long at = brokenAt.get(living.getId());
			long since = at == null ? Long.MAX_VALUE : now - at;
			down = since < OTHER_DISABLE_MS ? 1f - since / (float) OTHER_DISABLE_MS : 0f;
			if (at != null && since >= OTHER_DISABLE_MS && since < OTHER_DISABLE_MS + FLASH_MS) {
				readyAt.putIfAbsent(living.getId(), at + OTHER_DISABLE_MS);
			}
		} else {
			return 0;
		}
		int rgb;
		if (down > 0f) {
			// Smooth: stays clearly red for the first part, then eases over to green; Solid: red until it works.
			float u = SOLID.equals(mode) ? 1f : (float) Math.pow(down, 0.55);
			rgb = lerp(readyColor, brokenColor, u);
			return rgb;   // no flash while down
		}
		rgb = readyColor;
		int flash = 0;
		Long ready = readyAt.get(living.getId());
		if (readyFlash && ready != null && now - ready < FLASH_MS) {
			float f = 1f - (now - ready) / (float) FLASH_MS;
			flash = Math.round(255 * f);
			rgb = lerp(readyColor, 0xFFFFFF, f * f);
		}
		return (flash << 24) | rgb;
	}

	/** Pack check for the renderer: a tint was chosen (rgb != 0 also covers pure black, which never is a choice here). */
	public static boolean hasTint(int packed) {
		return (packed & 0xFFFFFF) != 0;
	}

	private static int lerp(int a, int b, float u) {
		int r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * u);
		int g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * u);
		int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * u);
		return r << 16 | g << 8 | bl;
	}

	private static ItemStack shieldOf(LivingEntity e) {
		//? if <26.1 {
		ItemStack main = e.getMainHandStack(), off = e.getOffHandStack();
		//?} else {
		/*ItemStack main = e.getMainHandItem(), off = e.getOffhandItem();
		*///?}
		return PvpCompat.isShield(off) ? off : PvpCompat.isShield(main) ? main : null;
	}

	/** 1 right after the shield got disabled .. 0 when it works (your own player only - it's your cooldown). */
	private static float ownCooldown(LivingEntity self, ItemStack stack, float partial) {
		//? if <1.21.2 {
		/*return self instanceof PlayerEntity p ? p.getItemCooldownManager().getCooldownProgress(stack.getItem(), partial) : 0f;
		*///?} else if <26.1 {
		return self instanceof PlayerEntity p ? p.getItemCooldownManager().getCooldownProgress(stack, partial) : 0f;
		//?} else {
		/*return self instanceof Player p ? p.getCooldowns().getCooldownPercent(stack, partial) : 0f;
		*///?}
	}

	@Override
	public List<ConfigField> configFields() {
		return List.of(
				new ConfigField.ChoiceField("Mode", List.of(SMOOTH, SOLID), () -> mode, v -> mode = v),
				new ConfigField.ToggleField("Flash When Ready", () -> readyFlash, v -> readyFlash = v),
				new ConfigField.ToggleField("Sound When Ready", () -> readySound, v -> readySound = v),
				new ConfigField.ToggleField("Other Players' Shields", () -> otherPlayers, v -> otherPlayers = v),
				new ConfigField.ColorField("Ready Color", () -> 0xFF000000 | readyColor, v -> readyColor = v & 0xFFFFFF, false),
				new ConfigField.ColorField("Disabled Color", () -> 0xFF000000 | brokenColor, v -> brokenColor = v & 0xFFFFFF, false));
	}
}
