package net.veloclient.velo.client.modules.queue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls a queue position/ETA out of a chat line, boss bar title, or tab-list
 * header/footer string. Ships a couple of presets matching common queue
 * plugin phrasing (2b2t-style "You are now queued", Hypixel-lobby-style
 * "Position in queue"), plus a custom regex fallback for anything else -
 * every server's queue plugin phrases this differently and there's no
 * universal packet for it, so this is inherently best-effort text matching,
 * not a protocol feature.
 *
 * <p>The Luxonity/MCTiers/MCPvP.club presets exist so those servers show up
 * as named, auto-detected options ({@link #presetForHost}) rather than
 * forcing "Generic" or a hand-written regex - but their exact queue-message
 * wording hasn't been verified against a real message from those servers,
 * so they currently just alias the same broad {@link #GENERIC_POSITION}
 * pattern. If a session on one of them shows "Waiting for status..." even
 * though it's genuinely queued, switch its preset to Custom and paste a
 * regex built from an actual message you see in its chat.
 */
public final class QueueStatusParser {

	public record Status(int position, String etaText, String rawText, long updatedAtMs) {
		public static final Status EMPTY = new Status(-1, "", "", 0);

		public boolean known() {
			return position >= 0 || !rawText.isEmpty();
		}
	}

	/** {@code Auto-detect} resolves a preset from the session's server address ({@link #presetForHost}), falling back to Generic. */
	public static final java.util.List<String> PRESET_NAMES = java.util.List.of(
			"Auto-detect", "Generic", "2b2t-style", "Hypixel-style", "Luxonity-style", "MCTiers-style", "MCPvP.club-style", "Custom");

	/** Substring-of-hostname -> preset name, checked in order; first match wins. */
	private static final java.util.List<java.util.Map.Entry<String, String>> HOST_PRESETS = java.util.List.of(
			java.util.Map.entry("2b2t.org", "2b2t-style"),
			java.util.Map.entry("hypixel.net", "Hypixel-style"),
			java.util.Map.entry("luxonity", "Luxonity-style"),
			java.util.Map.entry("mctiers", "MCTiers-style"),
			java.util.Map.entry("mcpvp.club", "MCPvP.club-style"));

	private static final Pattern GENERIC_POSITION = Pattern.compile(
			"(?:position|place|queue)[^0-9]{0,15}#?(\\d+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern TWOBTWOT_POSITION = Pattern.compile(
			"you are (?:now )?queued[^0-9]*?(\\d+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern HYPIXEL_POSITION = Pattern.compile(
			"position in queue[^0-9]*?(\\d+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern ETA = Pattern.compile(
			"(?:eta|estimated|time)[^0-9]{0,10}(\\d+\\s*(?:s|sec|second|m|min|minute|h|hour)s?)", Pattern.CASE_INSENSITIVE);

	private QueueStatusParser() {
	}

	/** Best-guess preset for a server address, or "Generic" if nothing recognized - used to auto-select a session's parsing when "Auto-detect" is chosen. */
	public static String presetForHost(String address) {
		if (address == null) {
			return "Generic";
		}
		String lower = address.toLowerCase(java.util.Locale.ROOT);
		for (var entry : HOST_PRESETS) {
			if (lower.contains(entry.getKey())) {
				return entry.getValue();
			}
		}
		return "Generic";
	}

	public static Status parse(String preset, String customRegex, String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		Pattern positionPattern = switch (preset) {
			case "2b2t-style" -> TWOBTWOT_POSITION;
			case "Hypixel-style" -> HYPIXEL_POSITION;
			// Best-guess aliases - see class javadoc.
			case "Luxonity-style", "MCTiers-style", "MCPvP.club-style" -> GENERIC_POSITION;
			case "Custom" -> compileCustom(customRegex);
			default -> GENERIC_POSITION;
		};
		if (positionPattern == null) {
			return null;
		}
		Matcher positionMatcher = positionPattern.matcher(text);
		int position = -1;
		if (positionMatcher.find()) {
			try {
				position = Integer.parseInt(positionMatcher.group(1));
			} catch (NumberFormatException | IndexOutOfBoundsException ignored) {
				// Custom regex might not have a capture group - still useful
				// as a "something matched" signal even without a position.
			}
		} else if (!"Custom".equals(preset)) {
			return null;
		}
		String eta = "";
		Matcher etaMatcher = ETA.matcher(text);
		if (etaMatcher.find()) {
			eta = etaMatcher.group(1);
		}
		return new Status(position, eta, text, System.currentTimeMillis());
	}

	private static Pattern compileCustom(String regex) {
		if (regex == null || regex.isBlank()) {
			return null;
		}
		try {
			return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
		} catch (java.util.regex.PatternSyntaxException e) {
			return null;
		}
	}
}
