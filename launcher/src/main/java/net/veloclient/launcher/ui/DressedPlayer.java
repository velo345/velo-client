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
					.map(CosmeticUi::frames).orElse(null);
		} catch (RuntimeException e) {
			LauncherLog.warn("Could not read the equipped cape", e);
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
}
