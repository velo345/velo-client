package net.veloclient.launcher.auth;

/** The code/URL the user needs to enter at {@code verificationUri} to approve sign-in. */
public record DeviceCodeInfo(String deviceCode, String userCode, String verificationUri,
		int expiresInSeconds, int intervalSeconds, String message) {
}
