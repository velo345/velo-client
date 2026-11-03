package net.veloclient.launcher;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Online or offline, for the whole launcher. Anything that fails on the network reports it here; while offline a
 * quiet check every 15 s (a plain connect to Modrinth / Mojang / Velo) notices when the connection is back. The top
 * banner and the sign-in follow it: offline the saved account is kept and the game still starts (singleplayer, LAN)
 * instead of the account being dropped as if its sign-in had been rejected.
 */
public final class Connectivity {

	private static final String[][] PROBES = {{"api.modrinth.com", "443"}, {"sessionserver.mojang.com", "443"}, {"api.velo-client.com", "443"}};
	private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
	private static volatile boolean offline;
	private static volatile Thread watcher;

	private Connectivity() {
	}

	public static boolean offline() {
		return offline;
	}

	/** Called (on any thread) whenever online / offline flips. */
	public static void onChange(Runnable listener) {
		LISTENERS.add(listener);
	}

	/** Something failed: if it was the network, the launcher is offline until a check gets through again. */
	public static void reportFailure(Throwable error) {
		if (isNetwork(error)) {
			set(true);
		}
	}

	public static void reportSuccess() {
		set(false);
	}

	/** Checks right now (blocking, a few seconds at most) and returns whether anything answered. */
	public static boolean checkNow() {
		for (String[] probe : PROBES) {
			try (Socket socket = new Socket()) {
				socket.connect(new InetSocketAddress(probe[0], Integer.parseInt(probe[1])), 3000);
				set(false);
				return true;
			} catch (IOException | RuntimeException ignored) {
				// Try the next one.
			}
		}
		set(true);
		return false;
	}

	/** True for "no connection / host unreachable / timed out", false for real answers (a rejected sign-in...). */
	public static boolean isNetwork(Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause()) {
			if (t instanceof java.net.UnknownHostException || t instanceof java.net.ConnectException || t instanceof java.net.NoRouteToHostException
					|| t instanceof java.net.SocketTimeoutException || t instanceof java.net.http.HttpTimeoutException
					|| t instanceof java.net.http.HttpConnectTimeoutException || t instanceof java.nio.channels.UnresolvedAddressException) {
				return true;
			}
			String message = t.getMessage();
			if (message != null && (message.startsWith("Network error talking to") || message.contains("Connection reset")
					|| message.contains("Network is unreachable"))) {
				return true;
			}
			if (t.getCause() == t) {
				break;
			}
		}
		return false;
	}

	/**
	 * A sign-in failure that says nothing about the account: no connection, or the service is down ("HTTP 503 from
	 * https://...") - the saved account stays. Only a real rejection signs it out.
	 */
	/**
	 * True only when Microsoft itself refused the saved refresh token (expired, revoked, password changed): then the
	 * account really has to sign in again. Everything else - rate limits, outages, a hiccup in the Xbox / Mojang steps -
	 * keeps the account and retries later instead of signing the player out.
	 */
	public static boolean accountDead(Throwable error) {
		for (Throwable t = error; t != null && t.getCause() != t; t = t.getCause()) {
			String m = t.getMessage();
			if (m != null && m.contains("Refresh token rejected")
					&& (m.contains("invalid_grant") || m.contains("interaction_required") || m.contains("consent_required"))) {
				return true;
			}
		}
		return false;
	}

	public static boolean keepAccount(Throwable error) {
		if (isNetwork(error)) {
			return true;
		}
		for (Throwable t = error; t != null && t.getCause() != t; t = t.getCause()) {
			// 5xx: the service is down; 429: Mojang / Xbox rate-limited us for a moment. Neither means the account is bad -
			// dropping it there signed people out for nothing (and the fresh sign-in then hit the same limit).
			if (t.getMessage() != null && t.getMessage().matches("(?s)HTTP (5\\d\\d|429) from .*")) {
				return true;
			}
		}
		return false;
	}

	private static void set(boolean nowOffline) {
		if (offline == nowOffline) {
			return;
		}
		offline = nowOffline;
		LauncherLog.info(nowOffline ? "Connectivity: offline" : "Connectivity: back online");
		if (nowOffline) {
			startWatcher();
		}
		LISTENERS.forEach(l -> {
			try {
				l.run();
			} catch (RuntimeException e) {
				LauncherLog.info("Connectivity listener: " + e);
			}
		});
	}

	private static synchronized void startWatcher() {
		if (watcher != null && watcher.isAlive()) {
			return;
		}
		watcher = Thread.ofVirtual().name("velo-connectivity").start(() -> {
			while (offline) {
				try {
					Thread.sleep(15_000);
				} catch (InterruptedException e) {
					return;
				}
				checkNow();
			}
		});
	}
}
