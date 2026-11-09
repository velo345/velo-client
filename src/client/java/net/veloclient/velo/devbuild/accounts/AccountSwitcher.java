package net.veloclient.velo.devbuild.accounts;

/**
 * Account switcher in the title screen's top left corner (vanilla menu and Velo menu alike): your account with its head,
 * a list of the accounts signed in to the Velo launcher, one click to play as another - no restart. Expired sign-ins
 * are renewed with Microsoft right here (the launcher's own refresh token, written back so both stay in sync). Next to
 * it: Velo Rewind's replays, when that module is on.
 */
//? if <26.2 {
public final class AccountSwitcher {

	private AccountSwitcher() {
	}

	public static void register() {
	}
}
//?} else {
/*import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.User;
import net.minecraft.world.entity.player.PlayerSkin;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class AccountSwitcher {

	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("Velo Accounts");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	// the Velo launcher's Microsoft app (its refresh tokens belong to it)
	private static final String CLIENT_ID = "6cc50134-1c8d-43dc-9265-e107d0540248";
	private static final String SCOPE = "XboxLive.signin offline_access";

	private record Account(String uuid, String name, JsonObject raw) {
	}

	private record Hit(int x, int y, int w, int h, Runnable action) {
	}

	private static final List<Hit> HITS = new ArrayList<>();
	private static final Map<String, java.util.function.Supplier<PlayerSkin>> SKINS = new HashMap<>();
	private static List<Account> accounts = List.of();
	private static long readAt;
	private static boolean open;
	private static float openAnim;
	private static volatile String status;
	private static volatile boolean switching;
	private static boolean broken;

	private AccountSwitcher() {
	}

	public static void register() {
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (!(screen instanceof TitleScreen)) {
				return;
			}
			open = false;
			ScreenEvents.afterRender(screen).register((s, g, mouseX, mouseY, delta) -> {
				if (broken) {
					return;
				}
				try {
					draw(g, mouseX, mouseY);
				} catch (RuntimeException | LinkageError e) {
					broken = true; // never take the title screen down with it
					LOG.error("Account switcher failed - hidden for this session", e);
				}
			});
			ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> {
				try {
					return broken || !click(click.x(), click.y());
				} catch (RuntimeException e) {
					broken = true;
					return true;
				}
			});
		});
		if (Boolean.getBoolean("velo.accountTest")) {
			// self test: the open list on the title screen, a screenshot, quit
			int[] ticks = {0};
			net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (client.gui.screen() == null) {
					return; // still loading
				}
				ticks[0]++;
				if (ticks[0] == 40 && !(client.gui.screen() instanceof TitleScreen)) {
					LOG.info("Account switcher test: on {}, opening the title screen", client.gui.screen());
					client.setScreen(new TitleScreen());
				}
				if (ticks[0] == 60) {
					open = true;
				}
				if (ticks[0] == 90) {
					net.minecraft.client.Screenshot.grab(client.gameDirectory, "account-switcher.png", client.gameRenderer.mainRenderTarget(), 1, m -> { });
				}
				if (ticks[0] == 110) {
					client.stop();
				}
			});
		}
	}

	// ---- accounts (the launcher's file) ----

	private static Path file() {
		return net.veloclient.velo.config.VeloPaths.config().resolve("account.json");
	}

	private static List<Account> accounts() {
		if (System.currentTimeMillis() - readAt < 3000) {
			return accounts;
		}
		readAt = System.currentTimeMillis();
		List<Account> out = new ArrayList<>();
		try {
			if (Files.exists(file())) {
				var root = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8));
				JsonArray list = root.isJsonObject() && root.getAsJsonObject().has("accounts") ? root.getAsJsonObject().getAsJsonArray("accounts")
						: new JsonArray();
				if (root.isJsonObject() && !root.getAsJsonObject().has("accounts") && root.getAsJsonObject().has("uuid")) {
					list.add(root); // the launcher's old single-account file
				}
				for (var e : list) {
					JsonObject o = e.getAsJsonObject();
					if (o.has("uuid") && o.has("username")) {
						out.add(new Account(dashed(o.get("uuid").getAsString()), o.get("username").getAsString(), o));
					}
				}
			}
		} catch (Exception e) {
			LOG.warn("Couldn't read the launcher's accounts", e);
		}
		accounts = out;
		return out;
	}

	private static String dashed(String uuid) {
		String u = uuid.replace("-", "");
		if (u.length() != 32) {
			return uuid;
		}
		return u.substring(0, 8) + "-" + u.substring(8, 12) + "-" + u.substring(12, 16) + "-" + u.substring(16, 20) + "-" + u.substring(20);
	}

	private static PlayerSkin skin(String uuid, String name) {
		var lookup = SKINS.computeIfAbsent(uuid, k -> {
			try {
				return Minecraft.getInstance().getSkinManager().createLookup(new com.mojang.authlib.GameProfile(UUID.fromString(uuid), name), false);
			} catch (Exception e) {
				return () -> net.minecraft.client.resources.DefaultPlayerSkin.get(UUID.fromString(uuid));
			}
		});
		return lookup.get();
	}

	// ---- drawing ----

	private static void draw(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		HITS.clear();
		User user = mc.getUser();
		String current = user.getProfileId().toString();
		// top right: the account at the edge, Velo Rewind left of it
		var name = MenuKit.uiBold(user.getName());
		int w = 12 + 6 + MenuKit.width(font, name) + 22;
		int h = 22;
		int x = g.guiWidth() - 8 - w;
		int y = 8;
		float hv = MenuKit.hover("acc-chip", MenuKit.inside(mouseX, mouseY, x, y, w, h));
		VeloDraw.fillRounded(g, x, y, w, h, 8, VeloAnim.lerpArgb(0xB0101015, 0xD01A1A20, Math.max(hv, open ? 1 : 0)));
		VeloDraw.strokeRounded(g, x, y, w, h, 8, 0x26FFFFFF);
		net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(g, skin(current, user.getName()), x + 5, y + 5, 12);
		MenuKit.text(g, font, name, x + 23, y + (h - MenuKit.H_UI) / 2, MenuKit.TEXT);
		// chevron
		float cx = x + w - 11;
		float cy = y + h / 2f;
		float t = open ? 1 : 0;
		VeloDraw.line(g, cx - 3, cy - 1.5f + 3 * t, cx, cy + 1.5f - 3 * t, 1.2f, MenuKit.TEXT_2);
		VeloDraw.line(g, cx, cy + 1.5f - 3 * t, cx + 3, cy - 1.5f + 3 * t, 1.2f, MenuKit.TEXT_2);
		HITS.add(new Hit(x, y, w, h, () -> open = !open));
		if (rewindAvailable()) {
			var label = MenuKit.ui("Velo Rewind");
			int rw = 22 + MenuKit.width(font, label) + 10;
			int rx = x - 6 - rw;
			float rh = MenuKit.hover("acc-rewind", MenuKit.inside(mouseX, mouseY, rx, y, rw, h));
			VeloDraw.fillRounded(g, rx, y, rw, h, 8, VeloAnim.lerpArgb(0xB0101015, 0xD01A1A20, rh));
			VeloDraw.strokeRounded(g, rx, y, rw, h, 8, 0x26FFFFFF);
			VeloDraw.fillCircle(g, rx + 11f, y + h / 2f, 3.5f, 0xFFFF4D57);
			VeloDraw.strokeArc(g, rx + 11f, y + h / 2f, 6f, 1.1f, 0, 360, 0x80FF4D57);
			MenuKit.text(g, font, label, rx + 22, y + (h - MenuKit.H_UI) / 2, MenuKit.TEXT);
			HITS.add(new Hit(rx, y, rw, h, AccountSwitcher::openRewind));
		}
		if (status != null) {
			var st = MenuKit.ui(status);
			MenuKit.text(g, font, st, g.guiWidth() - 10 - MenuKit.width(font, st), y + h + 4, switching ? MenuKit.TEXT_2 : MenuKit.RED_TEXT);
		}
		openAnim = VeloAnim.step(openAnim, open ? 1f : 0f, 1 / 60f * 6f);
		if (openAnim < 0.02f) {
			return;
		}
		// the list
		List<Account> list = accounts();
		int lw = Math.max(170, w);
		x = g.guiWidth() - 8 - lw; // the list hangs from the right edge
		int rowH = 20;
		int ly = y + h + (status != null ? 16 : 4);
		int lh = Math.max(1, list.size()) * rowH + 18 + 8;
		int a = Math.round(255 * openAnim);
		int shown = Math.round(lh * openAnim);
		g.enableScissor(x, ly, x + lw, ly + shown);
		VeloDraw.fillRounded(g, x, ly, lw, lh, 8, (Math.round(0xE0 * openAnim) << 24) | 0x101015);
		VeloDraw.strokeRounded(g, x, ly, lw, lh, 8, (Math.round(0x26 * openAnim) << 24) | 0xFFFFFF);
		int ry = ly + 4;
		if (list.isEmpty()) {
			MenuKit.text(g, font, MenuKit.ui("No accounts from the Velo launcher"), x + 10, ry + 6, MenuKit.TEXT_2);
			ry += rowH;
		}
		for (Account acc : list) {
			boolean on = acc.uuid().equals(current);
			float rh = MenuKit.hover("acc-" + acc.uuid(), MenuKit.inside(mouseX, mouseY, x + 4, ry, lw - 8, rowH));
			if (on || rh > 0.01f) {
				VeloDraw.fillRounded(g, x + 4, ry, lw - 8, rowH, 6, on ? 0x1EFFFFFF : (Math.round(0x14 * rh) << 24) | 0xFFFFFF);
			}
			net.minecraft.client.gui.components.PlayerFaceExtractor.extractRenderState(g, skin(acc.uuid(), acc.name()), x + 10, ry + 4, 12);
			MenuKit.text(g, font, MenuKit.ui(acc.name()), x + 28, ry + (rowH - MenuKit.H_UI) / 2, on ? MenuKit.TEXT : MenuKit.TEXT_2);
			if (on) {
				VeloDraw.line(g, x + lw - 20, ry + 10, x + lw - 17, ry + 13, 1.4f, (a << 24) | (MenuKit.accent() & 0xFFFFFF));
				VeloDraw.line(g, x + lw - 17, ry + 13, x + lw - 12, ry + 7, 1.4f, (a << 24) | (MenuKit.accent() & 0xFFFFFF));
			} else if (openAnim > 0.9f) {
				HITS.add(new Hit(x + 4, ry, lw - 8, rowH, () -> switchTo(acc)));
			}
			ry += rowH;
		}
		ry += 2;
		MenuKit.text(g, font, MenuKit.fit(font, MenuKit.ui("Add accounts in the Velo launcher"), lw - 20), x + 10, ry + 2, MenuKit.TEXT_3);
		g.disableScissor();
	}

	private static boolean click(double mx, double my) {
		for (int i = HITS.size() - 1; i >= 0; i--) {
			Hit h = HITS.get(i);
			if (mx >= h.x() && mx < h.x() + h.w() && my >= h.y() && my < h.y() + h.h()) {
				clickSound();
				h.action().run();
				return true;
			}
		}
		if (open) {
			open = false;
			return true;
		}
		return false;
	}

	private static void clickSound() {
		Minecraft.getInstance().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
				net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0f));
	}

	private static boolean rewindAvailable() {
		try {
			return (boolean) Class.forName("net.veloclient.velo.devbuild.rewind.RewindModule").getMethod("available").invoke(null);
		} catch (ReflectiveOperationException | LinkageError e) {
			return false;
		}
	}

	private static void openRewind() {
		Minecraft mc = Minecraft.getInstance();
		mc.setScreen(new net.veloclient.velo.devbuild.rewind.RewindLibraryScreen(mc.gui.screen()));
	}

	// ---- switching ----

	private static void switchTo(Account acc) {
		if (switching) {
			return;
		}
		switching = true;
		status = "Switching to " + acc.name() + "...";
		open = false;
		Thread.ofVirtual().name("velo-account-switch").start(() -> {
			try {
				JsonObject o = acc.raw();
				String token = o.has("minecraftAccessToken") ? o.get("minecraftAccessToken").getAsString() : null;
				long expires = o.has("accessTokenExpiresAtEpochMillis") ? o.get("accessTokenExpiresAtEpochMillis").getAsLong() : 0;
				if (token == null || System.currentTimeMillis() > expires - 60_000) {
					token = refresh(acc);
				}
				String finalToken = token;
				Minecraft.getInstance().execute(() -> {
					try {
						apply(acc, finalToken);
						status = null;
					} catch (Exception e) {
						LOG.warn("Account switch failed", e);
						status = "Couldn't switch: " + e.getMessage();
					}
					switching = false;
				});
			} catch (Exception e) {
				LOG.warn("Account switch failed", e);
				status = "Couldn't switch: " + e.getMessage() + " - sign in again in the launcher";
				switching = false;
			}
		});
	}

	// The game now plays as that account: its User, the services built on its token, chat signing keys, profile.
	private static void apply(Account acc, String token) throws ReflectiveOperationException {
		Minecraft mc = Minecraft.getInstance();
		User user = new User(acc.name(), UUID.fromString(acc.uuid()), token, Optional.empty(), Optional.empty());
		// authlib 9 (26.2): YggdrasilAuthenticationService; authlib 10 (26.3+): MinecraftServicesDiscoveryService
		Object auth;
		try {
			auth = Class.forName("com.mojang.authlib.services.MinecraftServicesDiscoveryService").getMethod("create", java.net.Proxy.class)
					.invoke(null, mc.getProxy());
		} catch (ClassNotFoundException old) {
			auth = Class.forName("com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService").getConstructor(java.net.Proxy.class)
					.newInstance(mc.getProxy());
		}
		var api = (com.mojang.authlib.minecraft.UserApiService) auth.getClass().getMethod("createUserApiService", String.class).invoke(auth, token);
		set(mc, "user", user);
		set(mc, "userApiService", api);
		set(mc, "profileKeyPairManager", net.minecraft.client.multiplayer.ProfileKeyPairManager.create(api, user, mc.gameDirectory.toPath()));
		set(mc, "reportingContext", net.minecraft.client.multiplayer.chat.report.ReportingContext.create(
				net.minecraft.client.multiplayer.chat.report.ReportEnvironment.local(), api));
		set(mc, "userPropertiesFuture", CompletableFuture.supplyAsync(() -> {
			try {
				return api.fetchProperties();
			} catch (Exception e) {
				return com.mojang.authlib.minecraft.UserApiService.OFFLINE_PROPERTIES;
			}
		}));
		var sessions = mc.services().sessionService();
		set(mc, "profileFuture", CompletableFuture.supplyAsync(() -> {
			try {
				return sessions.getClass().getMethod("fetchProfile", UUID.class, boolean.class).invoke(sessions, user.getProfileId(), true);
			} catch (ReflectiveOperationException e) {
				return null;
			}
		}));
		net.veloclient.velo.config.VeloPaths.setAccount(acc.uuid());
		SKINS.remove(acc.uuid());
		markActive(acc.uuid());
		LOG.info("Now playing as {}", acc.name());
		clickSound();
	}

	private static void set(Object target, String field, Object value) throws ReflectiveOperationException {
		var f = Minecraft.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	// ---- Microsoft -> Xbox -> Minecraft (the launcher's sign-in chain, with its refresh token) ----

	private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

	private static String refresh(Account acc) throws Exception {
		String refresh = acc.raw().has("microsoftRefreshToken") ? acc.raw().get("microsoftRefreshToken").getAsString() : null;
		if (refresh == null || refresh.isBlank()) {
			throw new IllegalStateException("no saved sign-in");
		}
		JsonObject ms = postForm("https://login.microsoftonline.com/consumers/oauth2/v2.0/token", "grant_type=refresh_token&client_id=" + enc(CLIENT_ID)
				+ "&refresh_token=" + enc(refresh) + "&scope=" + enc(SCOPE));
		if (!ms.has("access_token")) {
			throw new IllegalStateException("Microsoft didn't accept the saved sign-in");
		}
		String newRefresh = ms.has("refresh_token") ? ms.get("refresh_token").getAsString() : refresh;
		JsonObject xblProps = new JsonObject();
		xblProps.addProperty("AuthMethod", "RPS");
		xblProps.addProperty("SiteName", "user.auth.xboxlive.com");
		xblProps.addProperty("RpsTicket", "d=" + ms.get("access_token").getAsString());
		JsonObject xblReq = new JsonObject();
		xblReq.add("Properties", xblProps);
		xblReq.addProperty("RelyingParty", "http://auth.xboxlive.com");
		xblReq.addProperty("TokenType", "JWT");
		JsonObject xbl = postJson("https://user.auth.xboxlive.com/user/authenticate", xblReq);
		JsonObject xstsProps = new JsonObject();
		xstsProps.addProperty("SandboxId", "RETAIL");
		JsonArray tokens = new JsonArray();
		tokens.add(xbl.get("Token").getAsString());
		xstsProps.add("UserTokens", tokens);
		JsonObject xstsReq = new JsonObject();
		xstsReq.add("Properties", xstsProps);
		xstsReq.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
		xstsReq.addProperty("TokenType", "JWT");
		JsonObject xsts = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", xstsReq);
		String uhs = xsts.getAsJsonObject("DisplayClaims").getAsJsonArray("xui").get(0).getAsJsonObject().get("uhs").getAsString();
		JsonObject login = new JsonObject();
		login.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xsts.get("Token").getAsString());
		JsonObject mcLogin = postJson("https://api.minecraftservices.com/authentication/login_with_xbox", login);
		String token = mcLogin.get("access_token").getAsString();
		long expiresAt = System.currentTimeMillis() + mcLogin.get("expires_in").getAsLong() * 1000L;
		// written back, so the launcher has the fresh tokens too (Microsoft hands out a new refresh token each time)
		acc.raw().addProperty("minecraftAccessToken", token);
		acc.raw().addProperty("accessTokenExpiresAtEpochMillis", expiresAt);
		acc.raw().addProperty("microsoftRefreshToken", newRefresh);
		saveAccount(acc);
		return token;
	}

	private static synchronized void saveAccount(Account acc) {
		update(root -> {
			JsonArray list = root.getAsJsonArray("accounts");
			for (int i = 0; i < list.size(); i++) {
				JsonObject o = list.get(i).getAsJsonObject();
				if (o.has("uuid") && dashed(o.get("uuid").getAsString()).equals(acc.uuid())) {
					for (String k : List.of("minecraftAccessToken", "accessTokenExpiresAtEpochMillis", "microsoftRefreshToken")) {
						o.add(k, acc.raw().get(k));
					}
				}
			}
		});
	}

	private static void markActive(String uuid) {
		update(root -> {
			for (var e : root.getAsJsonArray("accounts")) {
				JsonObject o = e.getAsJsonObject();
				if (o.has("uuid") && dashed(o.get("uuid").getAsString()).equals(uuid)) {
					root.addProperty("activeUuid", o.get("uuid").getAsString());
				}
			}
		});
	}

	private static synchronized void update(java.util.function.Consumer<JsonObject> change) {
		try {
			var parsed = JsonParser.parseString(Files.readString(file(), StandardCharsets.UTF_8));
			if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("accounts")) {
				return;
			}
			JsonObject root = parsed.getAsJsonObject();
			change.accept(root);
			Path tmp = file().resolveSibling("account.json.tmp");
			Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
			Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			readAt = 0;
		} catch (Exception e) {
			LOG.warn("Couldn't update the launcher's accounts", e);
		}
	}

	private static JsonObject postForm(String url, String form) throws Exception {
		var r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
		return JsonParser.parseString(r.body()).getAsJsonObject();
	}

	private static JsonObject postJson(String url, JsonObject body) throws Exception {
		var r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
				.header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
		if (r.statusCode() / 100 != 2) {
			throw new IllegalStateException("sign-in step failed (" + r.statusCode() + ")");
		}
		return JsonParser.parseString(r.body()).getAsJsonObject();
	}

	private static String enc(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}
}
*///?}
