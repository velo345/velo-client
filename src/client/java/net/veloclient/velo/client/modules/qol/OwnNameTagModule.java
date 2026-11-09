package net.veloclient.velo.client.modules.qol;

import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/**
 * Own Name Tag: your name tag (with your Velo badge and name style) shows above you in third person (F5). Vanilla never
 * draws the name tag of the entity the camera belongs to - see {@code OwnNameTagMixin}.
 */
public final class OwnNameTagModule extends AbstractModule {

	private static volatile OwnNameTagModule instance;

	public OwnNameTagModule() {
		super("own-name-tag", "Own Name Tag", "See your own name tag - badge and name style included - in third person (F5).",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, true);
		instance = this;
	}

	public static boolean active() {
		OwnNameTagModule m = instance;
		return m != null && m.isEnabled();
	}
}
