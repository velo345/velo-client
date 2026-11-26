package net.veloclient.launcher.ui;

import javafx.application.Platform;
import javafx.scene.Node;
import net.veloclient.launcher.LauncherLog;
import net.veloclient.launcher.auth.SkinFetcher;
import net.veloclient.launcher.data.AuraCatalog;
import net.veloclient.launcher.data.CapeLibrary;
import net.veloclient.launcher.data.GifFrames;
import net.veloclient.launcher.data.WingsCatalog;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * The signed-in player's 3D model wearing whatever is equipped (cape, wings, aura) - shared by the
 * home screen and the profile page so both look the same. Nothing here may leave the caller
 * waiting: any failure while preparing a cosmetic falls back to the plain skin.
 */
public final class DressedPlayer {

	private DressedPlayer() {
	}

	/** Cape frames for the equipped cape (disk + decoding: call off the FX thread); null when none or on failure. */
	private static List<GifFrames.Frame> equippedCapeFrames() {
		try {
			return CapeLibrary.equippedCapeId().flatMap(id -> CapeLibrary.listAll().stream().filter(c -> c.id().equals(id)).findFirst())
					.map(CosmeticUi::viewerFrames).orElse(null);
		} catch (RuntimeException e) {
			LauncherLog.warn("Could not read the equipped cloak", e);
			return null;
		}
	}

	/**
	 * Builds the model for {@code skin} off the FX thread (cape frames) and hands it to {@code onReady}
	 * on the FX thread; {@code onReady} gets null if the skin itself can't be rendered.
	 */
	public static void build(SkinFetcher.SkinData skin, boolean interactive, double yaw, Consumer<Node> onReady) {
		CompletableFuture.supplyAsync(DressedPlayer::equippedCapeFrames, Executors.newVirtualThreadPerTaskExecutor())
				.exceptionally(e -> null)
				.thenAccept(frames -> Platform.runLater(() -> onReady.accept(create(skin, frames, interactive, yaw))));
	}

	/** FX thread. */
	public static Node create(SkinFetcher.SkinData skin, List<GifFrames.Frame> frames, boolean interactive, double yaw) {
		if (skin == null) {
			return null;
		}
		List<GifFrames.Frame> cape = frames == null || frames.isEmpty() ? null : frames;
		String wingsId = null;
		String auraId = null;
		try {
			wingsId = WingsCatalog.equipped();
			if (wingsId != null && WingsCatalog.byId(wingsId).isEmpty()) {
				wingsId = null;
			}
			auraId = AuraCatalog.equipped();
		} catch (RuntimeException e) {
			LauncherLog.warn("Could not read the equipped cosmetics", e);
		}
		boolean dressed = cape != null || wingsId != null || auraId != null;
		try {
			String wings = wingsId;
			Node viewer = PlayerSkin3DView.createViewer(skin.pngBytes(), skin.slim(), cape, interactive, yaw, dressed, wings == null ? null : model -> {
				Wings3D w = new Wings3D(model);
				w.settings(WingsCatalog.settings(wings));
				w.show(wings);
			});
			if (viewer != null && auraId != null) {
				String aura = auraId;
				var settings = AuraCatalog.settings(aura);
				AuraFx.attach(viewer, () -> new AuraFx.Shown(aura, settings));
			}
			if (viewer != null) {
				return viewer;
			}
		} catch (RuntimeException e) {
			LauncherLog.warn("Could not render the 3D player with cosmetics - showing the plain skin", e);
		}
		try {
			return PlayerSkin3DView.createViewer(skin.pngBytes(), skin.slim(), null, interactive, yaw, false, null);
		} catch (RuntimeException e) {
			LauncherLog.warn("Could not render the 3D player", e);
			return null;
		}
	}

	/** Home's idle player (glass launcher): your skin with the equipped cloak, wings and aura, breathing and swaying. */
	public static void buildIdle(SkinFetcher.SkinData skin, Consumer<Node> onReady) {
		CompletableFuture.supplyAsync(() -> {
			// everything heavy is decoded here, off the UI thread, so the player pops in without a blank frame
			try {
				String wings = WingsCatalog.equipped();
				Wings3D.warm(wings != null && WingsCatalog.byId(wings).isPresent() ? wings : null);
			} catch (RuntimeException ignored) {
				// built when shown then
			}
			return equippedCapeFrames();
		}, Executors.newVirtualThreadPerTaskExecutor())
				.exceptionally(e -> null)
				.thenAccept(frames -> Platform.runLater(() -> onReady.accept(createIdle(skin, frames))));
	}

	/**
	 * Someone else's idle player (the party on Home) in what they wear: their Store cloak, wings and aura, in the default
	 * look (their own colour tweaks aren't known here). Custom uploaded cloaks aren't shown yet. {@code onReady} runs on
	 * the FX thread.
	 */
	public static void buildOther(SkinFetcher.SkinData skin, String capeId, String wingsId, String auraId, Consumer<Node> onReady) {
		String wings = wingsId != null && WingsCatalog.byId(wingsId).isPresent() ? wingsId : null;
		String aura = auraId != null && AuraCatalog.byId(auraId).isPresent() ? auraId : null;
		net.veloclient.launcher.data.StoreItem item = capeId == null ? null : net.veloclient.launcher.data.StoreCatalog.byId(capeId).orElse(null);
		CompletableFuture.runAsync(() -> {
			if (wings != null) {
				Wings3D.warm(wings);
			}
		}, Executors.newVirtualThreadPerTaskExecutor()).exceptionally(e -> null).thenRun(() -> Platform.runLater(() -> {
			if (item == null) {
				onReady.accept(createOther(skin, null, wings, aura));
			} else {
				CosmeticUi.viewerFramesAsync(item, frames -> onReady.accept(createOther(skin, frames, wings, aura)));
			}
		}));
	}

	private static Node createOther(SkinFetcher.SkinData skin, List<GifFrames.Frame> frames, String wings, String aura) {
		if (skin == null) {
			return null;
		}
		try {
			Node viewer = PlayerSkin3DView.createIdleViewer(skin.pngBytes(), skin.slim(), frames == null || frames.isEmpty() ? null : frames,
					wings == null ? null : model -> {
						Wings3D w = new Wings3D(model);
						w.settings(WingsCatalog.Settings.DEFAULT);
						w.show(wings);
					});
			if (viewer != null && aura != null) {
				AuraFx.attach(viewer, () -> new AuraFx.Shown(aura, net.veloclient.velo.client.cosmetics.aura.AuraEffects.Settings.DEFAULT));
			}
			return viewer;
		} catch (RuntimeException e) {
			LauncherLog.warn("Could not render a party member's look - showing the plain player", e);
			return PlayerSkin3DView.createIdleViewer(skin.pngBytes(), skin.slim(), null, null);
		}
	}

	private static Node createIdle(SkinFetcher.SkinData skin, List<GifFrames.Frame> frames) {
		if (skin == null) {
			return null;
		}
		List<GifFrames.Frame> cape = frames == null || frames.isEmpty() ? null : frames;
		String wingsId = null;
		String auraId = null;
		try {
			wingsId = WingsCatalog.equipped();
			if (wingsId != null && WingsCatalog.byId(wingsId).isEmpty()) {
				wingsId = null;
			}
			auraId = AuraCatalog.equipped();
		} catch (RuntimeException e) {
			LauncherLog.warn("Could not read the equipped cosmetics", e);
		}
		try {
			String wings = wingsId;
			Node viewer = PlayerSkin3DView.createIdleViewer(skin.pngBytes(), skin.slim(), cape, wings == null ? null : model -> {
				Wings3D w = new Wings3D(model);
				w.settings(WingsCatalog.settings(wings));
				w.show(wings);
			});
			if (viewer != null && auraId != null) {
				String aura = auraId;
				var settings = AuraCatalog.settings(aura);
				AuraFx.attach(viewer, () -> new AuraFx.Shown(aura, settings));
			}
			return viewer;
		} catch (RuntimeException e) {
			LauncherLog.warn("Could not render the idle player - showing the plain one", e);
			return create(skin, frames, true, 20);
		}
	}
}
