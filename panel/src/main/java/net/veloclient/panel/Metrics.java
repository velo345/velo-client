package net.veloclient.panel;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The machine's vital signs, read from /proc every five seconds: CPU, memory, swap, network and disk throughput,
 * load. Keeps the last hour at 5 s and the last day at one-minute averages for the charts.
 */
final class Metrics {

	record Sample(long ts, double cpu, long memUsed, long memTotal, long swapUsed, long swapTotal, double rx, double tx,
			double diskRead, double diskWrite, double load1) {
	}

	private final Deque<Sample> recent = new ArrayDeque<>();
	private final Deque<Sample> day = new ArrayDeque<>();
	private final List<Sample> minute = new ArrayList<>();
	private long[] lastCpu;
	private long[] lastNet;
	private long[] lastDisk;
	private long lastTs;

	void start() {
		Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "panel-metrics");
			t.setDaemon(true);
			return t;
		}).scheduleAtFixedRate(() -> {
			try {
				sample();
			} catch (Exception e) {
				System.out.println("[panel] metrics: " + e.getMessage());
			}
		}, 0, 5, TimeUnit.SECONDS);
	}

	private synchronized void sample() throws IOException {
		long now = System.currentTimeMillis();
		long[] cpu = cpuTimes();
		long[] net = netBytes();
		long[] disk = diskSectors();
		Map<String, Long> mem = meminfo();
		double seconds = lastTs == 0 ? 0 : (now - lastTs) / 1000.0;
		double cpuPct = 0;
		if (lastCpu != null) {
			long idle = cpu[1] - lastCpu[1];
			long total = cpu[0] - lastCpu[0];
			cpuPct = total <= 0 ? 0 : 100.0 * (total - idle) / total;
		}
		double rx = seconds > 0 && lastNet != null ? (net[0] - lastNet[0]) / seconds : 0;
		double tx = seconds > 0 && lastNet != null ? (net[1] - lastNet[1]) / seconds : 0;
		double dr = seconds > 0 && lastDisk != null ? (disk[0] - lastDisk[0]) * 512 / seconds : 0;
		double dw = seconds > 0 && lastDisk != null ? (disk[1] - lastDisk[1]) * 512 / seconds : 0;
		lastCpu = cpu;
		lastNet = net;
		lastDisk = disk;
		boolean first = lastTs == 0;
		lastTs = now;
		if (first) {
			return;
		}
		long total = mem.getOrDefault("MemTotal", 0L) * 1024;
		long available = mem.getOrDefault("MemAvailable", 0L) * 1024;
		long swapTotal = mem.getOrDefault("SwapTotal", 0L) * 1024;
		long swapFree = mem.getOrDefault("SwapFree", 0L) * 1024;
		Sample s = new Sample(now, round(cpuPct), total - available, total, swapTotal - swapFree, swapTotal, Math.max(0, rx), Math.max(0, tx),
				Math.max(0, dr), Math.max(0, dw), load()[0]);
		recent.addLast(s);
		while (recent.size() > 720) {
			recent.removeFirst();
		}
		minute.add(s);
		if (minute.size() >= 12) {
			day.addLast(average(minute));
			minute.clear();
			while (day.size() > 1440) {
				day.removeFirst();
			}
		}
	}

	private static double round(double v) {
		return Math.round(v * 10) / 10.0;
	}

	private static Sample average(List<Sample> list) {
		int n = list.size();
		double cpu = 0;
		double rx = 0;
		double tx = 0;
		double dr = 0;
		double dw = 0;
		double load = 0;
		long mem = 0;
		long swap = 0;
		for (Sample s : list) {
			cpu += s.cpu();
			rx += s.rx();
			tx += s.tx();
			dr += s.diskRead();
			dw += s.diskWrite();
			load += s.load1();
			mem += s.memUsed();
			swap += s.swapUsed();
		}
		Sample last = list.get(n - 1);
		return new Sample(last.ts(), round(cpu / n), mem / n, last.memTotal(), swap / n, last.swapTotal(), rx / n, tx / n, dr / n, dw / n, load / n);
	}

	/** Current numbers plus the history ("1h" at 5 s, "24h" at 1 min). */
	synchronized Map<String, Object> snapshot(String range) throws IOException {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("current", recent.peekLast());
		out.put("history", new ArrayList<>("24h".equals(range) ? day : recent));
		out.put("cores", Runtime.getRuntime().availableProcessors());
		double[] load = load();
		out.put("load", load);
		out.put("uptimeSeconds", (long) Double.parseDouble(Files.readString(Path.of("/proc/uptime")).trim().split("\\s+")[0]));
		List<Map<String, Object>> disks = new ArrayList<>();
		for (String mount : List.of("/", "/opt", "/var")) {
			FileStore store = Files.getFileStore(Path.of(mount));
			if (disks.stream().anyMatch(d -> d.get("name").equals(store.name()))) {
				continue;
			}
			disks.add(Map.of("mount", mount, "name", store.name(), "total", store.getTotalSpace(), "free", store.getUsableSpace()));
		}
		out.put("disks", disks);
		out.put("hostname", Files.readString(Path.of("/proc/sys/kernel/hostname")).trim());
		out.put("kernel", Files.readString(Path.of("/proc/sys/kernel/osrelease")).trim());
		return out;
	}

	/** The biggest processes by memory, with their CPU time - like the top of {@code top}. */
	static List<Map<String, Object>> topProcesses(int limit) {
		List<Map<String, Object>> out = new ArrayList<>();
		try (var dirs = Files.list(Path.of("/proc"))) {
			for (Path dir : dirs.filter(p -> p.getFileName().toString().matches("\\d+")).toList()) {
				try {
					String status = Files.readString(dir.resolve("status"));
					long rss = 0;
					String name = "";
					String user = "";
					for (String line : status.split("\n")) {
						if (line.startsWith("VmRSS:")) {
							rss = Long.parseLong(line.replaceAll("\\D+", "")) * 1024;
						} else if (line.startsWith("Name:")) {
							name = line.substring(5).trim();
						} else if (line.startsWith("Uid:")) {
							user = line.substring(4).trim().split("\\s+")[0];
						}
					}
					if (rss == 0) {
						continue;
					}
					String stat = Files.readString(dir.resolve("stat"));
					String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
					long ticks = Long.parseLong(f[11]) + Long.parseLong(f[12]);
					String cmd = new String(Files.readAllBytes(dir.resolve("cmdline"))).replace('\0', ' ').trim();
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("pid", Integer.parseInt(dir.getFileName().toString()));
					row.put("name", name);
					row.put("uid", user);
					row.put("rss", rss);
					row.put("cpuSeconds", ticks / 100);
					row.put("command", cmd.length() > 160 ? cmd.substring(0, 160) + "…" : cmd);
					out.add(row);
				} catch (IOException | RuntimeException ignored) {
					// The process ended while we looked, or isn't ours to read.
				}
			}
		} catch (IOException e) {
			return out;
		}
		out.sort(Comparator.comparingLong((Map<String, Object> m) -> (Long) m.get("rss")).reversed());
		return out.subList(0, Math.min(limit, out.size()));
	}

	// ---- /proc ----

	/** {total jiffies, idle jiffies (idle + iowait)}. */
	private static long[] cpuTimes() throws IOException {
		String line = Files.readAllLines(Path.of("/proc/stat")).get(0);
		String[] f = line.trim().split("\\s+");
		long total = 0;
		for (int i = 1; i < Math.min(f.length, 9); i++) {
			total += Long.parseLong(f[i]);
		}
		return new long[] {total, Long.parseLong(f[4]) + Long.parseLong(f[5])};
	}

	/** Received and sent bytes over every interface but loopback and container bridges (counted once at the uplink). */
	private static long[] netBytes() throws IOException {
		long rx = 0;
		long tx = 0;
		for (String line : Files.readAllLines(Path.of("/proc/net/dev"))) {
			int colon = line.indexOf(':');
			if (colon < 0) {
				continue;
			}
			String name = line.substring(0, colon).trim();
			if (name.equals("lo") || name.startsWith("veth") || name.startsWith("br-") || name.startsWith("docker")) {
				continue;
			}
			String[] f = line.substring(colon + 1).trim().split("\\s+");
			rx += Long.parseLong(f[0]);
			tx += Long.parseLong(f[8]);
		}
		return new long[] {rx, tx};
	}

	/** Sectors read and written by whole disks (not partitions). */
	private static long[] diskSectors() throws IOException {
		long read = 0;
		long written = 0;
		for (String line : Files.readAllLines(Path.of("/proc/diskstats"))) {
			String[] f = line.trim().split("\\s+");
			if (f.length < 10) {
				continue;
			}
			String name = f[2];
			if (!(name.matches("(sd|vd|xvd)[a-z]+") || name.matches("nvme\\d+n\\d+"))) {
				continue;
			}
			read += Long.parseLong(f[5]);
			written += Long.parseLong(f[9]);
		}
		return new long[] {read, written};
	}

	private static Map<String, Long> meminfo() throws IOException {
		Map<String, Long> out = new LinkedHashMap<>();
		for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
			int colon = line.indexOf(':');
			if (colon > 0) {
				try {
					out.put(line.substring(0, colon), Long.parseLong(line.substring(colon + 1).replaceAll("\\D+", "")));
				} catch (NumberFormatException ignored) {
					// Not a number line.
				}
			}
		}
		return out;
	}

	private static double[] load() throws IOException {
		String[] f = Files.readString(Path.of("/proc/loadavg")).trim().split("\\s+");
		return new double[] {Double.parseDouble(f[0]), Double.parseDouble(f[1]), Double.parseDouble(f[2])};
	}
}
