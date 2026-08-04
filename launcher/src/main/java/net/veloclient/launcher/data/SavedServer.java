package net.veloclient.launcher.data;

/**
 * A user-saved server entry in the launcher's "My Servers" list (design spec
 * section 4). {@code instanceId} is nullable - which {@link
 * net.veloclient.launcher.instance.Instance} mod profile "Connect" should
 * launch with; null means the entry is just a saved address with no
 * launchable profile assigned yet.
 */
public record SavedServer(String id, String name, String host, int port, String instanceId) {

	/** Convenience constructor for call sites that don't assign a profile yet. */
	public SavedServer(String id, String name, String host, int port) {
		this(id, name, host, port, null);
	}

	public String address() {
		return port == 25565 ? host : host + ":" + port;
	}
}
