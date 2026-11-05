package net.veloclient.legacy.cape;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.living.player.ClientPlayerEntity;
import net.minecraft.resource.Identifier;
import net.veloclient.legacy.net.VeloUsers;

/** Which Velo cloak a player shows: your own equipped one, or what another Velo user published. */
public final class Capes {

	private Capes() {
	}

	public static Identifier textureFor(ClientPlayerEntity player) {
		String own = ownUuid();
		if (own != null && own.equals(dashless(player.getUuid()))) {
			LocalCape.Bundle bundle = LocalCape.equipped(own);
			return bundle == null ? null : CapeTextures.get("local:" + bundle.id() + "@" + bundle.file().toFile().lastModified(), () -> LocalCape.art(bundle));
		}
		VeloUsers.Entry entry = VeloUsers.get(player.getUuid(), player.getGameProfile().getName());
		if (entry == null || entry.capeId() == null) {
			return null;
		}
		String capeId = entry.capeId();
		return CapeTextures.get(capeId, () -> RemoteCapes.bytes(capeId));
	}

	public static String ownUuid() {
		String uuid = Minecraft.getInstance().getSession().getUuid();
		return uuid == null ? null : uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
	}

	static String dashless(java.util.UUID uuid) {
		return uuid == null ? "" : uuid.toString().replace("-", "");
	}
}
