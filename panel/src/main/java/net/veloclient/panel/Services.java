package net.veloclient.panel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The systemd units the panel may see and control - an allow-list from {@code PANEL_SERVICES}, nothing else.
 * Status and logs need no privileges ({@code systemctl show}, {@code journalctl} as a member of systemd-journal);
 * start/stop/restart run {@code sudo -n systemctl <action> <unit>}, which the sudoers file allows for exactly these
 * units and actions.
 */
final class Services {

	private static final Set<String> ACTIONS = Set.of("start", "stop", "restart");
	private final List<String> units;
	/** The panel's own unit: it may restart itself, but not stop itself. */
	private final String self;

	Services(List<String> units, String self) {
		this.units = units;
		this.self = self;
	}

	List<String> units() {
		return units;
	}

	List<Map<String, Object>> status() throws IOException, InterruptedException {
		List<Map<String, Object>> out = new ArrayList<>();
		for (String unit : units) {
			String raw = run(List.of("systemctl", "show", unit + ".service", "--no-pager", "--timestamp=unix",
					"-p", "Description,ActiveState,SubState,MainPID,MemoryCurrent,CPUUsageNSec,ActiveEnterTimestamp,NRestarts,UnitFileState"), 10);
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("unit", unit);
			for (String line : raw.split("\n")) {
				int eq = line.indexOf('=');
				if (eq > 0) {
					row.put(line.substring(0, eq), line.substring(eq + 1).trim());
				}
			}
			row.put("self", unit.equals(self));
			out.add(row);
		}
		return out;
	}

	String act(String unit, String action) throws IOException, InterruptedException {
		if (!units.contains(unit) || !ACTIONS.contains(action)) {
			throw new Http.Fail(400, "Not allowed");
		}
		if (unit.equals(self) && !"restart".equals(action)) {
			throw new Http.Fail(400, "The panel can only restart itself - stopping it would lock you out");
		}
		if (unit.equals(self)) {
			// Answer first, then restart (the restart kills this process).
			new ProcessBuilder("sudo", "-n", "/usr/bin/systemctl", "--no-block", "restart", unit + ".service").start();
			return "Restarting";
		}
		Process p = new ProcessBuilder("sudo", "-n", "/usr/bin/systemctl", action, unit + ".service").redirectErrorStream(true).start();
		String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		if (!p.waitFor(60, TimeUnit.SECONDS)) {
			p.destroyForcibly();
			throw new Http.Fail(504, "systemctl took too long");
		}
		if (p.exitValue() != 0) {
			throw new Http.Fail(500, output.isEmpty() ? "systemctl failed (" + p.exitValue() + ")" : output);
		}
		return "Done";
	}

	String logs(String unit, int lines, String grep) throws IOException, InterruptedException {
		if (!units.contains(unit)) {
			throw new Http.Fail(400, "Not allowed");
		}
		List<String> cmd = new ArrayList<>(List.of("journalctl", "-u", unit + ".service", "-n", String.valueOf(Math.max(10, Math.min(lines, 2000))),
				"--no-pager", "-o", "short-iso"));
		if (grep != null && !grep.isBlank()) {
			cmd.add("--grep=" + grep.trim());
			cmd.add("--case-sensitive=false");
		}
		return run(cmd, 20);
	}

	private static String run(List<String> cmd, int timeoutSeconds) throws IOException, InterruptedException {
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		byte[] out = p.getInputStream().readNBytes(4 * 1024 * 1024);
		if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
			p.destroyForcibly();
		}
		return new String(out, StandardCharsets.UTF_8);
	}
}
