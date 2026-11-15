package net.veloclient.legacy.menu;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiElement;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.platform.GlStateManager;
import net.minecraft.resource.Identifier;
import net.veloclient.legacy.Json;
import net.veloclient.legacy.cape.CapeTextures;
import net.veloclient.legacy.cape.Capes;
import net.veloclient.legacy.cape.LocalCape;
import net.veloclient.legacy.net.VeloNetwork;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The Locker on 1.8.9: pick which of your Velo cloaks you wear. Same library and the same per-account choice as the
 * launcher and the modern game, so a change here shows up there too (and every Velo player sees it). Store cloaks are
 * only listed when this account owns them; your own uploaded pictures always are.
 */
public final class LockerScreen extends Screen {

	private static volatile Set<String> owned;
	private static volatile boolean loading;
	private final Screen parent;
	private final List<Runnable> clicks = new ArrayList<>();
	private final List<int[]> boxes = new ArrayList<>();
	private int scroll;

	public LockerScreen(Screen parent) {
		this.parent = parent;
		loadOwned();
	}

	@Override
	public boolean shouldPauseGame() {
		return false;
	}

	/** What this account owns in the Store (asked once per game, needs Velo Network). */
	private static void loadOwned() {
		if (owned != null || loading || VeloNetwork.sessionToken() == null || VeloNetwork.base() == null) {
			return;
		}
		loading = true;
		Thread t = new Thread(() -> {
			try {
				HttpResponse<String> r = VeloNetwork.HTTP.send(HttpRequest.newBuilder(URI.create(VeloNetwork.base() + "/v1/store/wallet"))
						.header("Authorization", "Bearer " + VeloNetwork.sessionToken()).timeout(Duration.ofSeconds(15)).GET().build(),
						HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
				if (r.statusCode() == 200) {
					JsonObject o = Json.parse(r.body());
					Set<String> items = new HashSet<>();
					if (o != null && o.has("owned") && o.get("owned").isJsonArray()) {
						for (JsonElement e : o.getAsJsonArray("owned")) {
							items.add(e.getAsString());
						}
					}
					owned = items;
				}
			} catch (Exception ignored) {
				// offline: only your own pictures and what you wear are listed
			} finally {
				loading = false;
			}
		}, "velo-locker-wallet");
		t.setDaemon(true);
		t.start();
	}

	private List<LocalCape.Bundle> cloaks(String equipped) {
		Set<String> mine = owned;
		List<LocalCape.Bundle> list = new ArrayList<>();
		for (LocalCape.Bundle b : LocalCape.all()) {
			boolean store = b.sourceItemId() != null && !b.sourceItemId().isEmpty();
			if (!store || b.id().equals(equipped) || mine != null && mine.contains(b.sourceItemId())) {
				list.add(b);
			}
		}
		list.sort(Comparator.comparing(LocalCape::name, String.CASE_INSENSITIVE_ORDER));
		return list;
	}

	private void hit(int x, int y, int w, int h, Runnable action) {
		boxes.add(new int[] {x, y, w, h});
		clicks.add(action);
	}

	private static boolean over(int mx, int my, int x, int y, int w, int h) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	@Override
	public void render(int mouseX, int mouseY, float tickDelta) {
		boxes.clear();
		clicks.clear();
		fill(0, 0, width, height, 0x80000000);
		int pw = Math.min(420, width - 20);
		int ph = Math.min(270, height - 20);
		int px = (width - pw) / 2;
		int py = (height - ph) / 2;
		fill(px, py, px + pw, py + ph, 0xF0101016);
		fill(px, py, px + pw, py + 1, 0x30FFFFFF);
		textRenderer.drawWithShadow("Locker", px + 10, py + 9, 0xFFFFFFFF);
		textRenderer.drawWithShadow("Cloaks", px + 14 + textRenderer.getWidth("Locker"), py + 9, 0xFF8A8A95);
		String back = "Back";
		int bw = textRenderer.getWidth(back) + 14;
		boolean bh = over(mouseX, mouseY, px + pw - bw - 8, py + 5, bw, 16);
		fill(px + pw - bw - 8, py + 5, px + pw - 8, py + 21, bh ? 0x40FFFFFF : 0x20FFFFFF);
		textRenderer.drawWithShadow(back, px + pw - bw - 1, py + 9, 0xFFFFFFFF);
		hit(px + pw - bw - 8, py + 5, bw, 16, () -> minecraft.openScreen(parent));

		String uuid = Capes.ownUuid();
		if (uuid == null) {
			textRenderer.drawWithShadow("Sign in with a Minecraft account to wear cloaks.", px + 10, py + 40, 0xFFB4B4C0);
			return;
		}
		String equipped = LocalCape.equippedId(uuid);
		List<LocalCape.Bundle> list = cloaks(equipped);
		int top = py + 30;
		int bottom = py + ph - 22;
		int card = 58;
		int gap = 6;
		int cols = Math.max(1, (pw - 20 + gap) / (card + gap));
		int total = list.size() + 1;
		int rows = (total + cols - 1) / cols;
		int cardH = 82;
		int maxScroll = Math.max(0, rows * (cardH + gap) - (bottom - top));
		scroll = Math.max(0, Math.min(scroll, maxScroll));
		int left = px + 10 + (pw - 20 - (cols * (card + gap) - gap)) / 2;
		enableScissor(px, top, pw, bottom - top);
		for (int i = 0; i < total; i++) {
			int cx = left + (i % cols) * (card + gap);
			int cy = top + (i / cols) * (cardH + gap) - scroll;
			if (cy + cardH < top || cy > bottom) {
				continue;
			}
			LocalCape.Bundle b = i == 0 ? null : list.get(i - 1);
			boolean worn = b == null ? equipped == null : b.id().equals(equipped);
			boolean hov = over(mouseX, mouseY, cx, cy, card, cardH) && mouseY >= top && mouseY < bottom;
			fill(cx, cy, cx + card, cy + cardH, hov ? 0xFF1E1E26 : 0xFF17171D);
			if (worn) {
				fill(cx, cy, cx + card, cy + 1, VeloMenuScreen.ACCENT);
				fill(cx, cy + cardH - 1, cx + card, cy + cardH, VeloMenuScreen.ACCENT);
				fill(cx, cy, cx + 1, cy + cardH, VeloMenuScreen.ACCENT);
				fill(cx + card - 1, cy, cx + card, cy + cardH, VeloMenuScreen.ACCENT);
			}
			// the front of the cloak, 10 x 16 cape pixels, drawn 3x
			int artX = cx + (card - 30) / 2;
			int artY = cy + 6;
			if (b == null) {
				fill(artX, artY, artX + 30, artY + 48, 0xFF24242C);
				textRenderer.drawWithShadow("-", artX + 13, artY + 20, 0xFF8A8A95);
			} else {
				LocalCape.Bundle bundle = b;
				Identifier tex = CapeTextures.get("local:" + b.id() + "@" + b.file().toFile().lastModified(), () -> LocalCape.art(bundle));
				if (tex != null) {
					minecraft.getTextureManager().bind(tex);
					GlStateManager.enableBlend();
					GlStateManager.color4f(1f, 1f, 1f, 1f);
					GuiElement.drawTexture(artX, artY, 1.0F, 1.0F, 10, 16, 30, 48, 64.0F, 32.0F);
				} else {
					fill(artX, artY, artX + 30, artY + 48, 0xFF24242C);
				}
			}
			String name = b == null ? "None" : LocalCape.name(b);
			String shown = textRenderer.trim(name, card - 6);
			textRenderer.drawWithShadow(shown, cx + (card - textRenderer.getWidth(shown)) / 2, cy + 60, worn ? 0xFFFFFFFF : 0xFFB4B4C0);
			if (worn) {
				textRenderer.draw("worn", cx + (card - textRenderer.getWidth("worn")) / 2, cy + 70, 0xFF6A90FF, false);
			}
			if (cy >= top && cy + cardH <= bottom) {
				String id = b == null ? null : b.id();
				hit(cx, cy, card, cardH, () -> LocalCape.equip(uuid, id));
			}
		}
		disableScissor();
		String hint = owned == null && loading ? "Loading your Store cloaks..." : "Get more cloaks in the Store (launcher or modern Minecraft).";
		textRenderer.draw(hint, px + 10, py + ph - 14, 0xFF8A8A95, false);
	}

	@Override
	protected void mouseClicked(int mouseX, int mouseY, int button) {
		if (button != 0) {
			return;
		}
		for (int i = boxes.size() - 1; i >= 0; i--) {
			int[] b = boxes.get(i);
			if (over(mouseX, mouseY, b[0], b[1], b[2], b[3])) {
				clicks.get(i).run();
				return;
			}
		}
	}

	@Override
	public void handleMouse() {
		int wheel = org.lwjgl.input.Mouse.getEventDWheel();
		if (wheel != 0) {
			scroll += wheel > 0 ? -24 : 24;
		}
		super.handleMouse();
	}

	@Override
	protected void keyPressed(char chr, int key) {
		if (key == 1) {
			minecraft.openScreen(parent);
			return;
		}
		super.keyPressed(chr, key);
	}

	private void enableScissor(int x, int y, int w, int h) {
		net.minecraft.client.render.Window window = new net.minecraft.client.render.Window(minecraft);
		int s = window.getScale();
		org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
		org.lwjgl.opengl.GL11.glScissor(x * s, minecraft.height - (y + h) * s, w * s, h * s);
	}

	private void disableScissor() {
		org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
	}
}
