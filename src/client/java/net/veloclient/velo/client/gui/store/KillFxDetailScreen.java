package net.veloclient.velo.client.gui.store;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.cosmetics.aura.AuraEffects;
import net.veloclient.velo.client.cosmetics.killfx.KillFxEffects;
import net.veloclient.velo.client.cosmetics.killfx.KillFxManager;
import net.veloclient.velo.client.gui.widget.KillFxGuiRenderer;
import net.veloclient.velo.client.gui.widget.VeloButton;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloStyle;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.network.StoreClient;

import java.util.List;

/**
 * One kill effect: it plays over and over on a figure (with its sound the first time and on Replay), the price and Buy
 * (Velo Coins) or Equip, its colour, and "Try it" - which plays it for real a few blocks in front of you.
 */
public final class KillFxDetailScreen extends VeloWindow {

	private static final int PREVIEW_WIDTH = 250;
	private static final int SWATCH = 14;

	private final KillFxManager.KillFxType type;
	private final KillFxGuiRenderer.Loop loop;
	private boolean playSound = true;
	private net.veloclient.velo.client.emotes.EmoteSounds.Handle playing;
	private String status = "";
	private boolean busy;
	private int swatchX;
	private int swatchY;

	public KillFxDetailScreen(Screen parent, KillFxManager.KillFxType type) {
		super(Text.literal(type.name()), 620, 400);
		this.type = type;
		this.loop = new KillFxGuiRenderer.Loop(type.kind());
		returnTo(parent);
		onClosed(this::stopSound);
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		int ix = contentX() + PREVIEW_WIDTH + 16;
		int iw = contentX() + contentWidth() - ix;
		boolean owned = KillFxManager.owns(type.id());
		boolean equipped = type.id().equals(KillFxManager.equippedId());
		int price = StoreClient.price(type.itemId(), type.price());
		VeloButton action = new VeloButton(ix, contentY() + 64, iw, 20, Text.literal(busy ? "..." : equipped ? "Take it off"
				: owned ? "Equip" : StoreClient.buyLabel(price)), b -> action(owned, equipped));
		if (!equipped) {
			action.primary();
		}
		addDrawableChild(action);
		int half = (iw - 8) / 2;
		addDrawableChild(new VeloButton(ix, contentY() + 90, half, 20, Text.literal("Try it in the world"), b -> tryInWorld()));
		addDrawableChild(new VeloButton(ix + half + 8, contentY() + 90, iw - half - 8, 20, Text.literal("Replay with sound"), b -> {
			playSound = true;
			loop.restart();
		}));
		addDrawableChild(new net.veloclient.velo.client.gui.widget.VeloSlider(ix, contentY() + 204, iw, 18, "Volume", 0, 1.5, KillFxManager::volume,
				v -> KillFxManager.setVolume((float) v), v -> Math.round(v * 20) / 20.0, v -> Math.round(v * 100) + "%"));
		swatchX = ix;
		swatchY = contentY() + 150;
		addDrawableChild(new VeloButton(ix, swatchY + SWATCH + 12, half, 18, Text.literal("Own colours"), b -> KillFxManager.setColor(type.id(), 0)));
	}

	private void tryInWorld() {
		var player = net.veloclient.velo.client.pvp.PvpCompat.localPlayer();
		if (player == null) {
			status = "Join a world to try it";
			return;
		}
		// A few blocks ahead of you, on the ground.
		float yaw = (float) Math.toRadians(net.veloclient.velo.client.pvp.PvpCompat.cameraRotation()[0]);
		double x = net.veloclient.velo.client.pvp.PvpCompat.x(player) - Math.sin(yaw) * 4;
		double z = net.veloclient.velo.client.pvp.PvpCompat.z(player) + Math.cos(yaw) * 4;
		double py = net.veloclient.velo.client.pvp.PvpCompat.y(player);
		double ground = net.veloclient.velo.client.pvp.PvpCompat.groundBelow(x, py + 2, z, 6);
		stopSound();
		this.client.setScreen(null);
		KillFxManager.play(type.id(), x, Double.isNaN(ground) ? py : ground, z, true);
	}

	private void stopSound() {
		if (playing != null) {
			playing.fadeOut();
			playing = null;
		}
	}

	private void action(boolean owned, boolean equipped) {
		if (busy) {
			return;
		}
		if (equipped) {
			KillFxManager.equip(null);
			status = "Back to the Kill Effects module's own effects";
			layoutContent();
			return;
		}
		if (owned) {
			KillFxManager.equip(type.id());
			status = "Equipped - it plays on your kills (Kill Effects module)";
			layoutContent();
			return;
		}
		if (!StoreClient.connected()) {
			status = "Connect to Velo Network first (in a world with a Microsoft account)";
			return;
		}
		busy = true;
		status = "Buying...";
		layoutContent();
		StoreClient.buyOrClaim(type.itemId(), () -> {
			busy = false;
			KillFxManager.equip(type.id());
			status = "It's yours - equipped!";
			layoutContent();
		}, error -> {
			busy = false;
			status = error;
			layoutContent();
		});
	}

	private int swatchColor(int i) {
		if (i == 0) {
			return 0xFF000000 | KillFxEffects.accent(type.kind(), AuraEffects.Settings.DEFAULT);
		}
		return i <= AuraEffects.PALETTE.length ? 0xFF000000 | AuraEffects.PALETTE[i - 1] : 0xFFFFFFFF;
	}

	@Override
	public boolean mouseClicked(net.minecraft.client.gui.Click click, boolean doubled) {
		for (int i = 0; i <= AuraEffects.RAINBOW; i++) {
			int sx = swatchX + i * (SWATCH + 5);
			if (click.x() >= sx && click.x() < sx + SWATCH && click.y() >= swatchY && click.y() < swatchY + SWATCH) {
				KillFxManager.setColor(type.id(), i);
				loop.restart();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public void render(DrawContext context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		// The preview: a dark stage with the effect playing on a figure.
		int px = contentX();
		int py = contentY();
		int ph = contentBottom() - contentY();
		VeloDraw.fillRounded(context, px, py, PREVIEW_WIDTH, ph, 8, 0xFF0E0E14);
		context.enableScissor(px + 1, py + 1, px + PREVIEW_WIDTH - 1, py + ph - 1);
		KillFxGuiRenderer.draw(context, loop, KillFxManager.settings(type.id()), px + PREVIEW_WIDTH / 2f, py + ph * 0.74f, Math.min(PREVIEW_WIDTH / 5.4f, ph / 5.2f),
				0.45 + System.currentTimeMillis() / 9000.0);
		context.disableScissor();
		if (loop.justStarted() && playSound) {
			playSound = false;
			stopSound();
			playing = net.veloclient.velo.client.emotes.EmoteSounds.ui(KillFxEffects.sound(type.kind()), 0.8f * KillFxManager.volume());
		}

		int ix = contentX() + PREVIEW_WIDTH + 16;
		int iw = contentX() + contentWidth() - ix;
		int y = contentY();
		int accent = type.accent();
		String tag = "KILL EFFECT";
		VeloDraw.fillRounded(context, ix, y, this.textRenderer.getWidth(tag) + 12, 12, 4, VeloUi.withAlpha(accent, 0xD0));
		context.drawTextWithShadow(this.textRenderer, tag, ix + 5, y + 2, 0xFF101014);
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(type.name(), iw), ix, y + 18, VeloStyle.text());
		List<String> lines = VeloUi.wrap(type.description(), iw);
		for (int i = 0; i < Math.min(2, lines.size()); i++) {
			context.drawTextWithShadow(this.textRenderer, lines.get(i), ix, y + 32 + i * 10, VeloStyle.textMuted());
		}
		List<String> how = VeloUi.wrap("Plays where your kills fall, with its own sound. When it plays is set in the Kill Effects module.", iw);
		for (int i = 0; i < Math.min(2, how.size()); i++) {
			context.drawTextWithShadow(this.textRenderer, how.get(i), ix, y + 116 + i * 10, VeloStyle.textMuted());
		}
		int current = KillFxManager.color(type.id());
		context.drawTextWithShadow(this.textRenderer, "Colour", swatchX, swatchY - 11, VeloStyle.textMuted());
		for (int i = 0; i <= AuraEffects.RAINBOW; i++) {
			int sx = swatchX + i * (SWATCH + 5);
			if (i == AuraEffects.RAINBOW) {
				int[] bands = {0xFFFF4D4D, 0xFFFFD84D, 0xFF3FF0FF, 0xFFA65CFF};
				for (int b = 0; b < 4; b++) {
					context.fill(sx + b * SWATCH / 4, swatchY, sx + (b + 1) * SWATCH / 4, swatchY + SWATCH, bands[b]);
				}
			} else {
				VeloDraw.fillRounded(context, sx, swatchY, SWATCH, SWATCH, 4, swatchColor(i));
			}
			if (i == 0) {
				context.drawTextWithShadow(this.textRenderer, "A", sx + 4, swatchY + 3, 0xFF101014);
			}
			if (i == current) {
				VeloDraw.strokeRounded(context, sx - 2, swatchY - 2, SWATCH + 4, SWATCH + 4, 5, 0xFFFFFFFF);
			}
		}
		context.drawTextWithShadow(this.textRenderer, VeloUi.trim(status, iw), ix, contentBottom() - 12, VeloStyle.text());
	}
}
