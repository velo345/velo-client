package net.veloclient.launcher.data;

import java.nio.file.Path;

/** One cape in the shared library - mirrors the mod's {@code CapeDefinition}. */
public record CapeEntry(String id, String name, Path bundleFile, CapePhysicsPresetData physicsPreset) {
}
