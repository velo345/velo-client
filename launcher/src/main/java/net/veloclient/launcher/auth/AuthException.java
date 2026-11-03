package net.veloclient.launcher.auth;

/** Any failure in the Microsoft -> Xbox Live -> XSTS -> Minecraft Services auth chain. */
public class AuthException extends RuntimeException {

	/** Seconds a 429 / 503 asked to wait (Retry-After), or -1. */
	int retryAfterSeconds = -1;

	public AuthException(String message) {
		super(message);
	}

	public AuthException(String message, Throwable cause) {
		super(message, cause);
	}
}
