package net.veloclient.server.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Partner codes: a player enters one in the Store, and the purchase counts for the partner. Never for Velo+.
 *
 * <p>The codes are ours (owner panel: add, change, delete) and kept in {@code <data>/partner-codes.json}. Each is tied
 * to one thing in Tebex, made in the Tebex panel, that the server puts on the basket at checkout:
 * <ul>
 * <li>{@code coupon}: a Tebex coupon - the player pays less (e.g. 10% on the Velo Coins category);</li>
 * <li>{@code creator}: a Tebex creator code - Tebex pays the partner a share of the sale.</li>
 * </ul>
 * When a code is saved the server tries it on a throwaway Tebex basket: whether Tebex takes it and how much cheaper the
 * basket gets ({@link Code#percent()}, what the Store then shows). A code Tebex doesn't take is never offered to
 * players. Which code an order used is kept in the store database for the partner stats.
 */
public final class PartnerCodes {

	static final int MAX = 200;

	/**
	 * {@code partnerId}: the partner server it belongs to (website partner page), optional; {@code partner}: who it's for;
	 * {@code active}: off = refused at checkout but kept with its stats; {@code type}: "coupon" or "creator";
	 * {@code tebexCode}: its name in Tebex (null = the same as {@code code}); {@code tebexOk}, {@code percent},
	 * {@code checkedAt}: what the last check against Tebex found.
	 */
	public record Code(String code, String partner, String partnerId, boolean active, String note, long createdAt,
			String type, String tebexCode, Boolean tebexOk, int percent, long checkedAt) {

		/** Players can use it: switched on and Tebex took it at the last check. */
		public boolean usable() {
			return active && Boolean.TRUE.equals(tebexOk);
		}

		public boolean creator() {
			return "creator".equals(type);
		}

		public String tebexName() {
			return tebexCode == null || tebexCode.isBlank() ? code : tebexCode;
		}

		Code withCheck(boolean ok, int percent) {
			return new Code(code, partner, partnerId, active, note, createdAt, type, tebexCode, ok, percent, System.currentTimeMillis());
		}
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Path file;
	private volatile List<Code> list = List.of();

	public PartnerCodes(Path dataDir) throws IOException {
		this.file = dataDir.resolve("partner-codes.json");
		if (Files.exists(file)) {
			List<Code> loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), new TypeToken<List<Code>>() { }.getType());
			list = loaded == null ? List.of() : List.copyOf(loaded);
		}
	}

	public List<Code> all() {
		return list;
	}

	/** Upper case, trimmed; null if it can't be a code at all. */
	public static String normalize(String code) {
		if (code == null) {
			return null;
		}
		String c = code.trim().toUpperCase(Locale.ROOT);
		return c.matches("[A-Z0-9][A-Z0-9_-]{1,23}") ? c : null;
	}

	/** The active code for {@code input} (any case), or null. */
	public Code find(String input) {
		String c = normalize(input);
		if (c == null) {
			return null;
		}
		for (Code code : list) {
			if (code.code().equals(c) && code.active()) {
				return code;
			}
		}
		return null;
	}

	/** The active codes of one partner server (the website lists them on the partner page). */
	public List<java.util.Map<String, Object>> codesOf(String partnerId) {
		List<java.util.Map<String, Object>> out = new ArrayList<>();
		for (Code code : list) {
			if (code.usable() && partnerId != null && partnerId.equals(code.partnerId())) {
				out.add(java.util.Map.of("code", code.code(), "percent", code.percent()));
			}
		}
		return out;
	}

	/**
	 * Adds a code, or changes the one called {@code previous} (its stats move along when it's renamed - the caller
	 * renames them in the database). Returns the stored code.
	 */
	public synchronized Code save(String previous, Code incoming) throws StoreService.StoreException, IOException {
		String code = normalize(incoming == null ? null : incoming.code());
		if (code == null) {
			throw new StoreService.StoreException(400, "A code is 2 to 24 letters, digits, - or _");
		}
		String partner = incoming.partner() == null ? "" : incoming.partner().strip();
		if (partner.isEmpty() || partner.length() > 40) {
			throw new StoreService.StoreException(400, "Say who the code is for (up to 40 characters)");
		}
		String partnerId = incoming.partnerId() != null && incoming.partnerId().matches("[a-z0-9-]{1,40}") ? incoming.partnerId() : null;
		String note = incoming.note() == null ? null : incoming.note().strip();
		if (note != null && (note.isEmpty() || note.length() > 200)) {
			note = note.isEmpty() ? null : note.substring(0, 200);
		}
		String prev = normalize(previous);
		List<Code> next = new ArrayList<>(list);
		long createdAt = System.currentTimeMillis();
		int at = -1;
		for (int i = 0; i < next.size(); i++) {
			if (next.get(i).code().equals(prev)) {
				at = i;
				createdAt = next.get(i).createdAt();
			}
		}
		for (Code other : next) {
			if (other.code().equals(code) && !code.equals(prev)) {
				throw new StoreService.StoreException(409, "The code " + code + " exists already");
			}
		}
		if (at < 0 && next.size() >= MAX) {
			throw new StoreService.StoreException(400, "At most " + MAX + " partner codes");
		}
		String type = "creator".equals(incoming.type()) ? "creator" : "coupon";
		Code clean = new Code(code, partner, partnerId, incoming.active(), note, createdAt, type, tebexName(incoming.tebexCode()), null, 0, 0);
		if (at >= 0) {
			next.set(at, clean);
		} else {
			next.add(clean);
		}
		write(next);
		return clean;
	}

	private static String tebexName(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String v = value.trim();
		return v.matches("[A-Za-z0-9_-]{1,40}") ? v : null;
	}

	/** Stores what the Tebex check found for {@code code}. */
	public synchronized Code recordCheck(String code, boolean ok, int percent) throws IOException {
		List<Code> next = new ArrayList<>(list);
		Code out = null;
		for (int i = 0; i < next.size(); i++) {
			if (next.get(i).code().equals(code)) {
				out = next.get(i).withCheck(ok, percent);
				next.set(i, out);
			}
		}
		if (out != null) {
			write(next);
		}
		return out;
	}

	/** Whether any code can be used right now (the Store shows the code field then). */
	public boolean anyUsable() {
		return list.stream().anyMatch(Code::usable);
	}

	public synchronized boolean delete(String input) throws IOException {
		String c = normalize(input);
		List<Code> next = new ArrayList<>(list);
		if (!next.removeIf(code -> code.code().equals(c))) {
			return false;
		}
		write(next);
		return true;
	}

	private void write(List<Code> next) throws IOException {
		Path temp = Files.createTempFile(file.getParent(), "partner-codes-", ".tmp");
		Files.writeString(temp, GSON.toJson(next), StandardCharsets.UTF_8);
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		list = List.copyOf(next);
	}
}
