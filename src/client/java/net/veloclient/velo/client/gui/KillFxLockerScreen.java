package net.veloclient.velo.client.gui;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.veloclient.velo.client.cosmetics.aura.AuraEffects;
import net.veloclient.velo.client.cosmetics.killfx.KillFxEffects;
import net.veloclient.velo.client.cosmetics.killfx.KillFxManager;
import net.veloclient.velo.client.emotes.EmoteSounds;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.KillFxGuiRenderer;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloSlider;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Locker > Kill FX: which kill effect plays where your kills fall - none, one of the free ones (vanilla particles) or
 * one from the Store - with its colour and how loud it is. The selected one plays on the stage (with its sound when
 * you pick it); "Try it" plays it for real in front of you. When it plays is the Kill Effects module's setting.
 */
public final class KillFxLockerScreen extends VeloWindow implements net.veloclient.velo.client.gui.window.MenuPage {

	@Override
	public String railId() {
		return "skins";
	}


	private static final int TILE = 64;
	private static final int TILE_GAP = 8;
	private static final int SWATCH = 13;

	private record Hit(int x, int y, int w, int h, Runnable action) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private final List<Hit> hits = new ArrayList<>();
	private final Map<String, KillFxGuiRenderer.Loop> loops = new HashMap<>();
	/** The effect shown on the stage ("" = none). */
	private String selected;
	private boolean soundArmed;
	private EmoteSounds.Handle playing;
	private String status = "";
	private double scroll;

	public KillFxLockerScreen(Screen parent) {
		super(Text.literal("Locker"), 940, 580);
		returnTo(parent);
		String equipped = KillFxManager.equippedId();
		selected = equipped == null ? "" : equipped;
		onClosed(this::stopSound);
		net.veloclient.velo.client.network.StoreClient.refresh(false, null);
	}

	@Override
	protected String[] headerTabs() {
		return CapeEquipScreen.LOCKER_TABS;
	}

	@Override
	protected int headerTab() {
		return CapeEquipScreen.KILLFX_TAB;
	}

	@Override
	protected void selectHeaderTab(int index) {
		if (index != CapeEquipScreen.KILLFX_TAB) {
			stopSound();
			this.client.setScreen(CapeEquipScreen.open(returnScreen(), index));
		}
	}

	private int stageX() {
		return contentX() + CapeEquipScreen.SLOTS_W + 12;
	}

	private int panelX() {
		return stageX() + CapeEquipScreen.SHOWCASE_WIDTH + 14;
	}

	@Override
	protected void layoutContent() {
		this.clearChildren();
		int px = panelX();
		int pw = contentX() + contentWidth() - px;
		addDrawableChild(new VeloSlider(px, contentY() + 76, Math.min(240, pw), 18, "Volume", 0, 1.5, KillFxManager::volume,
				v -> KillFxManager.setVolume((float) v), v -> Math.round(v * 20) / 20.0, v -> Math.round(v * 100) + "%"));
	}

	private KillFxGuiRenderer.Loop loop(String id) {
		return loops.computeIfAbsent(id, k -> new KillFxGuiRenderer.Loop(KillFxEffects.kind(k)));
	}

	private void select(String id) {
		selected = id;
		if (KillFxManager.IDS.contains(id)) {
			loop(id).restart();
			soundArmed = true;
		}
	}

	private void stopSound() {
		if (playing != null) {
			playing.fadeOut();
			playing = null;
		}
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		CapeEquipScreen.drawSlots(context, this.textRenderer, contentX(), contentY(), CapeEquipScreen.KILLFX_TAB, mouseX, mouseY);
		CapeEquipScreen.drawLoadouts(context, this.textRenderer, contentX(), contentY(), contentBottom(), mouseX, mouseY);
		renderStage(context);

		int px = panelX();
		int pw = contentX() + contentWidth() - px;
		int y = contentY();
		String equipped = KillFxManager.equippedId();
		boolean isEquipped = selected.isEmpty() ? equipped == null : selected.equals(equipped);
		boolean owned = selected.isEmpty() || KillFxManager.owns(selected);
		MenuKit.text(context, this.textRenderer, MenuKit.small("KILL EFFECT"), px, y, MenuKit.TEXT_3);
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.uiBold(KillFxManager.name(selected.isEmpty() ? null : selected)), pw),
				px, y + 11, MenuKit.TEXT);
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(selected.isEmpty() ? "No effect on your kills"
				: KillFxManager.FREE.containsKey(selected) ? "Free · vanilla particles and sounds" : owned ? "Yours" : "In the Store"), pw), px, y + 23, MenuKit.TEXT_2);
		String action = isEquipped ? (selected.isEmpty() ? "Nothing equipped" : "Take it off") : !owned ? "Get it in the Store" : selected.isEmpty() ? "Play none" : "Equip";
		button(context, px, y + 40, 112, action, mouseX, mouseY, () -> {
			if (!owned) {
				stopSound();
				KillFxManager.type(selected).ifPresent(t -> this.client.setScreen(new net.veloclient.velo.client.gui.store.KillFxDetailScreen(this, t)));
				return;
			}
			if (isEquipped) {
				KillFxManager.equip(null);
				status = "No kill effect";
			} else {
				KillFxManager.equip(selected.isEmpty() ? null : selected);
				status = selected.isEmpty() ? "No kill effect" : "Equipped " + KillFxManager.name(selected);
			}
		});
		if (!selected.isEmpty()) {
			button(context, px + 118, y + 40, 112, "Try it in the world", mouseX, mouseY, this::tryInWorld);
		}
		// Colour: per Store effect, one for the free Color Burst / Confetti.
		if (!selected.isEmpty()) {
			boolean free = KillFxManager.FREE.containsKey(selected);
			int sy = y + 112;
			MenuKit.text(context, this.textRenderer, MenuKit.small(free ? "COLOUR (COLOR BURST, CONFETTI)" : "COLOUR"), px, sy - 11, MenuKit.TEXT_3);
			int current = free ? -1 : KillFxManager.color(selected);
			int last = free ? AuraEffects.PALETTE.length : AuraEffects.RAINBOW;
			for (int i = free ? 1 : 0; i <= last; i++) {
				int index = i;
				int sx = px + (free ? i - 1 : i) * (SWATCH + 4);
				int fill = i == 0 ? 0xFF000000 | KillFxEffects.accent(KillFxEffects.kind(selected), AuraEffects.Settings.DEFAULT)
						: i <= AuraEffects.PALETTE.length ? 0xFF000000 | AuraEffects.PALETTE[i - 1] : 0xFFFFFFFF;
				if (i == AuraEffects.RAINBOW) {
					int[] bands = {0xFFFF4D4D, 0xFFFFD84D, 0xFF3FF0FF, 0xFFA65CFF};
					for (int b = 0; b < 4; b++) {
						context.fill(sx + b * SWATCH / 4, sy, sx + (b + 1) * SWATCH / 4, sy + SWATCH, bands[b]);
					}
				} else {
					VeloDraw.fillRounded(context, sx, sy, SWATCH, SWATCH, 4, fill);
				}
				boolean chosen = free ? (KillFxManager.freeColor() & 0xFFFFFF) == (fill & 0xFFFFFF) : i == current;
				if (chosen) {
					VeloDraw.strokeRounded(context, sx - 2, sy - 2, SWATCH + 4, SWATCH + 4, 5, 0xFFFFFFFF);
				}
				hits.add(new Hit(sx, sy, SWATCH, SWATCH, () -> {
					if (free) {
						KillFxManager.setFreeColor(fill);
					} else {
						KillFxManager.setColor(selected, index);
						select(selected);
					}
				}));
			}
		}
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(status), pw), px, y + 134, MenuKit.TEXT_2);
		renderGrid(context, px, pw, y + 156, mouseX, mouseY);

		// The stage's sound, starting with its animation.
		if (KillFxManager.IDS.contains(selected) && loop(selected).justStarted() && soundArmed) {
			soundArmed = false;
			stopSound();
			playing = EmoteSounds.ui(KillFxEffects.sound(KillFxEffects.kind(selected)), 0.8f * KillFxManager.volume());
		}
	}

	private void renderStage(DrawContext context) {
		int x = stageX();
		int y = contentY();
		int w = CapeEquipScreen.SHOWCASE_WIDTH;
		int h = contentBottom() - y;
		VeloDraw.fillRounded(context, x, y, w, h, 10, 0xFF0E0E14);
		VeloDraw.strokeRounded(context, x, y, w, h, 10, 0x17FFFFFF);
		if (KillFxManager.IDS.contains(selected)) {
			context.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
			KillFxGuiRenderer.draw(context, loop(selected), KillFxManager.settings(selected), x + w / 2f, y + h * 0.72f, Math.min(w / 5.2f, h / 5.4f),
					0.45 + System.currentTimeMillis() / 9000.0);
			context.disableScissor();
		} else {
			String line = selected.isEmpty() ? "No kill effect" : "Vanilla particles";
			String hint = selected.isEmpty() ? "Pick one on the right" : "Press \"Try it in the world\"";
			MenuKit.icon(context, "skull", x + w / 2 - 12, y + h / 2 - 30, 24, MenuKit.TEXT_3);
			MenuKit.text(context, this.textRenderer, MenuKit.uiBold(line), x + (w - this.textRenderer.getWidth(MenuKit.uiBold(line))) / 2, y + h / 2 + 2, MenuKit.TEXT_2);
			MenuKit.text(context, this.textRenderer, MenuKit.ui(hint), x + (w - this.textRenderer.getWidth(MenuKit.ui(hint))) / 2, y + h / 2 + 14, MenuKit.TEXT_3);
		}
	}

	private void renderGrid(DrawContext context, int px, int pw, int gy, int mouseX, int mouseY) {
		List<String> ids = new ArrayList<>();
		ids.add("");
		ids.addAll(KillFxManager.FREE.keySet());
		for (var t : KillFxManager.catalog()) {
			if (KillFxManager.owns(t.id())) {
				ids.add(t.id());
			}
		}
		for (var t : KillFxManager.catalog()) {
			if (!KillFxManager.owns(t.id()) && t.inStore()) {
				ids.add(t.id());
			}
		}
		int columns = Math.max(1, (pw + TILE_GAP) / (TILE + TILE_GAP));
		int rows = (ids.size() + columns - 1) / columns;
		int maxScroll = Math.max(0, rows * (TILE + 14 + TILE_GAP) - (contentBottom() - gy));
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		context.enableScissor(px, gy, px + pw, contentBottom());
		for (int i = 0; i < ids.size(); i++) {
			int tx = px + (i % columns) * (TILE + TILE_GAP);
			int ty = gy + (i / columns) * (TILE + 14 + TILE_GAP) - (int) scroll;
			if (ty + TILE + 14 < gy || ty > contentBottom()) {
				continue;
			}
			drawTile(context, ids.get(i), tx, ty, mouseX, mouseY, gy);
		}
		context.disableScissor();
	}

	private void drawTile(DrawContext context, String id, int x, int y, int mouseX, int mouseY, int clipTop) {
		boolean premium = KillFxManager.IDS.contains(id);
		boolean owned = id.isEmpty() || KillFxManager.owns(id);
		boolean hovered = mouseX >= x && mouseX < x + TILE && mouseY >= y && mouseY < y + TILE + 14 && mouseY >= clipTop;
		boolean active = id.equals(selected);
		boolean worn = id.isEmpty() ? KillFxManager.equippedId() == null : id.equals(KillFxManager.equippedId());
		int accent = premium ? KillFxManager.type(id).map(KillFxManager.KillFxType::accent).orElse(MenuKit.accent()) : MenuKit.accent();
		VeloDraw.fillRounded(context, x, y, TILE, TILE, 8, active ? VeloUi.withAlpha(accent, 0x40) : hovered ? 0xFF1E1E26 : 0xFF15151B);
		VeloDraw.strokeRounded(context, x, y, TILE, TILE, 8, active ? VeloUi.withAlpha(accent, 0xC0) : 0x17FFFFFF);
		if (premium) {
			context.enableScissor(x + 1, Math.max(y + 1, clipTop), x + TILE - 1, y + TILE - 1);
			KillFxGuiRenderer.draw(context, loop("tile:" + id, id), owned ? KillFxManager.settings(id) : AuraEffects.Settings.DEFAULT, x + TILE / 2f, y + TILE * 0.78f,
					TILE / 5.4f, 0.5);
			context.disableScissor();
		} else {
			MenuKit.icon(context, freeIcon(id), x + TILE / 2 - 9, y + TILE / 2 - 12, 18, id.isEmpty() ? MenuKit.TEXT_3 : MenuKit.TEXT_2);
			if (!id.isEmpty()) {
				context.drawTextWithShadow(this.textRenderer, "FREE", x + (TILE - this.textRenderer.getWidth("FREE")) / 2, y + TILE - 14, 0xFF7BE07B);
			}
		}
		if (worn) {
			VeloDraw.fillCircle(context, x + 8f, y + 8f, 3f, MenuKit.GREEN);
		}
		if (!owned) {
			VeloDraw.fillRounded(context, x, y, TILE, TILE, 8, 0x55000000);
			context.drawTextWithShadow(this.textRenderer, "Store", x + TILE - 4 - this.textRenderer.getWidth("Store"), y + 4, 0xFFFFD24A);
		}
		String name = MenuKit.fit(this.textRenderer, MenuKit.ui(KillFxManager.name(id.isEmpty() ? null : id)), TILE).getString();
		context.drawTextWithShadow(this.textRenderer, name, x + (TILE - this.textRenderer.getWidth(name)) / 2, y + TILE + 3, owned ? 0xFFE8E8F0 : 0xFF8A8A95);
		int top = Math.max(y, clipTop);
		hits.add(new Hit(x, top, TILE, Math.max(0, y + TILE + 14 - top), () -> select(id)));
	}

	private static String freeIcon(String id) {
		return switch (id) {
			case "" -> "close";
			case "free_lightning", "free_surge" -> "bolt";
			case "free_burst" -> "theme_editor";
			case "free_totem" -> "trophy";
			case "free_firework" -> "party";
			case "free_void" -> "teleport";
			case "free_confetti" -> "gift";
			default -> "skull";
		};
	}

	/** A small looping preview per tile (its own loop, apart from the stage's). */
	private KillFxGuiRenderer.Loop loop(String key, String id) {
		return loops.computeIfAbsent(key, k -> new KillFxGuiRenderer.Loop(KillFxEffects.kind(id)));
	}

	private void tryInWorld() {
		var player = net.veloclient.velo.client.pvp.PvpCompat.localPlayer();
		if (player == null) {
			status = "Join a world to try it";
			return;
		}
		float yaw = (float) Math.toRadians(net.veloclient.velo.client.pvp.PvpCompat.cameraRotation()[0]);
		double x = net.veloclient.velo.client.pvp.PvpCompat.x(player) - Math.sin(yaw) * 4;
		double z = net.veloclient.velo.client.pvp.PvpCompat.z(player) + Math.cos(yaw) * 4;
		double py = net.veloclient.velo.client.pvp.PvpCompat.y(player);
		double ground = net.veloclient.velo.client.pvp.PvpCompat.groundBelow(x, py + 2, z, 6);
		double gy = Double.isNaN(ground) ? py : ground;
		String id = selected;
		stopSound();
		this.client.setScreen(null);
		if (KillFxManager.FREE.containsKey(id)) {
			net.veloclient.velo.client.modules.cosmetics.KillEffectsModule.preview(id, x, gy, z);
		} else {
			KillFxManager.play(id, x, gy, z, true);
		}
	}

	private void button(DrawContext context, int x, int y, int w, String label, int mouseX, int mouseY, Runnable action) {
		boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 18;
		VeloDraw.fillRounded(context, x, y, w, 18, 6, hovered ? 0x1AFFFFFF : 0x0DFFFFFF);
		VeloDraw.strokeRounded(context, x, y, w, 18, 6, hovered ? 0x38FFFFFF : 0x17FFFFFF);
		MenuKit.text(context, this.textRenderer, MenuKit.ui(label), x + (w - this.textRenderer.getWidth(MenuKit.ui(label))) / 2, y + 5,
				hovered ? MenuKit.TEXT : MenuKit.TEXT_2);
		hits.add(new Hit(x, y, w, 18, action));
	}

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		if (CapeEquipScreen.loadoutClick(click.x(), click.y(), click.button(), contentX(), contentY(), contentBottom())) {
			return true;
		}
		int slot = CapeEquipScreen.slotAt((int) click.x(), (int) click.y(), contentX(), contentY());
		if (slot >= 0) {
			selectHeaderTab(slot);
			return true;
		}
		for (Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
		if (mouseX >= panelX()) {
			scroll -= vertical * 24;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
	}
}
