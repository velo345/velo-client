package net.veloclient.launcher.auth;

/**
 * A fully-authenticated Minecraft session: what's needed to show account
 * info in the launcher (and, eventually, to launch the game with
 * {@code --accessToken}/{@code --uuid}/{@code --username}).
 */
public record MinecraftSession(
		String minecraftAccessToken,
		long accessTokenExpiresAtEpochMillis,
		String microsoftRefreshToken,
		String uuid,
		String username) {

	public boolean isAccessTokenExpired() {
		return System.currentTimeMillis() >= accessTokenExpiresAtEpochMillis - 30_000;
	}
}
