package net.veloclient.library;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Java Edition server list ping (the real handshake + status + ping a client sends - never a bare TCP probe, which
 * anti-bot systems punish). One answer per address is reused for 60 s, so the Library can't be used to flood a server;
 * addresses that resolve to private or local networks are refused.
 */
final class ServerPing {

	static final Pattern ADDRESS = Pattern.compile("^[a-zA-Z0-9.-]{1,253}(:\\d{1,5})?$");

	record Result(String host, int port, String ip, boolean online, long pingMs, String version, int protocol, int online_players, int max,
			List<String> sample, List<Text.Run> motd, byte[] favicon, String error, long at) {
	}

	private final Cache.Memo<Result> results = new Cache.Memo<>(5000);

	/** Addresses someone looked at in the last day: sampled every 5 minutes for the player graph. */
	private final LinkedHashMap<String, Long> watched = new LinkedHashMap<>(64, 0.75f, true);
	private final Map<String, List<int[]>> history = new java.util.concurrent.ConcurrentHashMap<>();

	Result ping(String address) throws IOException {
		String a = address.trim().toLowerCase(java.util.Locale.ROOT);
		if (!ADDRESS.matcher(a).matches() || !a.contains(".")) {
			throw new Http.Fail(400, "That isn't a server address");
		}
		Result cached = results.get(a);
		if (cached != null) {
			watch(a);
			return cached;
		}
		Result r = fresh(a);
		results.put(a, r, r.online() ? 60_000 : 30_000);
		watch(a);
		record(a, r);
		return r;
	}

	private synchronized void watch(String a) {
		watched.put(a, System.currentTimeMillis());
		while (watched.size() > 300) {
			watched.remove(watched.keySet().iterator().next());
		}
	}

	private void record(String a, Result r) {
		List<int[]> h = history.computeIfAbsent(a, k -> new ArrayList<>());
		synchronized (h) {
			int minute = (int) (System.currentTimeMillis() / 60_000);
			if (!h.isEmpty() && minute - h.get(h.size() - 1)[0] < 4) {
				return;
			}
			h.add(new int[] {minute, r.online() ? r.online_players() : -1});
			while (h.size() > 300 || (!h.isEmpty() && minute - h.get(0)[0] > 24 * 60)) {
				h.remove(0);
			}
		}
	}

	List<int[]> history(String a) {
		List<int[]> h = history.get(a.trim().toLowerCase(java.util.Locale.ROOT));
		if (h == null) {
			return List.of();
		}
		synchronized (h) {
			return new ArrayList<>(h);
		}
	}

	/** Every 5 minutes: refresh the watched addresses (dropping ones nobody looked at for a day). */
	void sample(Guard guard) {
		List<String> todo;
		synchronized (this) {
			long cutoff = System.currentTimeMillis() - 24 * 3_600_000L;
			watched.values().removeIf(t -> t < cutoff);
			todo = new ArrayList<>(watched.keySet());
		}
		for (String a : todo) {
			try {
				guard.checkOpen(Guard.Category.PING);
				Result r = fresh(a);
				results.put(a, r, 60_000);
				record(a, r);
				Thread.sleep(400);
			} catch (Exception e) {
				return; // busy or paused: try again next round
			}
		}
		history.keySet().removeIf(k -> !watched.containsKey(k));
	}

	static Result fresh(String address) throws IOException {
		String host = address;
		int port = 25565;
		int colon = address.lastIndexOf(':');
		if (colon > 0) {
			host = address.substring(0, colon);
			port = Integer.parseInt(address.substring(colon + 1));
			if (port < 1 || port > 65535) {
				throw new Http.Fail(400, "Port out of range");
			}
		} else {
			String[] srv = srv(host);
			if (srv != null) {
				host = srv[0];
				port = Integer.parseInt(srv[1]);
			}
		}
		InetAddress ip;
		try {
			InetAddress[] all = InetAddress.getAllByName(host);
			ip = null;
			for (InetAddress x : all) {
				if (!Net.isPublic(x)) {
					throw new Http.Fail(400, "That address points to a private network");
				}
				if (ip == null || (ip instanceof java.net.Inet6Address && x instanceof java.net.Inet4Address)) {
					ip = x;
				}
			}
		} catch (java.net.UnknownHostException e) {
			return offline(address, port, null, "No such host");
		}
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(ip, port), 4000);
			s.setSoTimeout(5000);
			DataOutputStream out = new DataOutputStream(s.getOutputStream());
			DataInputStream in = new DataInputStream(s.getInputStream());
			ByteArrayOutputStream hs = new ByteArrayOutputStream();
			DataOutputStream h = new DataOutputStream(hs);
			varInt(h, 0);
			varInt(h, 767);
			byte[] hb = host.getBytes(StandardCharsets.UTF_8);
			varInt(h, hb.length);
			h.write(hb);
			h.writeShort(port);
			varInt(h, 1);
			packet(out, hs.toByteArray());
			packet(out, new byte[] {0});
			readVarInt(in);
			if (readVarInt(in) != 0) {
				return offline(address, port, ip, "Unexpected answer");
			}
			int len = readVarInt(in);
			if (len < 0 || len > 512 * 1024) {
				return offline(address, port, ip, "Answer too large");
			}
			byte[] json = new byte[len];
			in.readFully(json);
			long t0 = System.nanoTime();
			ByteArrayOutputStream pb = new ByteArrayOutputStream();
			DataOutputStream p = new DataOutputStream(pb);
			varInt(p, 1);
			p.writeLong(System.currentTimeMillis());
			packet(out, pb.toByteArray());
			long pingMs;
			try {
				readVarInt(in);
				readVarInt(in);
				in.readLong();
				pingMs = (System.nanoTime() - t0) / 1_000_000;
			} catch (IOException e) {
				pingMs = -1;
			}
			return parse(address, port, ip, new String(json, StandardCharsets.UTF_8), pingMs);
		} catch (java.net.SocketTimeoutException e) {
			return offline(address, port, ip, "Timed out");
		} catch (java.net.ConnectException e) {
			return offline(address, port, ip, "Connection refused");
		} catch (IOException e) {
			return offline(address, port, ip, "Couldn't read the status");
		}
	}

	private static Result parse(String address, int port, InetAddress ip, String json, long pingMs) {
		JsonObject o = JsonParser.parseString(json).getAsJsonObject();
		String version = o.has("version") ? o.getAsJsonObject("version").get("name").getAsString() : "";
		int protocol = o.has("version") && o.getAsJsonObject("version").has("protocol") ? o.getAsJsonObject("version").get("protocol").getAsInt() : -1;
		int online = 0, max = 0;
		List<String> sample = new ArrayList<>();
		if (o.has("players")) {
			JsonObject p = o.getAsJsonObject("players");
			online = p.has("online") ? p.get("online").getAsInt() : 0;
			max = p.has("max") ? p.get("max").getAsInt() : 0;
			if (p.has("sample")) {
				for (JsonElement e : p.getAsJsonArray("sample")) {
					JsonObject so = e.getAsJsonObject();
					if (sample.size() < 12 && so.has("name")) {
						sample.add(so.get("name").getAsString());
					}
				}
			}
		}
		List<Text.Run> motd = Text.fromComponent(o.get("description"));
		byte[] favicon = null;
		if (o.has("favicon")) {
			String f = o.get("favicon").getAsString();
			int comma = f.indexOf(',');
			try {
				favicon = Base64.getDecoder().decode(f.substring(comma + 1).replace("\n", ""));
			} catch (IllegalArgumentException ignored) {
				// no icon
			}
		}
		return new Result(address, port, ip.getHostAddress(), true, pingMs, version, protocol, online, max, sample, motd, favicon, null, System.currentTimeMillis());
	}

	private static Result offline(String address, int port, InetAddress ip, String why) {
		return new Result(address, port, ip == null ? null : ip.getHostAddress(), false, -1, null, -1, 0, 0, List.of(), List.of(), null, why, System.currentTimeMillis());
	}

	private static String[] srv(String host) {
		try {
			Hashtable<String, String> env = new Hashtable<>();
			env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
			env.put("com.sun.jndi.dns.timeout.initial", "1500");
			env.put("com.sun.jndi.dns.timeout.retries", "1");
			var ctx = new javax.naming.directory.InitialDirContext(env);
			var attrs = ctx.getAttributes("_minecraft._tcp." + host, new String[] {"SRV"});
			var srv = attrs.get("SRV");
			if (srv == null) {
				return null;
			}
			String[] p = srv.get().toString().split(" ");
			String target = p[3].endsWith(".") ? p[3].substring(0, p[3].length() - 1) : p[3];
			return new String[] {target, p[2]};
		} catch (Exception e) {
			return null;
		}
	}

	private static void packet(DataOutputStream out, byte[] data) throws IOException {
		ByteArrayOutputStream b = new ByteArrayOutputStream();
		varInt(new DataOutputStream(b), data.length);
		b.write(data);
		out.write(b.toByteArray());
		out.flush();
	}

	private static void varInt(DataOutputStream out, int v) throws IOException {
		while ((v & ~0x7F) != 0) {
			out.writeByte((v & 0x7F) | 0x80);
			v >>>= 7;
		}
		out.writeByte(v);
	}

	private static int readVarInt(DataInputStream in) throws IOException {
		int v = 0, n = 0;
		byte b;
		do {
			b = in.readByte();
			v |= (b & 0x7F) << (7 * n++);
			if (n > 5) {
				throw new IOException("VarInt too big");
			}
		} while ((b & 0x80) != 0);
		return v;
	}

	static Map<String, Object> json(Result r, ServerPing self) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("address", r.host());
		m.put("port", r.port());
		m.put("ip", r.ip());
		m.put("online", r.online());
		if (!r.online()) {
			m.put("error", r.error());
			return m;
		}
		m.put("ping", r.pingMs());
		m.put("version", r.version());
		m.put("protocol", r.protocol());
		m.put("players", Http.map("online", r.online_players(), "max", r.max(), "sample", r.sample()));
		m.put("motd", Text.formats(r.motd()));
		m.put("icon", r.favicon() == null ? null : "/v2/server/" + r.host() + "/icon.png");
		m.put("checkedAt", r.at());
		List<int[]> h = self.history(r.host());
		List<List<Long>> series = new ArrayList<>();
		for (int[] p : h) {
			series.add(List.of(p[0] * 60_000L, (long) p[1]));
		}
		m.put("history", series);
		return m;
	}
}
