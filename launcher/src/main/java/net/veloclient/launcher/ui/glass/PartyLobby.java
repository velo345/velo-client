package net.veloclient.launcher.ui.glass;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Popup;
import javafx.util.Duration;
import net.veloclient.launcher.social.LauncherSocial;
import net.veloclient.launcher.ui.Heads;
import net.veloclient.launcher.ui.Icons;
import net.veloclient.launcher.ui.NameTag;
import net.veloclient.launcher.ui.PlayerSkin3DView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Home's party lobby (2026-11-25): your party stands next to you on Home like a game lobby - you in the middle, the
 * others beside you in 3D (a little smaller and further back, alternating right and left) with their name tags;
 * invites come from the Friends panel on Home. Follows the party live (joins, leaves, invites) without
 * rebuilding Home; only the members' row changes.
 */
public final class PartyLobby {

	/** At most this many other members stand in 3D (the rest are listed on the Friends page). */
	private static final int SHOWN_3D = 3;
	private static final int MAX_MEMBERS = 8;
	/** Built players by uuid + skin, reused when Home is shown again (a 3D player costs a few ms to build). */
	private static final Map<String, Node> BUILT = new HashMap<>();
	/** Members who already waved hello: coming back to Home doesn't make the whole party wave again. */
	private static final java.util.Set<String> GREETED = new java.util.HashSet<>();

	private static void greet(String uuid, Node viewer) {
		if (viewer != null && GREETED.add(uuid)) {
			PlayerEntrance.wave(viewer);
		}
	}

	private final StackPane me;
	private final StackPane behind = new StackPane();
	private final StackPane root = new StackPane();
	private String lastKey = "";

	private PartyLobby(StackPane me) {
		this.me = me;
	}

	/** Wraps Home's own player stage ({@code me}) with the party around it. */
	public static Node wrap(StackPane me) {
		return new PartyLobby(me).build();
	}

	private Node build() {
		// You keep your full-width stage in the middle (a narrower slot clipped your aura and wings); the others stand in a
		// layer behind you, offset to the sides. (No "+" rail: invites are a button on each online friend in Home's
		// Friends panel - owner, 2026-11-25.)
		behind.setMinSize(0, 0);
		behind.setPickOnBounds(false);
		root.getChildren().addAll(behind, me);
		root.widthProperty().addListener((o, a, w) -> placeMembers());
		root.setMinHeight(0);
		VBox.setVgrow(root, Priority.ALWAYS);
		java.util.function.Consumer<LauncherSocial.PartyEvent> listener = event -> {
			if (event.type().equals("party") || event.type().equals("party_refresh")) {
				refresh();
			} else if (event.type().equals("party_emote") && event.data() != null && event.data().has("from") && event.data().has("name")) {
				// a party member emoted on their Home: they do it here too
				String from = event.data().get("from").getAsString().replace("-", "");
				for (var entry : BUILT.entrySet()) {
					if (entry.getKey().replace("-", "").equalsIgnoreCase(from)) {
						EmoteWheel.play(entry.getValue(), event.data().get("name").getAsString());
					}
				}
			}
		};
		root.sceneProperty().addListener((o, a, scene) -> {
			if (scene == null) {
				LauncherSocial.removePartyListener(listener);
			} else {
				LauncherSocial.addPartyListener(listener);
			}
		});
		refresh();
		return root;
	}

	private record Member(String uuid, String name, boolean online, boolean leader) {
	}

	private List<Member> others() {
		List<Member> out = new ArrayList<>();
		// dev (screenshot tour, signed out): -Dvelo.tourParty=Name:uuid,Name:uuid stands a party next to you
		String tour = System.getProperty("velo.tourParty");
		if (tour != null) {
			int i = 0;
			for (String part : tour.split(",")) {
				String[] kv = part.split(":");
				if (kv.length == 2) {
					out.add(new Member(kv[1], kv[0], true, i++ == 0));
				}
			}
			return out;
		}
		JsonObject party = LauncherSocial.party();
		String mine = LauncherSocial.myUuid();
		if (party == null || !party.has("members")) {
			return out;
		}
		String leader = party.has("leader") ? party.get("leader").getAsString() : "";
		for (JsonElement e : party.getAsJsonArray("members")) {
			JsonObject m = e.getAsJsonObject();
			String uuid = str(m, "uuid");
			if (uuid.isEmpty() || uuid.equals(mine)) {
				continue;
			}
			out.add(new Member(uuid, str(m, "name"), m.has("online") && m.get("online").getAsBoolean(), uuid.equals(leader)));
		}
		return out;
	}

	private List<JsonObject> invited() {
		List<JsonObject> out = new ArrayList<>();
		JsonObject party = LauncherSocial.party();
		if (party != null && party.has("invited")) {
			for (JsonElement e : party.getAsJsonArray("invited")) {
				out.add(e.getAsJsonObject());
			}
		}
		return out;
	}

	private void refresh() {
		List<Member> others = others();
		StringBuilder key = new StringBuilder();
		for (Member m : others) {
			key.append(m.uuid()).append(m.online()).append(m.leader()).append(';');
		}
		if (!key.toString().equals(lastKey)) {
			lastKey = key.toString();
			layoutMembers(others);
		}
	}

	/** Side offsets in steps from the middle: right, left, further right - the way game lobbies line a party up. */
	private static final double[] STEPS = {1, -1, 1.75};

	/** One step: as wide as the stage allows, so nobody stands under the right column (120..200 px). */
	private double step() {
		double half = root.getWidth() / 2;
		return half <= 0 ? 180 : Math.max(120, Math.min(200, (half - 100) / 1.75));
	}

	private void placeMembers() {
		double step = step();
		for (int i = 0; i < behind.getChildren().size(); i++) {
			behind.getChildren().get(i).setTranslateX(STEPS[i] * step);
		}
	}

	/** You in the middle; the others alternate right, left, right, behind you. */
	/** Who stands where now (uuid -> slot), so a party change moves, adds and removes people instead of rebuilding. */
	private final java.util.Map<String, StackPane> holders = new java.util.LinkedHashMap<>();
	private final java.util.Map<String, String> holderState = new java.util.HashMap<>();

	private void layoutMembers(List<Member> others) {
		int shown = Math.min(SHOWN_3D, others.size());
		java.util.Set<String> keep = new java.util.HashSet<>();
		for (int i = 0; i < shown; i++) {
			keep.add(others.get(i).uuid());
		}
		// leaving: a wave goodbye, then they step aside and fade out
		for (var e : new ArrayList<>(holders.entrySet())) {
			if (!keep.contains(e.getKey())) {
				holders.remove(e.getKey());
				holderState.remove(e.getKey());
				GREETED.remove(e.getKey());
				leave(e.getKey(), e.getValue());
			}
		}
		double step = step();
		for (int i = 0; i < shown; i++) {
			Member m = others.get(i);
			String state = m.online() + ":" + m.leader();
			StackPane holder = holders.get(m.uuid());
			if (holder != null && state.equals(holderState.get(m.uuid()))) {
				// staying: glide to the new spot
				TranslateTransition glide = new TranslateTransition(Duration.millis(420), holder);
				glide.setToX(STEPS[i] * step);
				glide.setInterpolator(Interpolator.SPLINE(0.2, 0.9, 0.25, 1));
				glide.play();
				continue;
			}
			boolean arriving = holder == null;
			if (holder == null) {
				holder = new StackPane();
				holder.setMaxWidth(220);
				holder.setMinSize(0, 0);
				holder.setPickOnBounds(false);
				StackPane.setAlignment(holder, Pos.BOTTOM_CENTER);
				holders.put(m.uuid(), holder);
				behind.getChildren().add(holder);
			}
			holder.setTranslateX(STEPS[i] * step);
			holder.getChildren().setAll(memberSlot(m, i, arriving));
			holderState.put(m.uuid(), state);
		}
	}

	/** Waves, then slides out to its side and fades; removed when done. */
	private void leave(String uuid, StackPane holder) {
		Node viewer = BUILT.get(uuid);
		PlayerEntrance.wave(viewer);
		double dir = holder.getTranslateX() >= 0 ? 1 : -1;
		FadeTransition fade = new FadeTransition(Duration.millis(420), holder);
		fade.setToValue(0);
		TranslateTransition away = new TranslateTransition(Duration.millis(520), holder);
		away.setByX(60 * dir);
		away.setInterpolator(Interpolator.EASE_IN);
		ParallelTransition out = new ParallelTransition(fade, away);
		out.setDelay(Duration.millis(1500));
		out.setOnFinished(e -> behind.getChildren().remove(holder));
		out.play();
	}

	private Node memberSlot(Member member, int index, boolean arriving) {
		StackPane model = new StackPane();
		model.setMinSize(0, 0);
		VBox.setVgrow(model, Priority.ALWAYS);
		Node tag = NameTag.other(member.name());
		Label status = new Label(member.leader() ? "LEADER" : member.online() ? "READY" : "OFFLINE");
		status.getStyleClass().add("lobby-status");
		if (member.leader()) {
			status.getStyleClass().add("lobby-status-leader");
		}
		VBox column = new VBox(4, tag, status, model);
		column.setAlignment(Pos.BOTTOM_CENTER);
		column.setMinWidth(0);
		column.setPrefWidth(220);
		column.setMaxWidth(240);
		column.setMinHeight(0);
		// a step back: smaller and lower, so you stay the one in front
		double scale = index == 0 ? 0.84 : 0.76;
		column.setScaleX(scale);
		column.setScaleY(scale);
		column.setTranslateY(18 + index * 6);
		column.setOpacity(member.online() ? 1 : 0.55);
		column.setViewOrder(1 + index);
		Node cached = BUILT.get(member.uuid());
		if (cached != null) {
			if (cached.getParent() instanceof javafx.scene.layout.Pane old) {
				old.getChildren().remove(cached);
			}
			model.getChildren().setAll(cached);
		} else {
			model.getChildren().setAll(net.veloclient.launcher.ui.Skeleton.player());
			// their skin and what they wear (cloak, wings, aura) - both fetched off the UI thread
			CompletableFuture<byte[][]> skinFuture = CompletableFuture.supplyAsync(() -> {
				byte[][] skin = net.veloclient.launcher.sandbox.RemoteSkins.fetch(member.uuid());
				if (skin == null) {
					// Mojang's skin server turns quick repeats away for a moment: one more try
					try {
						Thread.sleep(2000);
					} catch (InterruptedException e) {
						return null;
					}
					skin = net.veloclient.launcher.sandbox.RemoteSkins.fetch(member.uuid());
				}
				return skin;
			}, Executors.newVirtualThreadPerTaskExecutor()).exceptionally(e -> null);
			CompletableFuture<JsonObject> lookFuture = CompletableFuture.supplyAsync(() -> look(member.uuid()),
					Executors.newVirtualThreadPerTaskExecutor()).exceptionally(e -> null);
			skinFuture.thenCombine(lookFuture, (skin, look) -> new Object[] {skin, look}).thenAccept(both -> Platform.runLater(() -> {
				byte[][] skin = (byte[][]) both[0];
				JsonObject look = (JsonObject) both[1];
				if (skin == null) {
					showViewer(model, member, null);
					return;
				}
				net.veloclient.launcher.ui.DressedPlayer.buildOther(new net.veloclient.launcher.auth.SkinFetcher.SkinData(skin[0], skin[1][0] == 1),
						str(look, "capeId").isEmpty() ? null : str(look, "capeId"), str(look, "wingsId").isEmpty() ? null : str(look, "wingsId"),
						str(look, "auraId").isEmpty() ? null : str(look, "auraId"), viewer -> showViewer(model, member, viewer));
			}));
		}
		return finishSlot(column, member, index, arriving);
	}

	/** Puts a built player (or, without one, their face) into its slot, rising in. */
	private void showViewer(StackPane model, Member member, Node viewer) {
		if (viewer == null) {
			// no skin to be had (offline, default skin): their face instead
			StackPane face = new StackPane(Heads.head(member.uuid(), member.name(), 96));
			face.setAlignment(Pos.BOTTOM_CENTER);
			face.setPadding(new Insets(0, 0, 60, 0));
			model.getChildren().setAll(face);
			return;
		}
		if (viewer instanceof Region region) {
			region.setMinSize(0, 0);
		}
		// the camera a step back: the slot is narrow, and a waving hand ran out of it (VELO-190)
		if (viewer.getProperties().get("velo.camera") instanceof javafx.scene.PerspectiveCamera camera) {
			camera.setTranslateZ(camera.getTranslateZ() * 1.2);
		}
		BUILT.put(member.uuid(), viewer);
		model.getChildren().setAll(viewer);
		viewer.setOpacity(0);
		viewer.setTranslateY(24);
		FadeTransition fade = new FadeTransition(Duration.millis(360), viewer);
		fade.setToValue(1);
		TranslateTransition rise = new TranslateTransition(Duration.millis(520), viewer);
		rise.setToY(0);
		rise.setInterpolator(Interpolator.SPLINE(0.2, 0.9, 0.2, 1));
		ParallelTransition in = new ParallelTransition(fade, rise);
		// a wave hello once they're there
		in.setOnFinished(e -> greet(member.uuid(), viewer));
		in.play();
	}

	private static final java.net.http.HttpClient HTTP = java.net.http.HttpClient.newBuilder()
			.connectTimeout(java.time.Duration.ofSeconds(6)).build();

	/** What {@code uuid} wears right now (cloak, wings, aura) from the Velo server, or null. */
	private static JsonObject look(String uuid) {
		try {
			JsonObject body = new JsonObject();
			com.google.gson.JsonArray ids = new com.google.gson.JsonArray();
			ids.add(uuid);
			body.add("uuids", ids);
			body.addProperty("includeOnline", true);
			var response = HTTP.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(LauncherSocial.baseUrl() + "/v1/cosmetics/of"))
					.timeout(java.time.Duration.ofSeconds(8)).header("Content-Type", "application/json")
					.POST(java.net.http.HttpRequest.BodyPublishers.ofString(body.toString())).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200) {
				return null;
			}
			JsonObject json = com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject();
			if (!json.has("users")) {
				return null;
			}
			for (JsonElement e : json.getAsJsonArray("users")) {
				return e.getAsJsonObject();
			}
		} catch (Exception e) {
			// shown without cosmetics
		}
		return null;
	}

	private Node finishSlot(VBox column, Member member, int index, boolean arriving) {
		if (!arriving) {
			// only their status changed: no entrance again
			column.setTranslateX(0);
			return column;
		}
		Node built = BUILT.get(member.uuid());
		if (built != null) {
			javafx.animation.PauseTransition later = new javafx.animation.PauseTransition(Duration.millis(500));
			later.setOnFinished(e -> greet(member.uuid(), built));
			later.play();
		}
		// arrives sliding in from its side
		column.setOpacity(0);
		double from = index % 2 == 0 ? 40 : -40;
		column.setTranslateX(from);
		double target = member.online() ? 1 : 0.55;
		FadeTransition in = new FadeTransition(Duration.millis(320), column);
		in.setToValue(target);
		TranslateTransition slide = new TranslateTransition(Duration.millis(480), column);
		slide.setToX(0);
		slide.setInterpolator(Interpolator.SPLINE(0.2, 0.9, 0.2, 1));
		ParallelTransition enter = new ParallelTransition(in, slide);
		enter.setDelay(Duration.millis(80 * index));
		enter.play();
		Tooltip.install(column, new Tooltip(member.name() + (member.online() ? "" : " (offline)")));
		return column;
	}

	private static String str(JsonObject o, String key) {
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
	}
}
