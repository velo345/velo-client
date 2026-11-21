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

	/**
	 * For starting the game: the game keeps this token for the whole session and uses it on every server join, so it
	 * must have hours left, not seconds. Starting with one that ran out soon after made joins fail with "Invalid
	 * session" / "Failed to log in" (players and the owner, 2026-11-21) - now anything under 6 hours is refreshed first.
	 */
	public boolean tooShortForGame() {
		return System.currentTimeMillis() >= accessTokenExpiresAtEpochMillis - 6L * 3_600_000L;
	}
}
