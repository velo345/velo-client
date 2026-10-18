package net.veloclient.velo.client.social;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.veloclient.velo.client.gui.FriendsScreen;
import net.veloclient.velo.client.gui.WaypointsScreen;
import net.veloclient.velo.client.gui.store.OwnerScreen;
import net.veloclient.velo.client.gui.store.StoreScreen;
import net.veloclient.velo.client.modules.qol.FlySpeedModule;
import net.veloclient.velo.client.network.SocialClient;
import net.veloclient.velo.client.network.StoreClient;
import net.veloclient.velo.client.util.ClientCompat;
import net.veloclient.velo.client.waypoints.Waypoint;
import net.veloclient.velo.client.waypoints.WaypointManager;
import net.veloclient.velo.client.worldmap.WorldMapScreen;
import net.veloclient.velo.module.ModuleRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * {@code /velo ...}: shortcuts for everything Velo - for everyone (your Velo+ time left, coins, the store, map,
 * friends, party, sharing a waypoint, phantom, fly speed) and, for owners, managing players (coins, Velo+, cosmetics)
 * without opening the panel. Plus bare {@code /phantom} and {@code /flyspeed <amount>} - but only when the server doesn't
 * have a command of that name itself: the server's own command always wins, so nothing a server offers is ever shadowed.
 */
public final class VeloCommands {

	private static final List<Runnable> NEXT_TICK = new ArrayList<>();
	/** Cosmetic ids for owner tab-completion (loaded once, on first use). */
	private static volatile List<String> itemIds = List.of();
	private static boolean itemIdsRequested;

	private VeloCommands() {
	}

	public static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			List<Runnable> run;
			synchronized (NEXT_TICK) {
				run = List.copyOf(NEXT_TICK);
				NEXT_TICK.clear();
			}
			run.forEach(Runnable::run);
			SocialClient.staffLevel(); // keeps the staff level fresh (for /velostaff)
			// Owners get cosmetic ids to tab-complete as soon as they're known to be owners.
			if (!itemIdsRequested && StoreClient.isOwner()) {
				loadItemIds();
			}
		});
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ClientCmd.forgetServerCommands());
		net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientCmd.forgetServerCommands());
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
			dispatcher.register(root());
			// The team's tools: /velostaff [player].
			dispatcher.register(ClientCmd.literal("velostaff").requires(source -> SocialClient.staffLevel() != null)
					.executes(context -> {
						openLater(() -> new net.veloclient.velo.client.gui.StaffScreen(null, null));
						return 1;
					})
					.then(ClientCmd.argument("player", StringArgumentType.word()).suggests(PLAYERS).executes(context -> {
						String target = context.getArgument("player", String.class);
						openLater(() -> new net.veloclient.velo.client.gui.StaffScreen(null, target));
						return 1;
					})));
			// Bare /phantom and /flyspeed (with tab completion) - only while the server has no command of that name.
			dispatcher.register(ClientCmd.literal("phantom").requires(source -> ClientCmd.serverLacks("phantom", source))
					.executes(context -> {
						phantom(null);
						return 1;
					})
					.then(ClientCmd.argument("state", StringArgumentType.word()).suggests(ON_OFF).executes(context -> {
						phantom(context.getArgument("state", String.class));
						return 1;
					})));
			dispatcher.register(ClientCmd.literal("flyspeed").requires(source -> ClientCmd.serverLacks("flyspeed", source))
					.executes(context -> {
						flySpeed(null);
						return 1;
					})
					.then(ClientCmd.argument("amount", StringArgumentType.word()).suggests(SPEEDS).executes(context -> {
						flySpeed(context.getArgument("amount", String.class));
						return 1;
					})));
		});
	}

	/** Screens open on the next tick: the chat screen closes itself right after a command and would close ours with it. */
	private static void openLater(Supplier<Screen> screen) {
		synchronized (NEXT_TICK) {
			NEXT_TICK.add(() -> MinecraftClient.getInstance().setScreen(screen.get()));
		}
	}

	// ---- The /velo tree ----

	private static com.mojang.brigadier.builder.LiteralArgumentBuilder<FabricClientCommandSource> root() {
		var player = ClientCmd.argument("player", StringArgumentType.word()).suggests(PLAYERS);
		return ClientCmd.literal("velo")
				.executes(context -> {
					help();
					return 1;
				})
				.then(ClientCmd.literal("help").executes(context -> {
					help();
					return 1;
				}))
				.then(ClientCmd.literal("plus")
						.executes(context -> {
							plusStatus();
							return 1;
						})
						.then(ClientCmd.literal("give").requires(s -> StoreClient.isOwner())
								.then(ClientCmd.argument("player", StringArgumentType.word()).suggests(PLAYERS)
										.then(ClientCmd.argument("days", IntegerArgumentType.integer(1, 3650)).executes(context -> {
											ownerPlus(context.getArgument("player", String.class), context.getArgument("days", Integer.class));
											return 1;
										}))))
						.then(ClientCmd.literal("end").requires(s -> StoreClient.isOwner())
								.then(ClientCmd.argument("player", StringArgumentType.word()).suggests(PLAYERS).executes(context -> {
									ownerPlus(context.getArgument("player", String.class), 0);
									return 1;
								}))))
				.then(ClientCmd.literal("coins")
						.executes(context -> {
							coinsStatus();
							return 1;
						})
						.then(ClientCmd.literal("give").requires(s -> StoreClient.isOwner())
								.then(ClientCmd.argument("player", StringArgumentType.word()).suggests(PLAYERS)
										.then(ClientCmd.argument("amount", IntegerArgumentType.integer(1, 10_000_000)).executes(context -> {
											ownerCoins(context.getArgument("player", String.class), context.getArgument("amount", Integer.class));
											return 1;
										}))))
						.then(ClientCmd.literal("take").requires(s -> StoreClient.isOwner())
								.then(ClientCmd.argument("player", StringArgumentType.word()).suggests(PLAYERS)
										.then(ClientCmd.argument("amount", IntegerArgumentType.integer(1, 10_000_000)).executes(context -> {
											ownerCoins(context.getArgument("player", String.class), -context.getArgument("amount", Integer.class));
											return 1;
										})))))
				.then(ClientCmd.literal("lookup").requires(s -> StoreClient.isOwner())
						.then(player.executes(context -> {
							lookup(context.getArgument("player", String.class));
							return 1;
						})))
				.then(ClientCmd.literal("cosmetic").requires(s -> StoreClient.isOwner())
						.then(ClientCmd.literal("give")
								.then(ClientCmd.argument("player", StringArgumentType.word()).suggests(PLAYERS)
										.then(ClientCmd.argument("item", StringArgumentType.word()).suggests(ITEMS).executes(context -> {
											ownerItem(context.getArgument("player", String.class), context.getArgument("item", String.class), true);
											return 1;
										}))))
						.then(ClientCmd.literal("take")
								.then(ClientCmd.argument("player", StringArgumentType.word()).suggests(PLAYERS)
										.then(ClientCmd.argument("item", StringArgumentType.word()).suggests(ITEMS).executes(context -> {
											ownerItem(context.getArgument("player", String.class), context.getArgument("item", String.class), false);
											return 1;
										})))))
				.then(ClientCmd.literal("owner").requires(s -> StoreClient.isOwner()).executes(context -> {
					openLater(() -> new OwnerScreen(null));
					return 1;
				}))
				.then(ClientCmd.literal("store").executes(context -> {
					openLater(() -> new StoreScreen(null));
					return 1;
				}))
				.then(ClientCmd.literal("map").executes(context -> {
					openLater(() -> new WorldMapScreen(null));
					return 1;
				}))
				.then(ClientCmd.literal("waypoints").executes(context -> {
					openLater(() -> new WaypointsScreen(null));
					return 1;
				}))
				.then(ClientCmd.literal("friends").executes(context -> {
					openLater(FriendsScreen::new);
					return 1;
				}))
				.then(ClientCmd.literal("party").executes(context -> {
					partyStatus();
					return 1;
				}))
				.then(ClientCmd.literal("waypoint")
						.then(ClientCmd.argument("name", StringArgumentType.greedyString()).suggests(WAYPOINTS).executes(context -> {
							shareWaypoint(context.getArgument("name", String.class));
							return 1;
						})))
				.then(ClientCmd.literal("phantom")
						.executes(context -> {
							phantom(null);
							return 1;
						})
						.then(ClientCmd.argument("state", StringArgumentType.word()).suggests(ON_OFF).executes(context -> {
							phantom(context.getArgument("state", String.class));
							return 1;
						})))
				.then(ClientCmd.literal("flyspeed")
						.executes(context -> {
							flySpeed(null);
							return 1;
						})
						.then(ClientCmd.argument("amount", StringArgumentType.word()).suggests(SPEEDS).executes(context -> {
							flySpeed(context.getArgument("amount", String.class));
							return 1;
						})));
	}

	private static final SuggestionProvider<FabricClientCommandSource> ON_OFF = (context, builder) -> {
		for (String option : List.of("on", "off")) {
			if (option.startsWith(builder.getRemaining().toLowerCase(Locale.ROOT))) {
				builder.suggest(option);
			}
		}
		return builder.buildFuture();
	};

	private static final SuggestionProvider<FabricClientCommandSource> SPEEDS = (context, builder) -> {
		for (String option : List.of("1", "2", "3", "5", "10", "off")) {
			if (option.startsWith(builder.getRemaining().toLowerCase(Locale.ROOT))) {
				builder.suggest(option);
			}
		}
		return builder.buildFuture();
	};

	/** Friends, party members and players around you. */
	private static final SuggestionProvider<FabricClientCommandSource> PLAYERS = (context, builder) -> {
		String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
		java.util.Set<String> names = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		var state = SocialClient.snapshot();
		if (state != null) {
			state.friends.forEach(f -> names.add(f.username));
		}
		var party = SocialClient.party();
		if (party != null && party.has("members")) {
			party.getAsJsonArray("members").forEach(m -> names.add(m.getAsJsonObject().get("name").getAsString()));
		}
		for (var other : net.veloclient.velo.client.pvp.PvpCompat.otherPlayers()) {
			names.add(other.getName().getString());
		}
		names.removeIf(n -> n == null || !n.toLowerCase(Locale.ROOT).startsWith(typed));
		names.forEach(builder::suggest);
		return builder.buildFuture();
	};

	private static final SuggestionProvider<FabricClientCommandSource> ITEMS = (context, builder) -> {
		loadItemIds();
		String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
		for (String id : itemIds) {
			if (id.toLowerCase(Locale.ROOT).startsWith(typed) || id.toLowerCase(Locale.ROOT).contains("_" + typed)) {
				builder.suggest(id);
			}
		}
		return builder.buildFuture();
	};

	private static final SuggestionProvider<FabricClientCommandSource> WAYPOINTS = (context, builder) -> {
		String world = WaypointManager.currentWorldKey();
		String typed = builder.getRemaining().toLowerCase(Locale.ROOT);
		if (world != null) {
			for (Waypoint waypoint : WaypointManager.inWorld(world)) {
				if (waypoint.name.toLowerCase(Locale.ROOT).startsWith(typed)) {
					builder.suggest(waypoint.name);
				}
			}
		}
		return builder.buildFuture();
	};

	private static void loadItemIds() {
		if (itemIdsRequested || !StoreClient.isOwner()) {
			return;
		}
		itemIdsRequested = true;
		StoreClient.adminCosmetics(result -> {
			List<String> ids = new ArrayList<>();
			result.getAsJsonArray("items").forEach(e -> ids.add(e.getAsJsonObject().get("id").getAsString()));
			ids.sort(String::compareTo);
			itemIds = List.copyOf(ids);
		}, e -> itemIdsRequested = false);
	}

	// ---- Everyone ----

	private static void help() {
		VeloChat.info("/velo plus - your Velo+ time left   /velo coins - your coins   /velo store | map | waypoints | friends | party");
		VeloChat.info("/velo waypoint <name> - post a waypoint in chat   /velo phantom [on|off]   /velo flyspeed <amount|off>");
		if (StoreClient.isOwner()) {
			VeloChat.info("Owner: /velo owner | lookup <player> | coins give|take <player> <n> | plus give <player> <days> | plus end <player>"
					+ " | cosmetic give|take <player> <id>");
		}
	}

	private static void plusStatus() {
		StoreClient.refresh(true, () -> {
			long until = StoreClient.plusUntil();
			if (StoreClient.plus() && until > System.currentTimeMillis()) {
				VeloChat.info("Velo+ is active - " + OwnerScreen.remaining(until) + " (until " + OwnerScreen.date(until) + ").");
			} else {
				VeloChat.info("You don't have Velo+. Get it in the Velo Store (/velo store).");
			}
		});
	}

	private static void coinsStatus() {
		StoreClient.refresh(true, () -> VeloChat.info("You have " + String.format(Locale.ROOT, "%,d", Math.max(0, StoreClient.balance())) + " Velo Coins."));
	}

	private static void partyStatus() {
		var party = SocialClient.party();
		if (party == null) {
			VeloChat.info("You're not in a Velo party. Start one from the launcher or the Friends menu (/velo friends).");
			return;
		}
		StringBuilder members = new StringBuilder();
		for (var member : party.getAsJsonArray("members")) {
			var o = member.getAsJsonObject();
			members.append(members.length() == 0 ? "" : ", ").append(o.get("name").getAsString())
					.append(o.has("leader") && o.get("leader").getAsBoolean() ? " (leader)" : "")
					.append(o.has("online") && o.get("online").getAsBoolean() ? "" : " [offline]");
		}
		VeloChat.info("Party: " + members + ".   Chat: /" + PartyChat.commandName() + " <message>");
	}

	private static void shareWaypoint(String name) {
		String world = WaypointManager.currentWorldKey();
		if (world == null) {
			VeloChat.error("Join a world first.");
			return;
		}
		for (Waypoint waypoint : WaypointManager.inWorld(world)) {
			if (waypoint.name.equalsIgnoreCase(name.strip())) {
				WaypointManager.shareInChat(waypoint);
				return;
			}
		}
		VeloChat.error("No waypoint called \"" + name + "\" in this world.");
	}

	// ---- Phantom / fly speed (also bare /phantom and /flyspeed when the server has neither) ----

	private static void phantom(String state) {
		var module = ModuleRegistry.get("phantom").orElse(null);
		if (module == null) {
			VeloChat.error("Phantom isn't available.");
			return;
		}
		boolean on = state == null ? !module.isEnabled() : state.equalsIgnoreCase("on");
		module.setEnabled(on);
		VeloChat.info("Phantom " + (on ? "on" : "off") + (on && !ClientCompat.isSingleplayer() ? " - it only works in singleplayer." : "."));
	}

	private static void flySpeed(String amount) {
		var module = ModuleRegistry.get("fly-speed").orElse(null);
		if (!(module instanceof FlySpeedModule fly)) {
			VeloChat.error("Fly Speed isn't available.");
			return;
		}
		if (amount == null) {
			VeloChat.info("Fly speed: " + (fly.isEnabled() ? String.format(Locale.ROOT, "%.1fx", fly.speed()) : "off") + ". /flyspeed <0.1-10> or /flyspeed off");
			return;
		}
		if (amount.equalsIgnoreCase("off") || amount.equalsIgnoreCase("reset")) {
			fly.setSpeedFromCommand(null);
			VeloChat.info("Fly speed back to normal.");
			return;
		}
		try {
			fly.setSpeedFromCommand(Double.parseDouble(amount.replace(',', '.')));
			VeloChat.info(String.format(Locale.ROOT, "Fly speed %.1fx.", fly.speed()) + " It only applies in singleplayer, creative or spectator.");
		} catch (NumberFormatException e) {
			VeloChat.error("Use a number between 0.1 and 10, or off.");
		}
	}

	/** Whether the server itself offers a command called {@code name}. */
	private static boolean serverHas(String name) {
		//? if <26.1 {
		var handler = MinecraftClient.getInstance().getNetworkHandler();
		return handler != null && handler.getCommandDispatcher().getRoot().getChild(name) != null;
		//?} else {
		/*var connection = net.minecraft.client.Minecraft.getInstance().getConnection();
		return connection != null && connection.getCommands().getRoot().getChild(name) != null;
		*///?}
	}

	/** {@code /phantom [on|off]} and {@code /flyspeed <amount>} typed in chat: ours only when the server has no such command. */
	public static boolean onOutgoingCommand(String command) {
		String[] words = command.strip().split("\\s+", 2);
		String name = words[0].toLowerCase(Locale.ROOT);
		String rest = words.length > 1 ? words[1].strip() : null;
		if (!name.equals("phantom") && !name.equals("flyspeed")) {
			return false;
		}
		if (serverHas(name)) {
			return false;
		}
		if (name.equals("phantom")) {
			phantom(rest == null || rest.isEmpty() ? null : rest);
		} else {
			flySpeed(rest == null || rest.isEmpty() ? null : rest);
		}
		return true;
	}

	// ---- Owner ----

	private static void ownerCoins(String target, int amount) {
		StoreClient.adminCoins(target, amount, "/velo", view -> {
			VeloChat.info((amount > 0 ? "Gave " : "Took ") + Math.abs(amount) + " coins " + (amount > 0 ? "to " : "from ") + view.get("username").getAsString()
					+ " - now " + String.format(Locale.ROOT, "%,d", view.get("balance").getAsInt()) + ".");
			StoreClient.refresh(true, null);
		}, VeloChat::error);
	}

	private static void ownerPlus(String target, int days) {
		StoreClient.adminPlus(target, days, view -> {
			long until = view.has("plusUntil") ? view.get("plusUntil").getAsLong() : 0;
			VeloChat.info(days > 0 ? view.get("username").getAsString() + " has Velo+ for " + OwnerScreen.remaining(until) + " (until " + OwnerScreen.date(until) + ")."
					: "Ended Velo+ for " + view.get("username").getAsString() + ".");
			StoreClient.refresh(true, null);
		}, VeloChat::error);
	}

	private static void ownerItem(String target, String item, boolean grant) {
		StoreClient.adminItem(target, item, grant, view -> {
			VeloChat.info((grant ? "Gave " : "Took ") + item + (grant ? " to " : " from ") + view.get("username").getAsString() + ".");
			StoreClient.refresh(true, null);
		}, VeloChat::error);
	}

	private static void lookup(String target) {
		StoreClient.adminLookup(target, view -> {
			long until = view.has("plusUntil") ? view.get("plusUntil").getAsLong() : 0;
			boolean plus = until > System.currentTimeMillis();
			StringBuilder owned = new StringBuilder();
			view.getAsJsonArray("owned").forEach(e -> owned.append(owned.length() == 0 ? "" : ", ").append(e.getAsString()));
			VeloChat.info(view.get("username").getAsString() + ": " + String.format(Locale.ROOT, "%,d", view.get("balance").getAsInt()) + " coins, Velo+ "
					+ (plus ? OwnerScreen.remaining(until) : "no") + ", owns " + (owned.length() == 0 ? "nothing" : owned) + ".");
		}, VeloChat::error);
	}
}
