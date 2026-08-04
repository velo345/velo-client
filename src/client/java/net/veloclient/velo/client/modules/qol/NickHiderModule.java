package net.veloclient.velo.client.modules.qol;

import net.veloclient.velo.module.AbstractModule;
import net.veloclient.velo.module.ModuleCategory;
import net.veloclient.velo.module.SafetyTag;

/**
 * Replaces every other player's in-world nametag with obfuscated (§k-style)
 * gibberish, purely for streaming/recording privacy - nothing sent to the
 * server changes, and only this client's own rendering is affected (see
 * {@link net.veloclient.velo.client.mixin.NickHiderMixin}). The local
 * player's own nametag is left untouched.
 */
public final class NickHiderModule extends AbstractModule {

	private static volatile boolean active;

	public NickHiderModule() {
		super("nick-hider", "Nick Hider",
				"Hides other players' nametags above their heads by scrambling them into obfuscated gibberish.",
				ModuleCategory.QOL, SafetyTag.ALWAYS_SAFE, false);
	}

	@Override
	public void onEnable() {
		active = true;
	}

	@Override
	public void onDisable() {
		active = false;
	}

	public static boolean isActive() {
		return active;
	}
}
