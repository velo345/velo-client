package net.veloclient.launcher.auth;

/** Any failure in the Microsoft -> Xbox Live -> XSTS -> Minecraft Services auth chain. */
public class AuthException extends RuntimeException {

	public AuthException(String message) {
		super(message);
	}

	public AuthException(String message, Throwable cause) {
		super(message, cause);
	}
}
