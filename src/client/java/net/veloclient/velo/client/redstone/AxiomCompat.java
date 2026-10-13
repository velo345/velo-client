package net.veloclient.velo.client.redstone;

/**
 * Keeps Axiom out of Redstone Mode's way: both mods add a tool slot right of the hotbar and both
 * want the clicks and the scroll wheel, which fought each other. While Redstone Mode is on, Axiom
 * counts as inactive (see {@code AxiomDisableMixin}, an optional mixin that only applies when
 * Axiom is installed) and its tool slot is left, so the Redstone tool slot replaces it completely.
 * Axiom works normally again as soon as Redstone Mode is off. Purely reflective - no Axiom code is
 * bundled or needed at build time.
 */
public final class AxiomCompat {

	private static final String TOOL_MANAGER = "com.moulberry.axiom.buildertools.BuilderToolManager";
	private static Boolean installed;

	private AxiomCompat() {
	}

	public static boolean installed() {
		if (installed == null) {
			installed = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("axiom");
		}
		return installed;
	}

	/** Whether Axiom should behave as switched off right now (read by the mixin). */
	public static boolean suppressed() {
		return RedstoneMode.active();
	}

	/** Steps out of Axiom's own tool slot (if it's selected), so it can't stay "held" under ours. */
	static void leaveToolSlot() {
		if (!installed()) {
			return;
		}
		try {
			Class.forName(TOOL_MANAGER).getMethod("setToolSlotActive", boolean.class).invoke(null, false);
		} catch (ReflectiveOperationException | LinkageError ignored) {
			// Another Axiom version - the mixin still keeps it inactive.
		}
	}
}
