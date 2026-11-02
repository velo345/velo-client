package net.veloclient.launcher.doctor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.List;

/**
 * Fabric's version requirements ({@code ">=0.15.0"}, {@code "~1.2"}, {@code "^3"}, {@code "1.21.x"},
 * {@code ">=1.20 <1.21"}, {@code "*"}, or a list where any one may match) checked against a version string.
 * Lenient on purpose: anything it can't read counts as a match, so the Doctor never invents a problem.
 */
final class VersionPredicate {

	private VersionPredicate() {
	}

	/** A requirement value from fabric.mod.json - a string or an array of strings (any one matching is enough). */
	static boolean matches(JsonElement requirement, String version) {
		if (requirement == null || requirement.isJsonNull() || version == null) {
			return true;
		}
		if (requirement.isJsonArray()) {
			JsonArray any = requirement.getAsJsonArray();
			if (any.isEmpty()) {
				return true;
			}
			for (JsonElement e : any) {
				if (e.isJsonPrimitive() && matches(e.getAsString(), version)) {
					return true;
				}
			}
			return false;
		}
		return !requirement.isJsonPrimitive() || matches(requirement.getAsString(), version);
	}

	static boolean matches(String requirement, String version) {
		if ("*".equals(version)) {
			return true; // a version the planner only imagines (an install / update that picks a fitting one)
		}
		String r = requirement.strip();
		if (r.isEmpty() || r.equals("*")) {
			return true;
		}
		// Space-separated parts must all match (">=1.20 <1.21").
		for (String part : r.split("\\s+")) {
			if (!matchesOne(part, version)) {
				return false;
			}
		}
		return true;
	}

	private static boolean matchesOne(String part, String version) {
		try {
			if (part.startsWith(">=")) {
				return compare(version, part.substring(2)) >= 0;
			}
			if (part.startsWith("<=")) {
				return compare(version, part.substring(2)) <= 0;
			}
			if (part.startsWith(">")) {
				return compare(version, part.substring(1)) > 0;
			}
			if (part.startsWith("<")) {
				return compare(version, part.substring(1)) < 0;
			}
			if (part.startsWith("=")) {
				return compare(version, part.substring(1)) == 0;
			}
			if (part.startsWith("~")) {
				// ~1.2.3: same major.minor, at least 1.2.3
				String base = part.substring(1);
				List<Long> b = numbers(base);
				List<Long> v = numbers(version);
				return compare(version, base) >= 0 && v.size() >= 2 && b.size() >= 1 && v.get(0).equals(b.get(0))
						&& (b.size() < 2 || v.get(1).equals(b.get(1)));
			}
			if (part.startsWith("^")) {
				String base = part.substring(1);
				List<Long> b = numbers(base);
				List<Long> v = numbers(version);
				return compare(version, base) >= 0 && !v.isEmpty() && !b.isEmpty() && v.get(0).equals(b.get(0));
			}
			if (part.contains("x") || part.contains("X") || part.contains("*")) {
				String[] want = part.split("\\.");
				String[] have = core(version).split("\\.");
				for (int i = 0; i < want.length; i++) {
					String w = want[i];
					if (w.equalsIgnoreCase("x") || w.equals("*")) {
						return true;
					}
					if (i >= have.length || !w.equals(have[i])) {
						return false;
					}
				}
				return true;
			}
			return compare(version, part) == 0 || core(version).equals(core(part));
		} catch (RuntimeException e) {
			return true;
		}
	}

	/** Numeric, part by part ("1.21.11" > "1.21.2"); a pre-release ("-beta") sorts below its release. */
	static int compare(String a, String b) {
		List<Long> x = numbers(a);
		List<Long> y = numbers(b);
		for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
			long p = i < x.size() ? x.get(i) : 0;
			long q = i < y.size() ? y.get(i) : 0;
			if (p != q) {
				return Long.compare(p, q);
			}
		}
		boolean preA = core(a).length() < a.replaceAll("\\+.*$", "").length();
		boolean preB = core(b).length() < b.replaceAll("\\+.*$", "").length();
		return preA == preB ? 0 : preA ? -1 : 1;
	}

	/** "1.2.3-beta.4+mc1.21" -> "1.2.3" */
	private static String core(String version) {
		return version.strip().replaceAll("[-+].*$", "");
	}

	private static List<Long> numbers(String version) {
		List<Long> out = new ArrayList<>();
		for (String piece : core(version).split("\\.")) {
			String digits = piece.replaceAll("\\D.*$", "");
			if (digits.isEmpty()) {
				break;
			}
			out.add(Long.parseLong(digits.length() > 12 ? digits.substring(0, 12) : digits));
		}
		return out;
	}
}
