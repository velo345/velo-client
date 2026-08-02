package net.veloclient.launcher.data;

import java.util.List;
import java.util.Map;

/** Mirrors the mod's {@code ServerProfile} record/JSON shape (design spec sections 5 and 8). */
public record ProfileData(String name, List<String> addressPatterns, boolean safeMode, Map<String, Boolean> moduleStates) {
}
