package net.veloclient.launcher.data;

/**
 * A user-saved server entry in the launcher's "My Servers" list (design spec
 * section 4). {@code instanceId} is nullable - which {@link
 * net.veloclient.launcher.instance.Instance} mod profile "Connect" should
 * launch with; null means the entry is just a saved address with no
 * launchable profile assigned yet.
 */
public record SavedServer(String id, String name, String host, int port, String instanceId) {

	/**
	 * Cleans up what people type into the address box: {@code mc://play.example.net:25566/} or
	 * {@code play.example.net:25566} used to be saved as the host of port 25565 and then never connected.
	 * An explicit port in the address wins over the port field while that is still the default.
	 */
	public SavedServer {
		String cleaned = host == null ? "" : host.trim();
		int scheme = cleaned.indexOf("://");
		if (scheme >= 0) {
			cleaned = cleaned.substring(scheme + 3);
		}
		int slash = cleaned.indexOf('/');
		if (slash >= 0) {
			cleaned = cleaned.substring(0, slash);
		}
		String embedded = null;
		if (cleaned.startsWith("[") && cleaned.contains("]:")) {
			embedded = cleaned.substring(cleaned.indexOf("]:") + 2);
			cleaned = cleaned.substring(0, cleaned.indexOf("]:") + 1);
		} else if (cleaned.indexOf(':') > 0 && cleaned.indexOf(':') == cleaned.lastIndexOf(':')) {
			embedded = cleaned.substring(cleaned.indexOf(':') + 1);
			cleaned = cleaned.substring(0, cleaned.indexOf(':'));
		}
		if (embedded != null) {
			try {
				int parsed = Integer.parseInt(embedded.trim());
				if (parsed > 0 && parsed < 65536 && (port <= 0 || port == 25565)) {
					port = parsed;
				}
			} catch (NumberFormatException ignored) {
				// Not a port: keep the one from the port field.
			}
		}
		host = cleaned;
		if (port <= 0 || port > 65535) {
			port = 25565;
		}
	}

	/** Convenience constructor for call sites that don't assign a profile yet. */
	public SavedServer(String id, String name, String host, int port) {
		this(id, name, host, port, null);
	}

	public String address() {
		return port == 25565 ? host : host + ":" + port;
	}
}
