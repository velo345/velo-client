package net.veloclient.launcher.data;

import java.nio.file.Path;

/** One cape in the shared library - mirrors the mod's {@code CapeDefinition}. {@code animated} bundles carry a {@code frames.gif} entry instead of {@code texture.png} (see {@code CapeLibrary#gifBytes}). */
public record CapeEntry(String id, String name, Path bundleFile, CapePhysicsPresetData physicsPreset, boolean animated) {
}
