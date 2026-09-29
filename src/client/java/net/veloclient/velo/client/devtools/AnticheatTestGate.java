package net.veloclient.velo.client.devtools;

import net.veloclient.velo.VeloClient;
import net.veloclient.velo.config.VeloPaths;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Deliberately NOT a {@link net.veloclient.velo.module.Module}: this harness sends fabricated
 * movement/vehicle-move packets to test the server's own anticheat, which is exactly the class of
 * behavior every real module in this project is built to never do (see the top of SAFETY.md). It
 * must never be reachable by an ordinary player who downloads the public client.
 *
 * <p>Gating is a single, one-time file check at startup — {@link #isEnabled()} is read ONCE by
 * {@code VeloClientMod#onInitializeClient} before anything in this package is even registered
 * (no keybinds, no tick listener, nothing). A player who never creates the marker file themselves
 * has zero code path into this package: it doesn't appear in the in-game panel (never registered
 * with {@link net.veloclient.velo.module.ModuleRegistry}, so it's absent from the manifest export
 * too), and it doesn't appear in vanilla's own Controls menu either, since the {@code KeyBinding}s
 * are only constructed when this returns true.
 *
 * <p>To enable: create an empty file at {@code ~/.velo-client/anticheat-test.enabled} (or the
 * Windows equivalent under {@code %APPDATA%/VeloClient/}) and restart the client.
 */
public final class AnticheatTestGate {

    private static final String MARKER_FILE_NAME = "anticheat-test.enabled";
    private static final boolean ENABLED = computeEnabled();

    private AnticheatTestGate() {
    }

    private static boolean computeEnabled() {
        Path marker = VeloPaths.root().resolve(MARKER_FILE_NAME);
        boolean present = Files.isRegularFile(marker);
        if (present) {
            VeloClient.LOGGER.warn("[AnticheatTest] {} present - anticheat test harness ENABLED. "
                + "This intentionally sends fabricated movement packets; only ever use it against "
                + "your own test server. Delete this file to disable.", marker);
        }
        return present;
    }

    public static boolean isEnabled() {
        return ENABLED;
    }
}
