package net.veloclient.server.store;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Wallets, owned items, payment orders and the coin ledger in one SQLite file ({@code store.db}).
 *
 * <p>Rules that keep money correct:
 * <ul>
 * <li>Every balance change happens in one transaction together with a row in {@code ledger}
 * (who, how much, why, reference, which admin) - the ledger is append-only, so every coin can be
 * traced, and a wallet's balance always equals the sum of its ledger rows.</li>
 * <li>A payment is credited exactly once: the order moves pending -> paid in the same
 * transaction that credits it, and Tebex webhook ids are remembered so redeliveries are no-ops.</li>
 * <li>One connection, all methods synchronized - SQLite serializes writes anyway, and the store's
 * traffic is tiny.</li>
 * </ul>
 */
public final class StoreDatabase implements AutoCloseable {

	public enum BuyResult { OK, ALREADY_OWNED, INSUFFICIENT_COINS }

	public record Order(String orderId, String uuid, String packId, int coins, double price, String currency,
			String status, String transactionId, long createdAt) {
	}

	public record LedgerEntry(long id, int delta, int balanceAfter, String reason, String ref, String actor, long createdAt) {
	}

	private final Connection connection;

	public StoreDatabase(Path file) throws SQLException {
		connection = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
		try (Statement st = connection.createStatement()) {
			st.execute("PRAGMA journal_mode=WAL");
			st.execute("PRAGMA synchronous=FULL");
			st.execute("PRAGMA foreign_keys=ON");
			st.execute("""
					CREATE TABLE IF NOT EXISTS wallets (
						uuid TEXT PRIMARY KEY,
						username TEXT,
						balance INTEGER NOT NULL DEFAULT 0,
						updated_at INTEGER NOT NULL)""");
			// Velo+ subscriptions: when each member's membership ends, and the payments that extended it
			// (so a refund can take exactly those days back and a renewal can be matched to its member).
			st.execute("""
					CREATE TABLE IF NOT EXISTS plus (
						uuid TEXT PRIMARY KEY,
						until INTEGER NOT NULL,
						source TEXT,
						last_coins INTEGER NOT NULL DEFAULT 0,
						started INTEGER NOT NULL)""");
			st.execute("""
					CREATE TABLE IF NOT EXISTS plus_payments (
						txn TEXT PRIMARY KEY,
						uuid TEXT NOT NULL,
						days INTEGER NOT NULL,
						reference TEXT,
						created_at INTEGER NOT NULL)""");
			st.execute("""
					CREATE TABLE IF NOT EXISTS ledger (
						id INTEGER PRIMARY KEY AUTOINCREMENT,
						uuid TEXT NOT NULL,
						delta INTEGER NOT NULL,
						balance_after INTEGER NOT NULL,
						reason TEXT NOT NULL,
						ref TEXT,
						actor TEXT,
						created_at INTEGER NOT NULL)""");
			st.execute("CREATE INDEX IF NOT EXISTS ledger_uuid ON ledger(uuid, id)");
			st.execute("""
					CREATE TABLE IF NOT EXISTS owned_items (
						uuid TEXT NOT NULL,
						item_id TEXT NOT NULL,
						source TEXT NOT NULL,
						created_at INTEGER NOT NULL,
						PRIMARY KEY (uuid, item_id))""");
			st.execute("""
					CREATE TABLE IF NOT EXISTS orders (
						order_id TEXT PRIMARY KEY,
						uuid TEXT NOT NULL,
						pack_id TEXT NOT NULL,
						coins INTEGER NOT NULL,
						price REAL NOT NULL,
						currency TEXT NOT NULL,
						basket_ident TEXT,
						status TEXT NOT NULL,
						transaction_id TEXT,
						created_at INTEGER NOT NULL,
						updated_at INTEGER NOT NULL)""");
			st.execute("CREATE INDEX IF NOT EXISTS orders_txn ON orders(transaction_id)");
			// Rewards: one row per player per UTC day.
			st.execute("""
					CREATE TABLE IF NOT EXISTS daily (
						uuid TEXT NOT NULL,
						day TEXT NOT NULL,
						online_seconds INTEGER NOT NULL DEFAULT 0,
						last_heartbeat INTEGER NOT NULL DEFAULT 0,
						login_claimed INTEGER NOT NULL DEFAULT 0,
						ads_watched INTEGER NOT NULL DEFAULT 0,
						PRIMARY KEY (uuid, day))""");
			st.execute("""
					CREATE TABLE IF NOT EXISTS daily_stats (
						uuid TEXT NOT NULL,
						day TEXT NOT NULL,
						stat TEXT NOT NULL,
						value INTEGER NOT NULL,
						PRIMARY KEY (uuid, day, stat))""");
			st.execute("""
					CREATE TABLE IF NOT EXISTS quest_claims (
						uuid TEXT NOT NULL,
						day TEXT NOT NULL,
						quest_id TEXT NOT NULL,
						PRIMARY KEY (uuid, day, quest_id))""");
			st.execute("""
					CREATE TABLE IF NOT EXISTS ad_views (
						tid TEXT PRIMARY KEY,
						uuid TEXT NOT NULL,
						day TEXT NOT NULL,
						coins INTEGER NOT NULL,
						created_at INTEGER NOT NULL)""");
			st.execute("""
					CREATE TABLE IF NOT EXISTS webhook_events (
						event_id TEXT PRIMARY KEY,
						type TEXT NOT NULL,
						received_at INTEGER NOT NULL)""");
			// Referrals: everyone's own code, and who invited whom (rewarded once the invited player has played 2 h).
			st.execute("""
					CREATE TABLE IF NOT EXISTS referral_codes (
						uuid TEXT PRIMARY KEY,
						code TEXT NOT NULL UNIQUE,
						created_at INTEGER NOT NULL)""");
			st.execute("""
					CREATE TABLE IF NOT EXISTS referrals (
						referred_uuid TEXT PRIMARY KEY,
						referrer_uuid TEXT NOT NULL,
						created_at INTEGER NOT NULL,
						rewarded_at INTEGER NOT NULL DEFAULT 0,
						referrer_coins INTEGER NOT NULL DEFAULT 0)""");
			st.execute("CREATE INDEX IF NOT EXISTS referrals_referrer ON referrals(referrer_uuid)");
			st.execute("""
					CREATE TABLE IF NOT EXISTS wishlist (
						uuid TEXT NOT NULL,
						item_id TEXT NOT NULL,
						created_at INTEGER NOT NULL,
						PRIMARY KEY (uuid, item_id))""");
		}
	}

	// ---- Wallets ----

	public synchronized void touchWallet(String uuid, String username) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(
				"INSERT INTO wallets(uuid, username, balance, updated_at) VALUES(?, ?, 0, ?) "
						+ "ON CONFLICT(uuid) DO UPDATE SET username = COALESCE(excluded.username, wallets.username)")) {
			ps.setString(1, uuid);
			ps.setString(2, username);
			ps.setLong(3, System.currentTimeMillis());
			ps.executeUpdate();
		}
	}

	// ---- Velo+ ----

	public record PlusRow(String uuid, long until, String source, long lastCoins, long started) {
	}

	public synchronized long plusUntil(String uuid) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("SELECT until FROM plus WHERE uuid = ?")) {
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getLong(1) : 0;
			}
		}
	}

	/**
	 * Adds {@code days} to the membership (from today, or from its current end if that's later) and returns the new end.
	 * {@code txn} (the payment) is remembered: a payment counts once, and its days can be taken back on a refund.
	 */
	public synchronized long extendPlus(String uuid, int days, String source, String txn, String reference) throws SQLException {
		return inTransaction(() -> {
			long now = System.currentTimeMillis();
			if (txn != null) {
				try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM plus_payments WHERE txn = ?")) {
					ps.setString(1, txn);
					try (ResultSet rs = ps.executeQuery()) {
						if (rs.next()) {
							return plusUntil(uuid);
						}
					}
				}
				try (PreparedStatement ps = connection.prepareStatement(
						"INSERT INTO plus_payments(txn, uuid, days, reference, created_at) VALUES(?, ?, ?, ?, ?)")) {
					ps.setString(1, txn);
					ps.setString(2, uuid);
					ps.setInt(3, days);
					ps.setString(4, reference);
					ps.setLong(5, now);
					ps.executeUpdate();
				}
			}
			long current = plusUntil(uuid);
			long until = Math.max(now, current) + days * 86_400_000L;
			try (PreparedStatement ps = connection.prepareStatement(
					"INSERT INTO plus(uuid, until, source, last_coins, started) VALUES(?, ?, ?, 0, ?) "
							+ "ON CONFLICT(uuid) DO UPDATE SET until = excluded.until, source = excluded.source")) {
				ps.setString(1, uuid);
				ps.setLong(2, until);
				ps.setString(3, source);
				ps.setLong(4, now);
				ps.executeUpdate();
			}
			return until;
		});
	}

	/** Ends the membership now (owner revoke). */
	public synchronized void endPlus(String uuid) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("UPDATE plus SET until = ? WHERE uuid = ?")) {
			ps.setLong(1, System.currentTimeMillis());
			ps.setString(2, uuid);
			ps.executeUpdate();
		}
	}

	/** A refunded / charged-back payment: takes its days back. Returns the member's uuid, or null if it wasn't a Velo+ payment. */
	public synchronized String reversePlusPayment(String txn) throws SQLException {
		return inTransaction(() -> {
			String uuid;
			int days;
			try (PreparedStatement ps = connection.prepareStatement("SELECT uuid, days FROM plus_payments WHERE txn = ?")) {
				ps.setString(1, txn);
				try (ResultSet rs = ps.executeQuery()) {
					if (!rs.next()) {
						return null;
					}
					uuid = rs.getString(1);
					days = rs.getInt(2);
				}
			}
			try (PreparedStatement ps = connection.prepareStatement("DELETE FROM plus_payments WHERE txn = ?")) {
				ps.setString(1, txn);
				ps.executeUpdate();
			}
			long until = Math.max(System.currentTimeMillis(), plusUntil(uuid) - days * 86_400_000L);
			try (PreparedStatement ps = connection.prepareStatement("UPDATE plus SET until = ? WHERE uuid = ?")) {
				ps.setLong(1, until);
				ps.setString(2, uuid);
				ps.executeUpdate();
			}
			return uuid;
		});
	}

	/** The member a payment (or its recurring reference) belongs to, or null. */
	public synchronized String plusMemberOfPayment(String txnOrReference) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("SELECT uuid FROM plus_payments WHERE txn = ? OR reference = ? LIMIT 1")) {
			ps.setString(1, txnOrReference);
			ps.setString(2, txnOrReference);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	public synchronized PlusRow plusRow(String uuid) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("SELECT uuid, until, source, last_coins, started FROM plus WHERE uuid = ?")) {
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? new PlusRow(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getLong(5)) : null;
			}
		}
	}

	public synchronized List<PlusRow> activePlus(long now) throws SQLException {
		List<PlusRow> out = new ArrayList<>();
		try (PreparedStatement ps = connection.prepareStatement("SELECT uuid, until, source, last_coins, started FROM plus WHERE until > ?")) {
			ps.setLong(1, now);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new PlusRow(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getLong(4), rs.getLong(5)));
				}
			}
		}
		return out;
	}

	public synchronized void markPlusCoins(String uuid, long at) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("UPDATE plus SET last_coins = ? WHERE uuid = ?")) {
			ps.setLong(1, at);
			ps.setString(2, uuid);
			ps.executeUpdate();
		}
	}

	/** The last username seen for a wallet (null if none). */
	public synchronized String username(String uuid) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("SELECT username FROM wallets WHERE uuid = ?")) {
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	public synchronized int balance(String uuid) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("SELECT balance FROM wallets WHERE uuid = ?")) {
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		}
	}

	/** Everyone who owns {@code itemId} through {@code source} (e.g. the staff cape handed out by the staff sync). */
	public synchronized List<String> ownersOf(String itemId, String source) throws SQLException {
		List<String> out = new ArrayList<>();
		try (PreparedStatement ps = connection.prepareStatement("SELECT uuid FROM owned_items WHERE item_id = ? AND source = ?")) {
			ps.setString(1, itemId);
			ps.setString(2, source);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(rs.getString(1));
				}
			}
		}
		return out;
	}

	public synchronized List<String> ownedItems(String uuid) throws SQLException {
		List<String> out = new ArrayList<>();
		try (PreparedStatement ps = connection.prepareStatement("SELECT item_id FROM owned_items WHERE uuid = ? ORDER BY created_at")) {
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(rs.getString(1));
				}
			}
		}
		return out;
	}

	public synchronized boolean owns(String uuid, String itemId) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("SELECT 1 FROM owned_items WHERE uuid = ? AND item_id = ?")) {
			ps.setString(1, uuid);
			ps.setString(2, itemId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		}
	}

	/** Changes a balance by {@code delta} (may go negative, e.g. after a chargeback); returns the new balance. */
	public synchronized int adjust(String uuid, int delta, String reason, String ref, String actor) throws SQLException {
		return inTransaction(() -> adjustInTx(uuid, delta, reason, ref, actor));
	}

	private int adjustInTx(String uuid, int delta, String reason, String ref, String actor) throws SQLException {
		ensureWalletInTx(uuid);
		int balance;
		try (PreparedStatement ps = connection.prepareStatement("UPDATE wallets SET balance = balance + ?, updated_at = ? WHERE uuid = ?")) {
			ps.setInt(1, delta);
			ps.setLong(2, System.currentTimeMillis());
			ps.setString(3, uuid);
			ps.executeUpdate();
		}
		try (PreparedStatement ps = connection.prepareStatement("SELECT balance FROM wallets WHERE uuid = ?")) {
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				balance = rs.getInt(1);
			}
		}
		try (PreparedStatement ps = connection.prepareStatement(
				"INSERT INTO ledger(uuid, delta, balance_after, reason, ref, actor, created_at) VALUES(?, ?, ?, ?, ?, ?, ?)")) {
			ps.setString(1, uuid);
			ps.setInt(2, delta);
			ps.setInt(3, balance);
			ps.setString(4, reason);
			ps.setString(5, ref);
			ps.setString(6, actor);
			ps.setLong(7, System.currentTimeMillis());
			ps.executeUpdate();
		}
		return balance;
	}

	private void ensureWalletInTx(String uuid) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(
				"INSERT OR IGNORE INTO wallets(uuid, username, balance, updated_at) VALUES(?, NULL, 0, ?)")) {
			ps.setString(1, uuid);
			ps.setLong(2, System.currentTimeMillis());
			ps.executeUpdate();
		}
	}

	/** Spends coins on an item and grants it, atomically. */
	public synchronized BuyResult buyItem(String uuid, String itemId, int price) throws SQLException {
		return inTransaction(() -> {
			if (owns(uuid, itemId)) {
				return BuyResult.ALREADY_OWNED;
			}
			ensureWalletInTx(uuid);
			if (balance(uuid) < price) {
				return BuyResult.INSUFFICIENT_COINS;
			}
			adjustInTx(uuid, -price, "buy_item", itemId, null);
			insertOwned(uuid, itemId, "purchase");
			return BuyResult.OK;
		});
	}

	public synchronized boolean grantItem(String uuid, String itemId, String actor) throws SQLException {
		return inTransaction(() -> {
			if (owns(uuid, itemId)) {
				return false;
			}
			insertOwned(uuid, itemId, actor == null ? "grant" : "grant:" + actor);
			adjustInTx(uuid, 0, "grant_item", itemId, actor);
			return true;
		});
	}

	public synchronized boolean revokeItem(String uuid, String itemId, String actor) throws SQLException {
		return inTransaction(() -> {
			int removed;
			try (PreparedStatement ps = connection.prepareStatement("DELETE FROM owned_items WHERE uuid = ? AND item_id = ?")) {
				ps.setString(1, uuid);
				ps.setString(2, itemId);
				removed = ps.executeUpdate();
			}
			if (removed > 0) {
				adjustInTx(uuid, 0, "revoke_item", itemId, actor);
			}
			return removed > 0;
		});
	}

	private void insertOwned(String uuid, String itemId, String source) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(
				"INSERT INTO owned_items(uuid, item_id, source, created_at) VALUES(?, ?, ?, ?)")) {
			ps.setString(1, uuid);
			ps.setString(2, itemId);
			ps.setString(3, source);
			ps.setLong(4, System.currentTimeMillis());
			ps.executeUpdate();
		}
	}

	public synchronized List<LedgerEntry> ledger(String uuid, int limit) throws SQLException {
		List<LedgerEntry> out = new ArrayList<>();
		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT id, delta, balance_after, reason, ref, actor, created_at FROM ledger WHERE uuid = ? ORDER BY id DESC LIMIT ?")) {
			ps.setString(1, uuid);
			ps.setInt(2, limit);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(new LedgerEntry(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getString(4), rs.getString(5),
							rs.getString(6), rs.getLong(7)));
				}
			}
		}
		return out;
	}

	// ---- Orders (real-money coin purchases) ----

	public synchronized void createOrder(String orderId, String uuid, StoreConfig.CoinPack pack, String currency) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(
				"INSERT INTO orders(order_id, uuid, pack_id, coins, price, currency, status, created_at, updated_at) "
						+ "VALUES(?, ?, ?, ?, ?, ?, 'pending', ?, ?)")) {
			long now = System.currentTimeMillis();
			ps.setString(1, orderId);
			ps.setString(2, uuid);
			ps.setString(3, pack.id());
			ps.setInt(4, pack.totalCoins());
			ps.setDouble(5, pack.price());
			ps.setString(6, currency);
			ps.setLong(7, now);
			ps.setLong(8, now);
			ps.executeUpdate();
		}
	}

	public synchronized void setBasket(String orderId, String basketIdent) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("UPDATE orders SET basket_ident = ?, updated_at = ? WHERE order_id = ?")) {
			ps.setString(1, basketIdent);
			ps.setLong(2, System.currentTimeMillis());
			ps.setString(3, orderId);
			ps.executeUpdate();
		}
	}

	public synchronized Order order(String orderId) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT order_id, uuid, pack_id, coins, price, currency, status, transaction_id, created_at FROM orders WHERE order_id = ?")) {
			ps.setString(1, orderId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? readOrder(rs) : null;
			}
		}
	}

	private static Order readOrder(ResultSet rs) throws SQLException {
		return new Order(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getDouble(5), rs.getString(6),
				rs.getString(7), rs.getString(8), rs.getLong(9));
	}

	/** pending -> paid and credit its coins, once. Returns false if it wasn't pending. */
	public synchronized boolean completeOrder(String orderId, String transactionId) throws SQLException {
		return inTransaction(() -> {
			Order order = order(orderId);
			if (order == null || !order.status().equals("pending")) {
				return false;
			}
			setStatus(orderId, "paid", transactionId);
			adjustInTx(order.uuid(), order.coins(), "purchase_coins", transactionId, "tebex");
			return true;
		});
	}

	public synchronized void flagOrder(String orderId, String status, String transactionId) throws SQLException {
		setStatus(orderId, status, transactionId);
	}

	/** A paid order was refunded or charged back: take its coins back (the balance may go negative). */
	public synchronized int reverseByTransaction(String transactionId, String newStatus) throws SQLException {
		return inTransaction(() -> {
			List<Order> paid = new ArrayList<>();
			try (PreparedStatement ps = connection.prepareStatement(
					"SELECT order_id, uuid, pack_id, coins, price, currency, status, transaction_id, created_at FROM orders "
							+ "WHERE transaction_id = ? AND status = 'paid'")) {
				ps.setString(1, transactionId);
				try (ResultSet rs = ps.executeQuery()) {
					while (rs.next()) {
						paid.add(readOrder(rs));
					}
				}
			}
			for (Order order : paid) {
				setStatus(order.orderId(), newStatus, transactionId);
				adjustInTx(order.uuid(), -order.coins(), newStatus, transactionId, "tebex");
			}
			return paid.size();
		});
	}

	private void setStatus(String orderId, String status, String transactionId) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(
				"UPDATE orders SET status = ?, transaction_id = COALESCE(?, transaction_id), updated_at = ? WHERE order_id = ?")) {
			ps.setString(1, status);
			ps.setString(2, transactionId);
			ps.setLong(3, System.currentTimeMillis());
			ps.setString(4, orderId);
			ps.executeUpdate();
		}
	}

	/** Remembers a webhook delivery; false if it was already processed (Tebex retries deliveries). */
	public synchronized boolean firstDelivery(String eventId, String type) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(
				"INSERT OR IGNORE INTO webhook_events(event_id, type, received_at) VALUES(?, ?, ?)")) {
			ps.setString(1, eventId);
			ps.setString(2, type);
			ps.setLong(3, System.currentTimeMillis());
			return ps.executeUpdate() == 1;
		}
	}

	public synchronized void forgetDelivery(String eventId) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("DELETE FROM webhook_events WHERE event_id = ?")) {
			ps.setString(1, eventId);
			ps.executeUpdate();
		}
	}

	// ---- Rewards ----

	public record Daily(long onlineSeconds, boolean loginClaimed, int adsWatched) {
	}

	private void ensureDaily(String uuid, String day) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("INSERT OR IGNORE INTO daily(uuid, day) VALUES(?, ?)")) {
			ps.setString(1, uuid);
			ps.setString(2, day);
			ps.executeUpdate();
		}
	}

	public synchronized Daily daily(String uuid, String day) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT online_seconds, login_claimed, ads_watched FROM daily WHERE uuid = ? AND day = ?")) {
			ps.setString(1, uuid);
			ps.setString(2, day);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? new Daily(rs.getLong(1), rs.getInt(2) == 1, rs.getInt(3)) : new Daily(0, false, 0);
			}
		}
	}

	/**
	 * Counts in-game time from game-session heartbeats: the gap since the previous heartbeat is
	 * added if it's short (the player was continuously online), capped so a stalled client can't
	 * claim a long gap.
	 */
	public synchronized void recordHeartbeat(String uuid, String day, long now, long maxGapMillis) throws SQLException {
		ensureDaily(uuid, day);
		long last;
		try (PreparedStatement ps = connection.prepareStatement("SELECT last_heartbeat FROM daily WHERE uuid = ? AND day = ?")) {
			ps.setString(1, uuid);
			ps.setString(2, day);
			try (ResultSet rs = ps.executeQuery()) {
				last = rs.next() ? rs.getLong(1) : 0;
			}
		}
		long gap = last == 0 ? 0 : now - last;
		long add = gap > 0 && gap <= maxGapMillis ? gap / 1000 : 0;
		try (PreparedStatement ps = connection.prepareStatement(
				"UPDATE daily SET online_seconds = online_seconds + ?, last_heartbeat = ? WHERE uuid = ? AND day = ?")) {
			ps.setLong(1, add);
			ps.setLong(2, now);
			ps.setString(3, uuid);
			ps.setString(4, day);
			ps.executeUpdate();
		}
	}

	public synchronized Map<String, Long> stats(String uuid, String day) throws SQLException {
		Map<String, Long> out = new java.util.HashMap<>();
		try (PreparedStatement ps = connection.prepareStatement("SELECT stat, value FROM daily_stats WHERE uuid = ? AND day = ?")) {
			ps.setString(1, uuid);
			ps.setString(2, day);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.put(rs.getString(1), rs.getLong(2));
				}
			}
		}
		return out;
	}

	public synchronized void addStat(String uuid, String day, String stat, long amount) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(
				"INSERT INTO daily_stats(uuid, day, stat, value) VALUES(?, ?, ?, ?) "
						+ "ON CONFLICT(uuid, day, stat) DO UPDATE SET value = value + excluded.value")) {
			ps.setString(1, uuid);
			ps.setString(2, day);
			ps.setString(3, stat);
			ps.setLong(4, amount);
			ps.executeUpdate();
		}
	}

	public synchronized java.util.Set<String> claimedQuests(String uuid, String day) throws SQLException {
		java.util.Set<String> out = new java.util.HashSet<>();
		try (PreparedStatement ps = connection.prepareStatement("SELECT quest_id FROM quest_claims WHERE uuid = ? AND day = ?")) {
			ps.setString(1, uuid);
			ps.setString(2, day);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(rs.getString(1));
				}
			}
		}
		return out;
	}

	/** Pays a quest once per day; false if already claimed. */
	public synchronized boolean claimQuest(String uuid, String day, String questId, int coins) throws SQLException {
		return inTransaction(() -> {
			try (PreparedStatement ps = connection.prepareStatement(
					"INSERT OR IGNORE INTO quest_claims(uuid, day, quest_id) VALUES(?, ?, ?)")) {
				ps.setString(1, uuid);
				ps.setString(2, day);
				ps.setString(3, questId);
				if (ps.executeUpdate() == 0) {
					return false;
				}
			}
			adjustInTx(uuid, coins, "quest", day + ":" + questId, null);
			return true;
		});
	}

	/** Pays the daily login reward once per day; false if already claimed. */
	public synchronized boolean claimLogin(String uuid, String day, int coins) throws SQLException {
		return inTransaction(() -> {
			ensureDaily(uuid, day);
			try (PreparedStatement ps = connection.prepareStatement(
					"UPDATE daily SET login_claimed = 1 WHERE uuid = ? AND day = ? AND login_claimed = 0")) {
				ps.setString(1, uuid);
				ps.setString(2, day);
				if (ps.executeUpdate() == 0) {
					return false;
				}
			}
			adjustInTx(uuid, coins, "daily_login", day, null);
			return true;
		});
	}

	/** Consecutive days (ending yesterday) on which the login reward was claimed. */
	public synchronized int loginStreakBefore(String uuid, String today) throws SQLException {
		int streak = 0;
		java.time.LocalDate day = java.time.LocalDate.parse(today).minusDays(1);
		while (streak < 1000) {
			try (PreparedStatement ps = connection.prepareStatement(
					"SELECT login_claimed FROM daily WHERE uuid = ? AND day = ?")) {
				ps.setString(1, uuid);
				ps.setString(2, day.toString());
				try (ResultSet rs = ps.executeQuery()) {
					if (!rs.next() || rs.getInt(1) != 1) {
						break;
					}
				}
			}
			streak++;
			day = day.minusDays(1);
		}
		return streak;
	}

	public enum AdResult { CREDITED, DUPLICATE, CAPPED }

	/** One verified ad view: credits once per transaction id, at most {@code dailyCap} per day. */
	public synchronized AdResult creditAd(String tid, String uuid, String day, int coins, int dailyCap) throws SQLException {
		return inTransaction(() -> {
			ensureDaily(uuid, day);
			if (daily(uuid, day).adsWatched() >= dailyCap) {
				return AdResult.CAPPED;
			}
			try (PreparedStatement ps = connection.prepareStatement(
					"INSERT OR IGNORE INTO ad_views(tid, uuid, day, coins, created_at) VALUES(?, ?, ?, ?, ?)")) {
				ps.setString(1, tid);
				ps.setString(2, uuid);
				ps.setString(3, day);
				ps.setInt(4, coins);
				ps.setLong(5, System.currentTimeMillis());
				if (ps.executeUpdate() == 0) {
					return AdResult.DUPLICATE;
				}
			}
			try (PreparedStatement ps = connection.prepareStatement(
					"UPDATE daily SET ads_watched = ads_watched + 1 WHERE uuid = ? AND day = ?")) {
				ps.setString(1, uuid);
				ps.setString(2, day);
				ps.executeUpdate();
			}
			adjustInTx(uuid, coins, "ad_reward", tid, "rewarded_ad");
			return AdResult.CREDITED;
		});
	}

	// ---- Bundles & gifts ----

	/** Buys every item of {@code itemIds} the player doesn't own yet for one price, atomically. */
	public synchronized BuyResult buyItems(String uuid, List<String> itemIds, int price, String ref) throws SQLException {
		return inTransaction(() -> {
			List<String> missing = new ArrayList<>();
			for (String id : itemIds) {
				if (!owns(uuid, id)) {
					missing.add(id);
				}
			}
			if (missing.isEmpty()) {
				return BuyResult.ALREADY_OWNED;
			}
			ensureWalletInTx(uuid);
			if (balance(uuid) < price) {
				return BuyResult.INSUFFICIENT_COINS;
			}
			adjustInTx(uuid, -price, "buy_bundle", ref, null);
			for (String id : missing) {
				insertOwned(uuid, id, "purchase:" + ref);
			}
			return BuyResult.OK;
		});
	}

	/** {@code giver} pays for an item that goes straight to {@code recipient}'s Locker. */
	public synchronized BuyResult giftItem(String giver, String recipient, String itemId, int price) throws SQLException {
		return inTransaction(() -> {
			if (owns(recipient, itemId)) {
				return BuyResult.ALREADY_OWNED;
			}
			ensureWalletInTx(giver);
			if (balance(giver) < price) {
				return BuyResult.INSUFFICIENT_COINS;
			}
			adjustInTx(giver, -price, "gift_item", itemId + ":" + recipient, null);
			ensureWalletInTx(recipient);
			insertOwned(recipient, itemId, "gift:" + giver);
			adjustInTx(recipient, 0, "gift_received", itemId, giver);
			return BuyResult.OK;
		});
	}

	/** The uuid of the player who last used this name with Velo Client, or null. */
	public synchronized String uuidByUsername(String username) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT uuid FROM wallets WHERE username = ? COLLATE NOCASE ORDER BY updated_at DESC LIMIT 1")) {
			ps.setString(1, username);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	// ---- Wishlist ----

	public synchronized void setWishlisted(String uuid, String itemId, boolean on) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(on
				? "INSERT OR IGNORE INTO wishlist(uuid, item_id, created_at) VALUES(?, ?, ?)"
				: "DELETE FROM wishlist WHERE uuid = ? AND item_id = ?")) {
			ps.setString(1, uuid);
			ps.setString(2, itemId);
			if (on) {
				ps.setLong(3, System.currentTimeMillis());
			}
			ps.executeUpdate();
		}
	}

	public synchronized List<String> wishlist(String uuid) throws SQLException {
		List<String> out = new ArrayList<>();
		try (PreparedStatement ps = connection.prepareStatement("SELECT item_id FROM wishlist WHERE uuid = ? ORDER BY created_at")) {
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(rs.getString(1));
				}
			}
		}
		return out;
	}

	// ---- Referrals ----

	public record Referral(String referred, String referrer, long createdAt, long rewardedAt, int referrerCoins) {
	}

	public synchronized String referralCode(String uuid) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("SELECT code FROM referral_codes WHERE uuid = ?")) {
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	/** Stores {@code code} as the player's code; false when another player already has it. */
	public synchronized boolean setReferralCode(String uuid, String code) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("INSERT OR IGNORE INTO referral_codes(uuid, code, created_at) VALUES(?, ?, ?)")) {
			ps.setString(1, uuid);
			ps.setString(2, code);
			ps.setLong(3, System.currentTimeMillis());
			return ps.executeUpdate() == 1;
		}
	}

	public synchronized String uuidForReferralCode(String code) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("SELECT uuid FROM referral_codes WHERE code = ?")) {
			ps.setString(1, code);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	private static Referral referral(ResultSet rs) throws SQLException {
		return new Referral(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getInt(5));
	}

	/** Who invited {@code uuid}, or null. */
	public synchronized Referral referredBy(String uuid) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT referred_uuid, referrer_uuid, created_at, rewarded_at, referrer_coins FROM referrals WHERE referred_uuid = ?")) {
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? referral(rs) : null;
			}
		}
	}

	/** Everyone {@code referrer} invited, newest first. */
	public synchronized List<Referral> referralsBy(String referrer) throws SQLException {
		List<Referral> out = new ArrayList<>();
		try (PreparedStatement ps = connection.prepareStatement(
				"SELECT referred_uuid, referrer_uuid, created_at, rewarded_at, referrer_coins FROM referrals WHERE referrer_uuid = ? ORDER BY created_at DESC")) {
			ps.setString(1, referrer);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(referral(rs));
				}
			}
		}
		return out;
	}

	/** Invited players still waiting for their reward. */
	public synchronized List<String> pendingReferrals() throws SQLException {
		List<String> out = new ArrayList<>();
		try (Statement st = connection.createStatement();
				ResultSet rs = st.executeQuery("SELECT referred_uuid FROM referrals WHERE rewarded_at = 0")) {
			while (rs.next()) {
				out.add(rs.getString(1));
			}
		}
		return out;
	}

	/** Records the invite; false when the player already has a referrer. */
	public synchronized boolean addReferral(String referred, String referrer) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement(
				"INSERT OR IGNORE INTO referrals(referred_uuid, referrer_uuid, created_at) VALUES(?, ?, ?)")) {
			ps.setString(1, referred);
			ps.setString(2, referrer);
			ps.setLong(3, System.currentTimeMillis());
			return ps.executeUpdate() == 1;
		}
	}

	/**
	 * Pays both sides of an invite once: {@code referredCoins} to the invited player and the referrer's ladder
	 * amount ({@code ladder} gets how many invites the referrer was already paid for). False if already paid.
	 */
	public synchronized boolean rewardReferral(String referred, int referredCoins, java.util.function.IntUnaryOperator ladder) throws SQLException {
		return inTransaction(() -> {
			Referral row = referredBy(referred);
			if (row == null || row.rewardedAt() != 0) {
				return false;
			}
			int paidBefore;
			try (PreparedStatement ps = connection.prepareStatement("SELECT COUNT(*) FROM referrals WHERE referrer_uuid = ? AND rewarded_at > 0")) {
				ps.setString(1, row.referrer());
				try (ResultSet rs = ps.executeQuery()) {
					rs.next();
					paidBefore = rs.getInt(1);
				}
			}
			int referrerCoins = ladder.applyAsInt(paidBefore);
			try (PreparedStatement ps = connection.prepareStatement(
					"UPDATE referrals SET rewarded_at = ?, referrer_coins = ? WHERE referred_uuid = ? AND rewarded_at = 0")) {
				ps.setLong(1, System.currentTimeMillis());
				ps.setInt(2, referrerCoins);
				ps.setString(3, referred);
				if (ps.executeUpdate() != 1) {
					return false;
				}
			}
			adjustInTx(referred, referredCoins, "referral_joined", row.referrer(), null);
			if (referrerCoins > 0) {
				adjustInTx(row.referrer(), referrerCoins, "referral_invite", referred, null);
			}
			return true;
		});
	}

	/** In-game seconds over all days. */
	public synchronized long totalOnlineSeconds(String uuid) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("SELECT COALESCE(SUM(online_seconds), 0) FROM daily WHERE uuid = ?")) {
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getLong(1) : 0;
			}
		}
	}

	/** The first UTC day the player was seen in-game or claimed anything ("yyyy-mm-dd"), or null. */
	public synchronized String firstDay(String uuid) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("SELECT MIN(day) FROM daily WHERE uuid = ?")) {
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
	}

	// ---- Owner panel ----

	/**
	 * Money and Store numbers for the owner panel: paid coin orders (revenue), coins spent on items, item sales,
	 * Velo+ members, plus the latest orders and item purchases. {@code since} limits the per-day series.
	 */
	public synchronized Map<String, Object> panelSummary(long since, long now, java.util.Set<String> excludeFromWallets) throws SQLException {
		Map<String, Object> out = new java.util.LinkedHashMap<>();
		try (Statement st = connection.createStatement()) {
			try (ResultSet rs = st.executeQuery("SELECT COUNT(*), COALESCE(SUM(price), 0), COALESCE(SUM(coins), 0) FROM orders WHERE status = 'paid'")) {
				rs.next();
				out.put("paidOrders", rs.getLong(1));
				out.put("revenue", rs.getDouble(2));
				out.put("coinsSold", rs.getLong(3));
			}
			try (ResultSet rs = st.executeQuery("SELECT COUNT(*), COALESCE(SUM(price), 0) FROM orders WHERE status = 'paid' AND created_at >= " + since)) {
				rs.next();
				out.put("paidOrdersInRange", rs.getLong(1));
				out.put("revenueInRange", rs.getDouble(2));
			}
			try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM orders WHERE status IN ('refunded', 'chargeback')")) {
				rs.next();
				out.put("reversedOrders", rs.getLong(1));
			}
			try (ResultSet rs = st.executeQuery("SELECT COUNT(*), COALESCE(-SUM(delta), 0) FROM ledger WHERE reason IN ('buy_item', 'buy_bundle', 'gift_item')")) {
				rs.next();
				out.put("itemPurchases", rs.getLong(1));
				out.put("coinsSpent", rs.getLong(2));
			}
			// Players' coins only: the owners' own test wallets would dwarf everyone else's.
			long coins = 0;
			long wallets = 0;
			try (ResultSet rs = st.executeQuery("SELECT uuid, balance FROM wallets")) {
				while (rs.next()) {
					if (!excludeFromWallets.contains(rs.getString(1))) {
						coins += rs.getLong(2);
						wallets++;
					}
				}
			}
			out.put("coinsInCirculation", coins);
			out.put("wallets", wallets);
			try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM plus WHERE until > " + now)) {
				rs.next();
				out.put("plusMembers", rs.getLong(1));
			}
			List<Map<String, Object>> top = new ArrayList<>();
			try (ResultSet rs = st.executeQuery("SELECT item_id, COUNT(*) FROM owned_items WHERE source LIKE 'purchase%' OR source LIKE 'gift%' "
					+ "GROUP BY item_id ORDER BY 2 DESC LIMIT 10")) {
				while (rs.next()) {
					top.add(Map.of("itemId", rs.getString(1), "sales", rs.getLong(2)));
				}
			}
			out.put("topItems", top);
			// Per day: revenue (paid orders) and coins spent in the Store.
			Map<String, double[]> days = new java.util.TreeMap<>();
			try (ResultSet rs = st.executeQuery("SELECT date(created_at / 1000, 'unixepoch'), SUM(price) FROM orders WHERE status = 'paid' AND created_at >= "
					+ since + " GROUP BY 1")) {
				while (rs.next()) {
					days.computeIfAbsent(rs.getString(1), k -> new double[2])[0] = rs.getDouble(2);
				}
			}
			try (ResultSet rs = st.executeQuery("SELECT date(created_at / 1000, 'unixepoch'), -SUM(delta) FROM ledger WHERE reason IN ('buy_item', 'buy_bundle', 'gift_item') "
					+ "AND created_at >= " + since + " GROUP BY 1")) {
				while (rs.next()) {
					days.computeIfAbsent(rs.getString(1), k -> new double[2])[1] = rs.getDouble(2);
				}
			}
			List<Map<String, Object>> series = new ArrayList<>();
			days.forEach((day, v) -> series.add(Map.of("day", day, "revenue", v[0], "coinsSpent", (long) v[1])));
			out.put("daily", series);
		}
		List<Map<String, Object>> orders = new ArrayList<>();
		try (PreparedStatement ps = connection.prepareStatement("SELECT o.order_id, o.uuid, w.username, o.pack_id, o.coins, o.price, o.currency, o.status, o.created_at "
				+ "FROM orders o LEFT JOIN wallets w ON w.uuid = o.uuid ORDER BY o.created_at DESC LIMIT 50");
				ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				Map<String, Object> row = new java.util.LinkedHashMap<>();
				row.put("orderId", rs.getString(1));
				row.put("uuid", rs.getString(2));
				row.put("username", rs.getString(3));
				row.put("packId", rs.getString(4));
				row.put("coins", rs.getInt(5));
				row.put("price", rs.getDouble(6));
				row.put("currency", rs.getString(7));
				row.put("status", rs.getString(8));
				row.put("createdAt", rs.getLong(9));
				orders.add(row);
			}
		}
		out.put("recentOrders", orders);
		List<Map<String, Object>> purchases = new ArrayList<>();
		try (PreparedStatement ps = connection.prepareStatement("SELECT l.uuid, w.username, l.reason, l.ref, l.delta, l.created_at FROM ledger l "
				+ "LEFT JOIN wallets w ON w.uuid = l.uuid WHERE l.reason IN ('buy_item', 'buy_bundle', 'gift_item', 'purchase_coins') ORDER BY l.id DESC LIMIT 50");
				ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				Map<String, Object> row = new java.util.LinkedHashMap<>();
				row.put("uuid", rs.getString(1));
				row.put("username", rs.getString(2));
				row.put("reason", rs.getString(3));
				row.put("ref", rs.getString(4));
				row.put("coins", rs.getInt(5));
				row.put("createdAt", rs.getLong(6));
				purchases.add(row);
			}
		}
		out.put("recentPurchases", purchases);
		return out;
	}

	// ---- Transactions ----

	@FunctionalInterface
	private interface TxBody<T> {
		T run() throws SQLException;
	}

	private <T> T inTransaction(TxBody<T> body) throws SQLException {
		boolean outer = connection.getAutoCommit();
		if (!outer) {
			return body.run(); // already inside one
		}
		connection.setAutoCommit(false);
		try {
			T result = body.run();
			connection.commit();
			return result;
		} catch (SQLException | RuntimeException e) {
			connection.rollback();
			throw e;
		} finally {
			connection.setAutoCommit(true);
		}
	}

	@Override
	public synchronized void close() throws SQLException {
		connection.close();
	}
}
