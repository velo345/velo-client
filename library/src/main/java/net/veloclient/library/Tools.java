package net.veloclient.library;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Small text tools behind the API: gradient/animation frames and JVM start flags. */
final class Tools {

	private Tools() {
	}

	/** Colour at t (0..1) on a looped gradient through the stops. */
	static int at(int[] stops, double t) {
		t = ((t % 1) + 1) % 1;
		int n = stops.length;
		double seg = t * n;
		int i = (int) Math.floor(seg) % n;
		double f = seg - Math.floor(seg);
		int a = stops[i], b = stops[(i + 1) % n];
		int r = (int) Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * f);
		int g = (int) Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * f);
		int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * f);
		return r << 16 | g << 8 | bl;
	}

	static Map<String, Object> gradient(String text, int[] stops, String mode, int frames, boolean bold, String format) {
		if (text.length() > 64) {
			throw new Http.Fail(400, "Up to 64 characters");
		}
		List<String> out = new ArrayList<>();
		int n = text.length();
		int count = mode.equals("static") ? 1 : frames;
		for (int f = 0; f < count; f++) {
			double p = f / (double) count;
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < n; i++) {
				double x = n > 1 ? i / (double) (n - 1) : 0;
				double t = switch (mode) {
					case "shift" -> p;
					case "pulse" -> 0.5 + 0.25 * Math.sin(p * Math.PI * 2) + x * 0.15;
					case "static" -> x * (stops.length - 1) / (double) stops.length;
					default -> x * 0.5 - p;
				};
				String hex = String.format("%06x", at(stops, t));
				char ch = text.charAt(i);
				switch (format) {
					case "minimessage" -> sb.append("<#").append(hex).append('>').append(bold ? "<b>" : "").append(ch == '<' ? "\\<" : String.valueOf(ch));
					case "legacy" -> {
						sb.append("§x");
						for (char c : hex.toCharArray()) {
							sb.append('§').append(c);
						}
						sb.append(bold ? "§l" : "").append(ch);
					}
					default -> {
						sb.append("&#").append(hex).append(bold ? "&l" : "").append(ch);
					}
				}
			}
			out.add(sb.toString());
		}
		return Http.map("frames", out, "count", out.size());
	}

	static Map<String, Object> flags(int ramGb, String software, String gc, String os) {
		int ram = Math.max(1, Math.min(256, ramGb));
		boolean big = ram >= 12;
		List<String> list;
		if (software.equals("velocity") || software.equals("bungeecord") || software.equals("waterfall")) {
			list = List.of("-XX:+UseG1GC", "-XX:G1HeapRegionSize=4M", "-XX:+UnlockExperimentalVMOptions", "-XX:+ParallelRefProcEnabled",
					"-XX:+AlwaysPreTouch", "-XX:MaxInlineLevel=15");
		} else if (gc.equals("zgc")) {
			list = List.of("-XX:+UseZGC", "-XX:+ZGenerational", "-XX:+AlwaysPreTouch", "-XX:+DisableExplicitGC", "-XX:+PerfDisableSharedMem");
		} else {
			list = List.of("-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200", "-XX:+UnlockExperimentalVMOptions",
					"-XX:+DisableExplicitGC", "-XX:+AlwaysPreTouch", "-XX:G1NewSizePercent=" + (big ? 40 : 30), "-XX:G1MaxNewSizePercent=" + (big ? 50 : 40),
					"-XX:G1HeapRegionSize=" + (big ? "16M" : "8M"), "-XX:G1ReservePercent=" + (big ? 15 : 20), "-XX:G1HeapWastePercent=5",
					"-XX:G1MixedGCCountTarget=4", "-XX:InitiatingHeapOccupancyPercent=" + (big ? 20 : 15), "-XX:G1MixedGCLiveThresholdPercent=90",
					"-XX:G1RSetUpdatingPauseTimePercent=5", "-XX:SurvivorRatio=32", "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1",
					"-Dusing.aikars.flags=https://mcflags.emc.gs", "-Daikars.new.flags=true");
		}
		String jar = switch (software) {
			case "folia" -> "folia.jar";
			case "purpur" -> "purpur.jar";
			case "velocity" -> "velocity.jar";
			case "fabric" -> "fabric-server-launch.jar";
			case "forge", "neoforge" -> "server.jar";
			default -> "paper.jar";
		};
		boolean proxy = jar.equals("velocity.jar");
		int mem = proxy ? Math.min(ram, 2) : ram;
		String cmd = "java -Xms" + mem + "G -Xmx" + mem + "G " + String.join(" ", list) + " -jar " + jar + (proxy ? "" : " --nogui");
		String script = switch (os) {
			case "windows" -> "@echo off\r\n" + cmd + "\r\npause\r\n";
			case "pterodactyl" -> String.join(" ", list);
			default -> "#!/bin/sh\n" + cmd + "\n";
		};
		return Http.map("flags", list, "command", cmd, "script", script, "memoryGb", mem);
	}
}
