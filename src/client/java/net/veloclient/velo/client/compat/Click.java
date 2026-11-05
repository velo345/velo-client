package net.veloclient.velo.client.compat;

//? if <1.21.2 {
/*/^* 1.21.1 stand-in for 1.21.9's mouse click event (only what Velo reads from it). ^/
public record Click(double x, double y, int button) {

	private static long lastTime;
	private static int lastButton = -1;
	private static double lastX;
	private static double lastY;

	/^* Whether this click doubles the previous one (same button, close by, within a quarter second). ^/
	public static boolean doubled(double x, double y, int button) {
		long now = System.currentTimeMillis();
		boolean doubled = button == lastButton && now - lastTime < 250 && Math.abs(x - lastX) < 4 && Math.abs(y - lastY) < 4;
		lastTime = now;
		lastButton = button;
		lastX = x;
		lastY = y;
		return doubled;
	}
}
*///?}
