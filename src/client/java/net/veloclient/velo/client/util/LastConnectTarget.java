package net.veloclient.velo.client.util;

import net.minecraft.client.gui.screen.Screen;
//? if <26.1 {
import net.minecraft.client.network.ServerInfo;
//?} else {
/*import net.minecraft.client.multiplayer.ServerData;
*///?}

/** The server (and the screen to return to) of the most recent connection attempt - see ConnectTargetMixin. */
public final class LastConnectTarget {

	//? if <26.1 {
	private static volatile ServerInfo server;
	//?} else {
	/*private static volatile ServerData server;
	*///?}
	private static volatile Screen parent;

	private LastConnectTarget() {
	}

	//? if <26.1 {
	public static void remember(ServerInfo info, Screen returnTo) {
		if (info != null) {
			server = info;
			parent = returnTo;
		}
	}

	public static ServerInfo server() {
		return server;
	}
	//?} else {
	/*public static void remember(ServerData info, Screen returnTo) {
		if (info != null) {
			server = info;
			parent = returnTo;
		}
	}

	public static ServerData server() {
		return server;
	}
	*///?}

	public static Screen parent() {
		return parent;
	}
}
