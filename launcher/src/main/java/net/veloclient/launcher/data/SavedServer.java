package net.veloclient.launcher.data;

/** A user-saved server entry in the launcher's "My Servers" list (design spec section 4). */
public record SavedServer(String id, String name, String host, int port) {

	public String address() {
		return port == 25565 ? host : host + ":" + port;
	}
}
