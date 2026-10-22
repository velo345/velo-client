package net.veloclient.server;

/** Something the caller got wrong (or isn't allowed to do) - {@link VeloServerApp#handle} turns it into that HTTP status. */
final class ApiError extends Exception {

	final int status;

	ApiError(int status, String message) {
		super(message);
		this.status = status;
	}
}
