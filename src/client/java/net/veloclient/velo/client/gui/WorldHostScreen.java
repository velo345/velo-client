package net.veloclient.velo.client.gui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.veloclient.velo.client.gui.title.MenuKit;
import net.veloclient.velo.client.gui.widget.VeloAnim;
import net.veloclient.velo.client.gui.widget.VeloDraw;
import net.veloclient.velo.client.gui.widget.VeloUi;
import net.veloclient.velo.client.gui.window.VeloWindow;
import net.veloclient.velo.client.hosting.WorldHosting;
import net.veloclient.velo.client.network.SocialClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * World Hosting: <b>Host</b> (Velo+) - open your singleplayer world to friends, share a join code, invite friends or
 * your whole party, let in people who knocked, manage who's in (op, game mode, teleport, kick, remove, ban) and the
 * world's rules, and whether it's public (listed in Discover). <b>Discover</b> (free) - partner servers, friends'
 * worlds, public worlds (join straight away or ask to join, report one that breaks the rules), or knock with a code.
 *
 * <p>Everything eases: cards rise in one after another when a tab opens, buttons and cards light up on hover, the
 * list scrolls smoothly, and a live dot breathes on what's online.
 */
public final class WorldHostScreen extends VeloWindow {

	private record Hit(int x, int y, int w, int h, Runnable action) {
		boolean contains(double mx, double my) {
			return mx >= x && mx < x + w && my >= y && my < y + h;
		}
	}

	private static final String[][] REPORT_REASONS = {{"inappropriate", "Inappropriate"}, {"harassment", "Harassment"}, {"cheating", "Cheating"},
			{"scam", "Scam"}, {"spam", "Spam"}, {"other", "Other"}};
	private static final Map<String, Identifier> ICONS = new ConcurrentHashMap<>();

	private final List<Hit> hits = new ArrayList<>();
	private boolean joinTab;
	private boolean friendsCanJoin = true;
	private String status = "";
	private int statusColor = MenuKit.TEXT_2;
	private boolean working;
	private List<WorldHosting.Joinable> joinable = List.of();
	private List<WorldHosting.Listed> discover = List.of();
	private List<WorldHosting.Partner> partners = List.of();
	/** Who may enter when you open the world: "friends", "public", "request". */
	private String visibility = "friends";
	private TextFieldWidget codeField;
	private double scroll;
	private double scrollTarget;
	private int contentTop;
	private int contentEnd;
	/** The public world whose report choices are open, and what was picked. */
	private String reportingWorld;
	private final java.util.Set<String> reportedWorlds = new java.util.HashSet<>();
	private long tabOpenedAt = System.nanoTime();
	private long lastFrame;
	private int cardIndex;
	private final Map<String, Float> hover = new HashMap<>();
	/** World rules as set from here this session (the world keeps them). */
	private final Map<String, String> rules = new HashMap<>(Map.of("keepInventory", "false", "pvp", "true", "sleep", "false",
			"mobGriefing", "true", "daylight", "true", "difficulty", "normal"));

	public WorldHostScreen(Screen parent) {
		super(Text.literal("Worlds"), 780, 560);
		returnTo(parent);
		// In your own world you probably came to host it; anywhere else, to find somewhere to play.
		joinTab = !WorldHosting.canHost();
		if (WorldHosting.hosting()) {
			WorldHosting.refresh(this::done);
		}
		refreshLists();
	}

	/** Opens on the Discover tab - from the title screen. */
	public static WorldHostScreen discover(Screen parent) {
		WorldHostScreen screen = new WorldHostScreen(parent);
		screen.joinTab = true;
		return screen;
	}

	private void refreshLists() {
		WorldHosting.joinable(list -> joinable = list);
		WorldHosting.discover(list -> discover = list);
		WorldHosting.partners(list -> partners = list);
	}

	@Override
	protected String[] headerTabs() {
		return new String[] {"Discover", "Host a world"};
	}

	@Override
	protected int headerTab() {
		return joinTab ? 0 : 1;
	}

	@Override
	protected void selectHeaderTab(int index) {
		joinTab = index == 0;
		scroll = 0;
		scrollTarget = 0;
		tabOpenedAt = System.nanoTime();
		if (joinTab) {
			refreshLists();
		}
		layoutContent();
	}

	@Override
	protected void layoutContent() {
		String keep = codeField == null ? "" : codeField.getText();
		this.clearChildren();
		codeField = null;
		if (joinTab) {
			int w = contentWidth();
			codeField = new TextFieldWidget(this.textRenderer, contentX() + w - 236, contentY() + 30, 140, 18, Text.literal("Code"));
			codeField.setPlaceholder(VeloUi.placeholder("VELO-ABC123"));
			codeField.setMaxLength(12);
			codeField.setText(keep);
			addDrawableChild(codeField);
		}
	}

	private void done(String error) {
		working = false;
		status = error == null ? "" : error;
		statusColor = error == null ? MenuKit.TEXT_2 : 0xFFFF7A7A;
	}

	private void run(String busy, java.util.function.Consumer<java.util.function.Consumer<String>> action) {
		if (working) {
			return;
		}
		working = true;
		status = busy;
		statusColor = MenuKit.TEXT_2;
		action.accept(this::done);
	}

	private void say(String text, boolean good) {
		status = text;
		statusColor = good ? MenuKit.GREEN : MenuKit.TEXT_2;
	}

	@Override
	protected void renderContentLayer(DrawContext context, int mouseX, int mouseY, float delta) {
		hits.clear();
		long now = System.nanoTime();
		float dt = lastFrame == 0 ? 0 : Math.min(0.1f, (now - lastFrame) / 1e9f);
		lastFrame = now;
		// Smooth scrolling toward where the wheel asked.
		scroll += (scrollTarget - scroll) * Math.min(1, dt * 14);
		cardIndex = 0;
		int x = contentX();
		int y = contentY();
		int w = contentWidth();
		if (joinTab) {
			renderJoin(context, x, y, w, mouseX, mouseY, dt);
		} else {
			renderHost(context, x, y, w, mouseX, mouseY, dt);
		}
		if (!status.isEmpty()) {
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(status), w), x, contentBottom() + 4, statusColor);
		}
	}

	// ---- Discover ----

	private void renderJoin(DrawContext context, int x, int y, int w, int mouseX, int mouseY, float dt) {
		// Hero: what this tab is, and the code box.
		heroCard(context, x, y, w, 58, MenuKit.accent());
		MenuKit.icon(context, "globe", x + 16, y + 17, 24, MenuKit.accentText());
		MenuKit.text(context, this.textRenderer, MenuKit.display("Find somewhere to play"), x + 50, y + 14, MenuKit.TEXT);
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui("Partner servers, your friends' worlds and public worlds - no IP needed."),
				w - 320), x + 50, y + 34, MenuKit.TEXT_2);
		MenuKit.text(context, this.textRenderer, MenuKit.small("HAVE A CODE?"), x + w - 236, y + 18, MenuKit.TEXT_3);
		pill(context, x + w - 90, y + 29, 80, 20, "Knock", true, mouseX, mouseY, dt, () -> {
			String code = codeField == null ? "" : codeField.getText().trim();
			if (code.isEmpty()) {
				say("Type the code your friend gave you", false);
				return;
			}
			run("Knocking...", d -> WorldHosting.knock(code, error -> {
				d.accept(error);
				if (error == null) {
					say("Asked the host - you get a Join button as soon as they let you in", true);
				}
			}));
		});

		contentTop = y + 66;
		contentEnd = contentBottom();
		context.enableScissor(x - 4, contentTop, x + w + 6, contentEnd);
		clipTop = contentTop;
		clipBottom = contentEnd;
		int ry = contentTop + 4 - (int) Math.round(scroll);
		int start = ry;

		if (!partners.isEmpty()) {
			ry = section(context, "PARTNER SERVERS", x, ry, () -> refreshLists());
			int columns = w >= 560 ? 3 : 2;
			int gap = 8;
			int tileW = (w - gap * (columns - 1)) / columns;
			int tileH = 62;
			for (int i = 0; i < partners.size(); i++) {
				WorldHosting.Partner partner = partners.get(i);
				int tx = x + (i % columns) * (tileW + gap);
				int ty = ry + (i / columns) * (tileH + gap);
				partnerTile(context, partner, tx, ty, tileW, tileH, mouseX, mouseY, dt);
			}
			ry += ((partners.size() + columns - 1) / columns) * (tileH + gap) + 6;
		}

		ry = section(context, "FRIENDS' WORLDS", x, ry, null);
		if (joinable.isEmpty()) {
			ry = emptyLine(context, x, ry, w, "None right now - when a friend invites you, a Join button pops up wherever you are.");
		}
		for (WorldHosting.Joinable world : joinable) {
			ry = worldCard(context, x, ry, w, mouseX, mouseY, dt, "f:" + world.id(), world.hostUuid(), world.hostName(), world.name(),
					"Hosted by " + world.hostName(), world.version(), world.guests(), -1, "Join", true,
					() -> run("Joining " + world.hostName() + "'s world...", d -> WorldHosting.join(world.id(), d)), null);
		}
		ry += 6;
		ry = section(context, "PUBLIC WORLDS · " + discover.size(), x, ry, null);
		if (discover.isEmpty()) {
			ry = emptyLine(context, x, ry, w, "Nobody is hosting a public world right now. Velo+ members can open theirs to everyone.");
		}
		for (WorldHosting.Listed world : discover) {
			boolean full = world.guests() >= world.maxPlayers();
			Runnable join = full ? () -> say("That world is full", false) : world.ask()
					? () -> run("Asking " + world.hostName() + "...", d -> WorldHosting.ask(world.id(), error -> {
						d.accept(error);
						if (error == null) {
							say("Asked - you get a Join button when " + world.hostName() + " lets you in", true);
						}
					}))
					: () -> run("Joining " + world.name() + "...", d -> WorldHosting.join(world.id(), d));
			ry = worldCard(context, x, ry, w, mouseX, mouseY, dt, "p:" + world.id(), world.hostUuid(), world.hostName(), world.name(),
					world.description().isEmpty() ? "Hosted by " + world.hostName() : world.description(), world.version(), world.guests(),
					world.maxPlayers(), full ? "Full" : world.ask() ? "Ask to join" : "Join", !full, join, world.id());
		}
		context.disableScissor();
		clipBottom = 0;
		clampScroll(ry - start);
	}

	private void partnerTile(DrawContext context, WorldHosting.Partner partner, int x, int y, int w, int h, int mouseX, int mouseY, float dt) {
		int index = cardIndex++;
		float rise = entrance(index);
		y += Math.round((1 - rise) * 12);
		String key = "partner:" + partner.address();
		boolean hot = visible(mouseX, mouseY, x, y, w, h);
		float hv = ease(key, hot, dt);
		int accent = partner.accent() != 0 ? partner.accent() : MenuKit.accent();
		VeloDraw.shadow(context, x, y, w, h, 10, 8 + Math.round(hv * 8), 3, VeloAnim.lerpArgb(0x30000000, (accent & 0xFFFFFF) | 0x50000000, hv));
		VeloDraw.fillRounded(context, x, y, w, h, 10, VeloAnim.lerpArgb(0xFF131318, 0xFF19191F, hv));
		VeloDraw.strokeRounded(context, x, y, w, h, 10, VeloAnim.lerpArgb(0x17FFFFFF, accent, hv));
		// A soft band of the partner's colour along the top edge.
		VeloDraw.fillRoundedGradient(context, x + 1, y + 1, w - 2, 18, 9, (accent & 0xFFFFFF) | 0x26000000, accent & 0xFFFFFF);
		int icon = 34;
		Identifier texture = partner.iconUrl() == null ? null : ICONS.get(partner.iconUrl());
		if (texture == null && partner.iconUrl() != null) {
			String url = partner.iconUrl();
			RemoteTextureLoader.load(url, id -> ICONS.put(url, id));
		}
		if (texture != null) {
			MenuKit.texture(context, texture, x + 10, y + (h - icon) / 2, icon, 64);
		} else {
			VeloDraw.fillRounded(context, x + 10, y + (h - icon) / 2, icon, icon, 8, (accent & 0xFFFFFF) | 0x33000000);
			MenuKit.icon(context, "globe", x + 10 + icon / 4, y + (h - icon) / 2 + icon / 4, icon / 2, accent);
		}
		int tx = x + 10 + icon + 10;
		int tw = w - (tx - x) - 10;
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.uiBold(partner.name()), tw), tx, y + 12, MenuKit.TEXT);
		String line = partner.description().isEmpty() ? partner.address() : partner.description();
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(line), tw), tx, y + 25, MenuKit.TEXT_2);
		if (partner.online()) {
			livePulse(context, tx + 3, y + 43);
			String players = String.format(Locale.ROOT, "%,d online", partner.players()) + (partner.versions().isEmpty() ? "" : "  ·  " + partner.versions());
			MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.small(players.toUpperCase(Locale.ROOT)), tw - 60), tx + 10, y + 41,
					MenuKit.GREEN);
		} else {
			MenuKit.text(context, this.textRenderer, MenuKit.small("OFFLINE RIGHT NOW"), tx, y + 41, MenuKit.TEXT_3);
		}
		int bw = 48;
		pill(context, x + w - bw - 8, y + h - 26, bw, 18, "Join", true, mouseX, mouseY, dt,
				() -> net.veloclient.velo.client.util.ClientCompat.joinServer(partner.address(), partner.name()));
		hit(x, y, w, h, () -> net.veloclient.velo.client.util.ClientCompat.joinServer(partner.address(), partner.name()));
	}

	/** A world row: host head, name, line, version + players chips, the main button, and (public worlds) Report. */
	private int worldCard(DrawContext context, int x, int y, int w, int mouseX, int mouseY, float dt, String key, String hostUuid, String hostName,
			String name, String line, String version, int guests, int max, String action, boolean primary, Runnable onAction, String reportId) {
		int index = cardIndex++;
		float rise = entrance(index);
		boolean reporting = reportId != null && reportId.equals(reportingWorld);
		int h = reporting ? 78 : 50;
		int cy = y + Math.round((1 - rise) * 12);
		boolean hot = visible(mouseX, mouseY, x, cy, w, h);
		float hv = ease(key, hot, dt);
		VeloDraw.fillRounded(context, x, cy, w, h, 10, VeloAnim.lerpArgb(0xFF131318, 0xFF18181E, hv));
		VeloDraw.strokeRounded(context, x, cy, w, h, 10, VeloAnim.lerpArgb(0x17FFFFFF, (MenuKit.accent() & 0xFFFFFF) | 0x80000000, hv));
		PlayerHeads.draw(context, hostUuid, hostName, x + 10, cy + 10, 30);
		VeloDraw.strokeRounded(context, x + 9, cy + 9, 32, 32, 5, 0x30FFFFFF);
		int tx = x + 50;
		int right = x + w - 14 - (reportId != null ? 24 : 0);
		int bw = Math.max(64, this.textRenderer.getWidth(action) + 24);
		int textW = right - bw - 12 - tx;
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.uiBold(name), textW), tx, cy + 10, MenuKit.TEXT);
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(line), textW), tx, cy + 23, MenuKit.TEXT_2);
		int chipX = tx;
		if (version != null && !version.isEmpty()) {
			chipX += chip(context, chipX, cy + 35, version, 0x14FFFFFF, MenuKit.TEXT_2) + 4;
		}
		String players = max > 0 ? guests + "/" + max + " playing" : guests + " playing";
		livePulse(context, chipX + 5, cy + 39);
		chip(context, chipX, cy + 35, "   " + players, 0x1A3DDC97, MenuKit.GREEN);
		pill(context, right - bw, cy + 15, bw, 20, action, primary, mouseX, mouseY, dt, onAction);
		if (reportId != null) {
			boolean done = reportedWorlds.contains(reportId);
			int fx = x + w - 30;
			boolean overFlag = visible(mouseX, mouseY, fx, cy + 15, 20, 20);
			float fv = ease("flag:" + reportId, overFlag || reporting, dt);
			VeloDraw.fillRounded(context, fx, cy + 15, 20, 20, 6, VeloAnim.lerpArgb(0x00FFFFFF, 0x33E0262E, fv));
			MenuKit.icon(context, "flag", fx + 4, cy + 19, 12, done ? MenuKit.TEXT_3 : VeloAnim.lerpArgb(0xFF8A8A95, 0xFFFF6B6B, fv));
			hit(fx, cy + 15, 20, 20, () -> {
				if (done) {
					say("You already reported that world - staff will look at it", false);
				} else {
					reportingWorld = reporting ? null : reportId;
				}
			});
			if (reporting) {
				MenuKit.text(context, this.textRenderer, MenuKit.small("REPORT IT FOR"), tx, cy + 56, 0xFFFF9A9A);
				int px = tx + 70;
				for (String[] reason : REPORT_REASONS) {
					int rw = this.textRenderer.getWidth(reason[1]) + 16;
					if (px + rw > x + w - 10) {
						break;
					}
					pill(context, px, cy + 52, rw, 16, reason[1], false, mouseX, mouseY, dt, () -> {
						reportingWorld = null;
						say("Sending report...", false);
						SocialClient.reportWorld(reportId, reason[0], "").thenAccept(reply -> MinecraftClient.getInstance().execute(() -> {
							if (reply.ok()) {
								reportedWorlds.add(reportId);
								say("Thanks - Velo staff will take a look at that world.", true);
							} else {
								done("Couldn't send: " + reply.error());
							}
						}));
					});
					px += rw + 4;
				}
			}
		}
		return y + h + 8;
	}

	// ---- Host ----

	private void renderHost(DrawContext context, int x, int y, int w, int mouseX, int mouseY, float dt) {
		boolean plus = net.veloclient.velo.client.namestyle.NameStyles.plus();
		boolean hosting = WorldHosting.hosting();
		WorldHosting.Hosted hosted = WorldHosting.hosted();
		int accent = hosting ? MenuKit.GREEN : MenuKit.accent();
		heroCard(context, x, y, w, 64, accent);
		if (hosting) {
			livePulse(context, x + 28, y + 32);
			float ring = (System.nanoTime() / 1e9f) % 1.6f / 1.6f;
			VeloDraw.strokeRounded(context, x + 28 - 6 - ring * 6, y + 32 - 6 - ring * 6, 12 + ring * 12, 12 + ring * 12, 6 + ring * 6, 1.2f,
					(Math.round((1 - ring) * 140) << 24) | (MenuKit.GREEN & 0xFFFFFF));
		} else {
			MenuKit.icon(context, "globe", x + 16, y + 20, 24, MenuKit.accentText());
		}
		String title = !WorldHosting.canHost() ? "Open a singleplayer world to host it" : hosting ? "Your world is open" : "Play this world with friends";
		MenuKit.text(context, this.textRenderer, MenuKit.display(title), x + 50, y + 16, MenuKit.TEXT);
		String sub = !plus ? "Hosting is part of Velo+ - your friends join for free, no port forwarding."
				: hosting ? (WorldHosting.status().isEmpty() ? (hosted == null ? "" : hosted.guests() + " connected through Velo · backed up before opening")
						: WorldHosting.status())
				: "No port forwarding, no IP to share. Your world is backed up first.";
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(sub), w - 190), x + 50, y + 37, MenuKit.TEXT_2);
		if (!plus) {
			pill(context, x + w - 110, y + 22, 96, 22, "Get Velo+", true, mouseX, mouseY, dt, () -> {
				net.veloclient.velo.client.gui.store.StoreScreen.showPlus();
				this.client.setScreen(new net.veloclient.velo.client.gui.store.StoreScreen(this));
			});
			return;
		}
		if (!WorldHosting.canHost()) {
			return;
		}
		pill(context, x + w - 124, y + 22, 110, 22, working ? "Working..." : hosting ? "Stop hosting" : "Open world", !hosting, mouseX, mouseY, dt,
				() -> run(hosting ? "Closing..." : "Opening your world...", d -> {
					if (hosting) {
						WorldHosting.stop(d);
					} else {
						WorldHosting.start(friendsCanJoin, visibility, d);
					}
				}));
		y += 74;
		if (!hosting) {
			y = toggleRow(context, x, y, w, "All my Velo friends may join", "Off: only the people you invite", friendsCanJoin, mouseX, mouseY, dt,
					() -> friendsCanJoin = !friendsCanJoin);
			segmented(context, x, y, w, "WHO CAN FIND IT", new String[][] {{"friends", "Friends only"}, {"request", "Public - they ask"},
					{"public", "Public - anyone joins"}}, visibility, mouseX, mouseY, dt, v -> visibility = v);
			return;
		}

		contentTop = y;
		contentEnd = contentBottom();
		context.enableScissor(x - 4, contentTop, x + w + 6, contentEnd);
		clipTop = contentTop;
		clipBottom = contentEnd;
		int sy = y - (int) Math.round(scroll);
		int start = sy;
		boolean canJoin = hosted != null && hosted.friendsCanJoin();
		sy = toggleRow(context, x, sy, w, "All my Velo friends may join", "Off: only people you invite or let in", canJoin, mouseX, mouseY, dt,
				() -> run("Saving...", d -> WorldHosting.setFriendsCanJoin(!canJoin, d)));
		sy = segmented(context, x, sy, w, "WHO CAN FIND IT", new String[][] {{"friends", "Friends only"}, {"request", "Public - they ask"},
				{"public", "Public - anyone joins"}}, hosted == null ? "friends" : hosted.visibility(), mouseX, mouseY, dt,
				v -> run("Saving...", d -> WorldHosting.setVisibility(v, d)));
		int max = hosted == null ? 10 : hosted.maxPlayers();
		sy = segmented(context, x, sy, w, "MAX GUESTS", new String[][] {{"2", "2"}, {"4", "4"}, {"6", "6"}, {"8", "8"}, {"10", "10"}}, String.valueOf(max),
				mouseX, mouseY, dt, v -> run("Saving...", d -> WorldHosting.setMaxPlayers(Integer.parseInt(v), d)));

		// Share code.
		sy = section(context, "JOIN CODE - SHARE IT ANYWHERE, THEY KNOCK, YOU LET THEM IN", x, sy, null);
		String code = hosted == null ? null : hosted.code();
		heroCard(context, x, sy, w, 40, MenuKit.accent());
		if (code != null) {
			MenuKit.text(context, this.textRenderer, MenuKit.displayLg("VELO-" + code), x + 14, sy + 12, MenuKit.TEXT);
			pill(context, x + w - 214, sy + 10, 66, 20, "Copy", true, mouseX, mouseY, dt, () -> {
				MinecraftClient.getInstance().keyboard.setClipboard("VELO-" + code);
				say("Code copied", true);
			});
			pill(context, x + w - 142, sy + 10, 62, 20, "New", false, mouseX, mouseY, dt, () -> run("New code...", d -> WorldHosting.code(true, d)));
			pill(context, x + w - 74, sy + 10, 62, 20, "Off", false, mouseX, mouseY, dt, () -> run("Turning codes off...", d -> WorldHosting.code(false, d)));
		} else {
			MenuKit.text(context, this.textRenderer, MenuKit.ui("Codes are off - nobody can knock."), x + 14, sy + 16, MenuKit.TEXT_2);
			pill(context, x + w - 124, sy + 10, 110, 20, "Make a code", true, mouseX, mouseY, dt, () -> run("Making a code...", d -> WorldHosting.code(true, d)));
		}
		sy += 48;

		// Knocks.
		List<WorldHosting.Knock> knocks = WorldHosting.knocks();
		if (!knocks.isEmpty()) {
			sy = section(context, "WANT TO JOIN · " + knocks.size(), x, sy, null);
			for (WorldHosting.Knock knock : knocks) {
				int h = 34;
				int cy = sy + Math.round((1 - entrance(cardIndex++)) * 10);
				VeloDraw.fillRounded(context, x, cy, w, h, 9, 0x2AE0262E);
				VeloDraw.strokeRounded(context, x, cy, w, h, 9, (MenuKit.accent() & 0xFFFFFF) | 0x80000000);
				PlayerHeads.draw(context, null, knock.username(), x + 8, cy + 7, 20);
				MenuKit.text(context, this.textRenderer, MenuKit.uiBold(knock.username()), x + 36, cy + 13, MenuKit.TEXT);
				pill(context, x + w - 156, cy + 7, 72, 20, "Let in", true, mouseX, mouseY, dt, () -> run("Letting in...", d -> WorldHosting.accept(knock, d)));
				pill(context, x + w - 78, cy + 7, 70, 20, "Decline", false, mouseX, mouseY, dt, () -> WorldHosting.decline(knock));
				sy += h + 6;
			}
			sy += 4;
		}

		// Who's in the world now.
		String self = this.client.player == null ? "" : this.client.player.getGameProfile().name();
		List<String> players = WorldHosting.players().stream().filter(p -> !p.equals(self)).toList();
		sy = section(context, "IN YOUR WORLD · " + players.size(), x, sy, null);
		if (players.isEmpty()) {
			sy = emptyLine(context, x, sy, w, "Nobody yet - invite someone below or share the code.");
		}
		for (String player : players) {
			int h = 34;
			int cy = sy + Math.round((1 - entrance(cardIndex++)) * 10);
			boolean hot = visible(mouseX, mouseY, x, cy, w, h);
			float hv = ease("pl:" + player, hot, dt);
			VeloDraw.fillRounded(context, x, cy, w, h, 9, VeloAnim.lerpArgb(0xFF131318, 0xFF18181E, hv));
			VeloDraw.strokeRounded(context, x, cy, w, h, 9, 0x17FFFFFF);
			PlayerHeads.draw(context, null, player, x + 8, cy + 7, 20);
			MenuKit.text(context, this.textRenderer, MenuKit.uiBold(player), x + 36, cy + 13, MenuKit.TEXT);
			String[][] actions = {{"Op", "op"}, {"Deop", "deop"}, {"Survival", "survival"}, {"Creative", "creative"}, {"Spectator", "spectator"},
					{"TP here", "tp-here"}, {"Go to", "tp-to"}, {"Kick", "kick"}};
			int ax = x + w - 8;
			for (int i = actions.length - 1; i >= 0; i--) {
				String[] a = actions[i];
				int aw = this.textRenderer.getWidth(a[0]) + 14;
				ax -= aw + 4;
				if (ax < x + 40 + this.textRenderer.getWidth(player)) {
					break;
				}
				pill(context, ax, cy + 8, aw, 18, a[0], false, mouseX, mouseY, dt, () -> {
					WorldHosting.playerAction(player, a[1]);
					say(a[0] + " - " + player, true);
				});
			}
			sy += h + 6;
		}
		sy += 4;

		// Invite.
		sy = section(context, "INVITE", x, sy, null);
		pill(context, x, sy, 130, 22, "Invite my party", false, mouseX, mouseY, dt, () -> run("Inviting your party...", d -> WorldHosting.inviteParty(d)));
		sy += 30;
		var social = SocialClient.snapshot();
		List<String> allowed = hosted == null ? List.of() : hosted.allowed().stream().map(a -> a[0]).toList();
		if (social != null) {
			int fx = x;
			for (var friend : social.friends.stream().sorted((a, b) -> Boolean.compare(b.online, a.online)).limit(18).toList()) {
				boolean invited = allowed.contains(friend.uuid);
				String label = friend.username + (invited ? "  ✓" : "");
				int fw = this.textRenderer.getWidth(label) + 36;
				if (fx + fw > x + w) {
					fx = x;
					sy += 28;
				}
				String key = "inv:" + friend.uuid;
				boolean hot = visible(mouseX, mouseY, fx, sy, fw, 22);
				float hv = ease(key, hot, dt);
				VeloDraw.fillRounded(context, fx, sy, fw, 22, 11, invited ? (MenuKit.GREEN & 0xFFFFFF) | 0x22000000 : VeloAnim.lerpArgb(0x0AFFFFFF, 0x1EFFFFFF, hv));
				VeloDraw.strokeRounded(context, fx, sy, fw, 22, 11, invited ? (MenuKit.GREEN & 0xFFFFFF) | 0x66000000 : VeloAnim.lerpArgb(0x17FFFFFF, MenuKit.accent(), hv));
				PlayerHeads.draw(context, friend.uuid, friend.username, fx + 4, sy + 4, 14);
				if (friend.online) {
					VeloDraw.fillCircle(context, fx + 17, sy + 17, 2.5f, MenuKit.GREEN);
				}
				MenuKit.text(context, this.textRenderer, MenuKit.ui(label), fx + 24, sy + 7, invited ? MenuKit.GREEN : MenuKit.TEXT);
				hit(fx, sy, fw, 22, invited ? () -> say(friend.username + " is invited", true)
						: () -> run("Inviting " + friend.username + "...", d -> WorldHosting.invite(friend.uuid, d)));
				fx += fw + 6;
			}
			sy += 32;
		}

		// Allowed list.
		if (hosted != null && !hosted.allowed().isEmpty()) {
			sy = section(context, "ALLOWED", x, sy, null);
			for (String[] member : hosted.allowed()) {
				VeloDraw.fillRounded(context, x, sy, w, 28, 8, 0xFF131318);
				PlayerHeads.draw(context, member[0], member[1], x + 7, sy + 6, 16);
				MenuKit.text(context, this.textRenderer, MenuKit.ui(member[1]), x + 30, sy + 10, MenuKit.TEXT);
				pill(context, x + w - 140, sy + 4, 66, 20, "Remove", false, mouseX, mouseY, dt,
						() -> run("Removing...", d -> WorldHosting.remove(member[0], member[1], false, d)));
				pill(context, x + w - 70, sy + 4, 64, 20, "Ban", false, mouseX, mouseY, dt,
						() -> run("Banning...", d -> WorldHosting.remove(member[0], member[1], true, d)));
				sy += 32;
			}
			sy += 4;
		}

		// World rules: a grid of switches, then difficulty.
		sy = section(context, "WORLD RULES", x, sy, null);
		String[][] toggles = {{"keepInventory", "Keep inventory"}, {"pvp", "PvP"}, {"sleep", "One player sleeps for all"},
				{"mobGriefing", "Mob griefing"}, {"daylight", "Day/night cycle"}};
		int colW = (w - 8) / 2;
		for (int i = 0; i < toggles.length; i++) {
			String[] t = toggles[i];
			boolean on = Boolean.parseBoolean(rules.get(t[0]));
			int tx = x + (i % 2) * (colW + 8);
			int ty = sy + (i / 2) * 32;
			float knob = ease("rule:" + t[0], on, dt);
			VeloDraw.fillRounded(context, tx, ty, colW, 26, 8, 0xFF131318);
			MenuKit.text(context, this.textRenderer, MenuKit.ui(t[1]), tx + 10, ty + 9, MenuKit.TEXT);
			MenuKit.toggle(context, tx + colW - 26, ty + 8, knob);
			hit(tx, ty, colW, 26, () -> {
				rules.put(t[0], String.valueOf(!on));
				WorldHosting.rule(t[0], String.valueOf(!on));
			});
		}
		sy += ((toggles.length + 1) / 2) * 32 + 4;
		sy = segmented(context, x, sy, w, "DIFFICULTY", new String[][] {{"peaceful", "Peaceful"}, {"easy", "Easy"}, {"normal", "Normal"}, {"hard", "Hard"}},
				rules.get("difficulty"), mouseX, mouseY, dt, v -> {
					rules.put("difficulty", v);
					WorldHosting.rule("difficulty", v);
				});
		MenuKit.text(context, this.textRenderer, MenuKit.small("BACKUPS: .velo-client/world-backups (the newest 5 per world)"), x + 2, sy, MenuKit.TEXT_3);
		sy += 16;
		context.disableScissor();
		clipBottom = 0;
		clampScroll(sy - start);
	}

	// ---- Building blocks ----

	/** 0..1 for the {@code index}-th card since the tab opened: each rises in a little after the last. */
	private float entrance(int index) {
		float t = Math.max(0f, Math.min(1f, ((System.nanoTime() - tabOpenedAt) / 1e6f - index * 35f) / 320f));
		return 1f - (float) Math.pow(1f - t, 3);
	}

	private float ease(String key, boolean target, float dt) {
		float value = VeloAnim.step(hover.getOrDefault(key, target ? 1f : 0f), target ? 1f : 0f, dt * 10);
		hover.put(key, value);
		return value;
	}

	private void clampScroll(int height) {
		double max = Math.max(0, height - (contentEnd - contentTop) + 8);
		scrollTarget = Math.max(0, Math.min(max, scrollTarget));
		scroll = Math.max(0, Math.min(max, scroll));
	}

	private boolean visible(int mouseX, int mouseY, int x, int y, int w, int h) {
		return MenuKit.inside(mouseX, mouseY, x, y, w, h) && (clipBottom <= 0 || mouseY >= clipTop && mouseY < clipBottom);
	}

	private void heroCard(DrawContext context, int x, int y, int w, int h, int accent) {
		VeloDraw.shadow(context, x, y, w, h, 12, 14, 4, 0x45000000);
		VeloDraw.fillRoundedGradient(context, x, y, w, h, 12, (accent & 0xFFFFFF) | 0x2A000000, 0xF2121217);
		VeloDraw.strokeRounded(context, x, y, w, h, 12, (accent & 0xFFFFFF) | 0x55000000);
	}

	private void livePulse(DrawContext context, int cx, int cy) {
		float pulse = 0.5f + 0.5f * (float) Math.sin(System.nanoTime() / 3.5e8);
		VeloDraw.fillCircle(context, cx, cy, 2.5f + pulse * 2f, (Math.round(70 * (1 - pulse)) << 24) | (MenuKit.GREEN & 0xFFFFFF));
		VeloDraw.fillCircle(context, cx, cy, 2.5f, MenuKit.GREEN);
	}

	private int chip(DrawContext context, int x, int y, String text, int fill, int color) {
		Text label = MenuKit.small(text.toUpperCase(Locale.ROOT));
		int w = this.textRenderer.getWidth(label) + 10;
		VeloDraw.fillRounded(context, x, y, w, 10, 5, fill);
		MenuKit.text(context, this.textRenderer, label, x + 5, y + 2, color);
		return w;
	}

	private int emptyLine(DrawContext context, int x, int y, int w, String text) {
		VeloDraw.fillRounded(context, x, y, w, 30, 9, 0x0CFFFFFF);
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, MenuKit.ui(text), w - 24), x + 12, y + 11, MenuKit.TEXT_3);
		return y + 38;
	}

	/** A section heading with an accent tick; {@code refresh} adds a small refresh link at the right. */
	private int section(DrawContext context, String title, int x, int y, Runnable refresh) {
		VeloDraw.fillRounded(context, x, y + 4, 3, 8, 1.5f, MenuKit.accent());
		MenuKit.text(context, this.textRenderer, MenuKit.small(title), x + 8, y + 5, MenuKit.TEXT_3);
		if (refresh != null) {
			int rx = x + contentWidth() - 64;
			MenuKit.icon(context, "reset", rx, y + 3, 10, MenuKit.TEXT_3);
			MenuKit.text(context, this.textRenderer, MenuKit.small("REFRESH"), rx + 14, y + 5, MenuKit.TEXT_3);
			hit(rx - 2, y, 66, 16, refresh);
		}
		return y + 20;
	}

	private int segmented(DrawContext context, int x, int y, int w, String title, String[][] options, String current, int mouseX, int mouseY, float dt,
			java.util.function.Consumer<String> pick) {
		MenuKit.text(context, this.textRenderer, MenuKit.small(title), x + 2, y + 8, MenuKit.TEXT_3);
		int sx = x + 110;
		int total = 0;
		for (String[] o : options) {
			total += this.textRenderer.getWidth(o[1]) + 22;
		}
		VeloDraw.fillRounded(context, sx, y, total + 4, 24, 12, 0xFF101014);
		VeloDraw.strokeRounded(context, sx, y, total + 4, 24, 12, 0x17FFFFFF);
		int ox = sx + 2;
		// The selected option's background slides between options.
		float targetX = ox;
		float targetW = 0;
		for (String[] o : options) {
			int ow = this.textRenderer.getWidth(o[1]) + 22;
			if (o[0].equals(current)) {
				targetX = ox;
				targetW = ow;
			}
			ox += ow;
		}
		String key = "seg:" + title;
		float knobX = hover.getOrDefault(key + ":x", targetX);
		float knobW = hover.getOrDefault(key + ":w", targetW);
		knobX = VeloAnim.step(knobX, targetX, dt * 12);
		knobW = VeloAnim.step(knobW, targetW, dt * 12);
		hover.put(key + ":x", knobX);
		hover.put(key + ":w", knobW);
		if (knobW > 1) {
			VeloDraw.fillRounded(context, knobX, y + 2, knobW, 20, 10, MenuKit.accent());
		}
		ox = sx + 2;
		for (String[] o : options) {
			int ow = this.textRenderer.getWidth(o[1]) + 22;
			boolean selected = o[0].equals(current);
			boolean hot = visible(mouseX, mouseY, ox, y, ow, 24);
			MenuKit.text(context, this.textRenderer, MenuKit.uiBold(o[1]), ox + 11, y + 8, selected ? MenuKit.onAccent() : hot ? MenuKit.TEXT : MenuKit.TEXT_2);
			hit(ox, y, ow, 24, () -> pick.accept(o[0]));
			ox += ow;
		}
		return y + 32;
	}

	private int toggleRow(DrawContext context, int x, int y, int w, String title, String sub, boolean on, int mouseX, int mouseY, float dt, Runnable toggle) {
		boolean hot = visible(mouseX, mouseY, x, y, w, 36);
		float hv = ease("toggle:" + title, hot, dt);
		float knob = ease("knob:" + title, on, dt);
		VeloDraw.fillRounded(context, x, y, w, 36, 10, VeloAnim.lerpArgb(0xFF131318, 0xFF18181E, hv));
		VeloDraw.strokeRounded(context, x, y, w, 36, 10, 0x17FFFFFF);
		MenuKit.text(context, this.textRenderer, MenuKit.uiBold(title), x + 12, y + 8, MenuKit.TEXT);
		MenuKit.text(context, this.textRenderer, MenuKit.small(sub), x + 12, y + 21, MenuKit.TEXT_3);
		MenuKit.toggle(context, x + w - 30, y + 14, knob);
		hit(x, y, w, 36, toggle);
		return y + 44;
	}

	private void pill(DrawContext context, int x, int y, int w, int h, String label, boolean primary, int mouseX, int mouseY, float dt, Runnable action) {
		boolean hot = visible(mouseX, mouseY, x, y, w, h);
		float hv = ease("pill:" + label + ":" + x + ":" + (y - (int) Math.round(scroll)), hot, dt);
		int lift = Math.round(hv);
		if (primary) {
			VeloDraw.shadow(context, x, y - lift, w, h, h / 2, 6 + Math.round(hv * 4), 2, (MenuKit.accent() & 0xFFFFFF) | (Math.round(0x40 + hv * 0x40) << 24));
			VeloDraw.fillRounded(context, x, y - lift, w, h, h / 2f, VeloAnim.lerpArgb(MenuKit.accent(), 0xFFFFFFFF, hv * 0.12f));
		} else {
			VeloDraw.fillRounded(context, x, y - lift, w, h, h / 2f, VeloAnim.lerpArgb(0x0AFFFFFF, 0x1EFFFFFF, hv));
			VeloDraw.strokeRounded(context, x, y - lift, w, h, h / 2, VeloAnim.lerpArgb(0x17FFFFFF, 0x45FFFFFF, hv));
		}
		Text text = MenuKit.uiBold(label);
		MenuKit.text(context, this.textRenderer, MenuKit.fit(this.textRenderer, text, w - 6), x + Math.max(3, (w - this.textRenderer.getWidth(text)) / 2),
				y - lift + (h - 8) / 2, primary ? MenuKit.onAccent() : MenuKit.TEXT);
		hit(x, y, w, h, action);
	}

	/** A click target, cut to the visible part while drawing the scrolling list. */
	private void hit(int x, int y, int w, int h, Runnable action) {
		if (clipBottom > 0) {
			int top = Math.max(y, clipTop);
			int bottom = Math.min(y + h, clipBottom);
			if (bottom > top) {
				// Inner targets (buttons) are added after their card: put them first so they win the click.
				hits.add(0, new Hit(x, top, w, bottom - top, action));
			}
			return;
		}
		hits.add(0, new Hit(x, y, w, h, action));
	}

	/** While drawing the scrolling part: click targets only where it's visible. */
	private int clipTop;
	private int clipBottom;

	@Override
	public boolean mouseClicked(Click click, boolean doubled) {
		for (Hit hit : List.copyOf(hits)) {
			if (hit.contains(click.x(), click.y())) {
				hit.action().run();
				return true;
			}
		}
		return super.mouseClicked(click, doubled);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
		if ((joinTab || WorldHosting.hosting()) && mouseY >= contentTop) {
			scrollTarget -= verticalAmount * 36;
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
	}
}
