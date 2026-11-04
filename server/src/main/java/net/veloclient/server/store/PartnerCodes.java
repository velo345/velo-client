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
 * Partner (affiliate) codes: a player who enters one pays {@link #PERCENT}% less for a Velo Coins pack, and the
 * purchase counts for the partner. Never for Velo+ subscriptions.
 *
 * <p>The codes are ours alone (owner panel: add, change, delete) and kept in {@code <data>/partner-codes.json}. The
 * discount itself is one Tebex coupon ({@code TEBEX_PARTNER_COUPON}, 10% on the Velo Coins category, unlimited, never
 * expiring) that the server puts on the basket whenever a valid code is used - so a new partner code needs no Tebex
 * change. Which code an order used is kept in the store database for the partner stats.
 */
public final class PartnerCodes {

	/** What a code takes off a coin pack. The Tebex coupon gives the same. */
	public static final int PERCENT = 10;
	static final int MAX = 200;

	/**
	 * {@code partnerId}: the partner server it belongs to (shown on the website's partner page), optional;
	 * {@code partner}: who it's for (display name); {@code active}: off = refused at checkout but kept with its stats.
	 */
	public record Code(String code, String partner, String partnerId, boolean active, String note, long createdAt) {
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
	public List<String> codesOf(String partnerId) {
		List<String> out = new ArrayList<>();
		for (Code code : list) {
			if (code.active() && partnerId != null && partnerId.equals(code.partnerId())) {
				out.add(code.code());
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
		Code clean = new Code(code, partner, partnerId, incoming.active(), note, createdAt);
		if (at >= 0) {
			next.set(at, clean);
		} else {
			next.add(clean);
		}
		write(next);
		return clean;
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
