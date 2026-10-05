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

	public synchronized int balance(String uuid) throws SQLException {
		try (PreparedStatement ps = connection.prepareStatement("SELECT balance FROM wallets WHERE uuid = ?")) {
			ps.setString(1, uuid);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getInt(1) : 0;
			}
		}
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
