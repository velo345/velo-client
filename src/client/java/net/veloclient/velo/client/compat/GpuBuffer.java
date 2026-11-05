package net.veloclient.velo.client.compat;

//? if <1.21.2 {
/*/^* 1.21.1 has no GPU buffer objects; only the frame grabber's pool type needs to exist (never created there). ^/
public final class GpuBuffer implements AutoCloseable {
	@Override
	public void close() {
	}
}
*///?}
