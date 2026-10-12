package net.veloclient.velo.client.pvp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Extra lines drawn above other players' name tags (health, effects, hunger). Each module registers
 * a provider that returns a text line for an entity id, or null for none; the name tag mixin draws
 * them in order, closest to the name first, styled exactly like vanilla name tags.
 */
public final class NameTagLines {

	private record Provider(int order, BiFunction<Integer, Boolean, Object> line) {
	}

	private static final List<Provider> PROVIDERS = new ArrayList<>();
	/** Lines left free above a player whose vanilla name tag is hidden (server-drawn names/ranks/health). */
	private static int extraLines = 2;

	public static int extraLines() {
		return extraLines;
	}

	/** Shared "Height Above Head" setting, shown in each module that draws lines here. */
	public static net.veloclient.velo.module.ConfigField heightField() {
		return new net.veloclient.velo.module.ConfigField.SliderField("Height When Server Hides Names", 0, 5, () -> extraLines,
				v -> extraLines = (int) Math.round(v), v -> Math.round(v) + (Math.round(v) == 1 ? " line" : " lines"));
	}

	private NameTagLines() {
	}

	/**
	 * @param line (entity id, server already shows a line under the name - e.g. a health objective)
	 *             -> a Text (Component on 26.x) to show, or null; called on the render thread
	 */
	public static synchronized void register(int order, BiFunction<Integer, Boolean, Object> line) {
		PROVIDERS.add(new Provider(order, line));
		PROVIDERS.sort(Comparator.comparingInt(Provider::order));
	}

	public static synchronized List<Object> lines(int entityId, boolean hasScoreLine) {
		if (PROVIDERS.isEmpty() || entityId == PvpCompat.localPlayerId()) {
			return List.of();
		}
		List<Object> lines = new ArrayList<>(PROVIDERS.size());
		for (Provider provider : PROVIDERS) {
			try {
				Object line = provider.line().apply(entityId, hasScoreLine);
				if (line != null) {
					lines.add(line);
				}
			} catch (RuntimeException e) {
				// One broken line must never take down entity rendering.
			}
		}
		return lines;
	}
}
