package net.veloclient.velo.client.gui.media;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The stat lines of a fight recap ({@code FightTracker.Recap} as JSON), shared by the recap screen and the clip viewer. */
public final class FightRecapRows {

	private FightRecapRows() {
	}

	public static List<String[]> rows(JsonObject r) {
		List<String[]> rows = new ArrayList<>();
		int dealt = i(r, "hitsDealt");
		int attacks = i(r, "attacks");
		rows.add(new String[] {"Duration", String.format(Locale.ROOT, "%.1fs", d(r, "durationMillis") / 1000.0)});
		rows.add(new String[] {"Hits dealt", String.valueOf(dealt)});
		rows.add(new String[] {"Hits taken", String.valueOf(i(r, "hitsTaken"))});
		rows.add(new String[] {"Best combo", String.valueOf(i(r, "bestCombo"))});
		rows.add(new String[] {"Crits", String.valueOf(i(r, "crits"))});
		rows.add(new String[] {"Accuracy", attacks <= 0 ? "-" : Math.min(100, Math.round(dealt * 100f / attacks)) + "%"});
		rows.add(new String[] {"Avg reach", d(r, "averageReach") <= 0 ? "-" : String.format(Locale.ROOT, "%.2f", d(r, "averageReach"))});
		rows.add(new String[] {"Max reach", d(r, "maxReach") <= 0 ? "-" : String.format(Locale.ROOT, "%.2f", d(r, "maxReach"))});
		rows.add(new String[] {"Damage dealt", hearts(d(r, "damageDealt"))});
		rows.add(new String[] {"Damage taken", hearts(d(r, "damageTaken"))});
		rows.add(new String[] {"Your health", hearts(d(r, "healthLeft"))});
		return rows;
	}

	/** Health points as hearts (2 HP = 1 heart), the way players talk about it. */
	public static String hearts(double hp) {
		return String.format(Locale.ROOT, "%.1f ❤", hp / 2.0);
	}

	private static int i(JsonObject o, String key) {
		return o.has(key) ? o.get(key).getAsInt() : 0;
	}

	private static double d(JsonObject o, String key) {
		return o.has(key) ? o.get(key).getAsDouble() : 0;
	}
}
