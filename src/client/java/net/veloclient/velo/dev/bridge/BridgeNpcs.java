package net.veloclient.velo.dev.bridge;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.veloclient.velo.client.network.VeloUserRegistry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The server's NPCs in Velo cosmetics (Velo Bridge "npc_cosmetics"): the plugin names each NPC by its entity id (the
 * NPC plugins make up their UUIDs), so the entity is looked up here and its look handed to {@link VeloUserRegistry}
 * under that entity's UUID - from there cloak, wings, aura and bodywear render like on any Velo player.
 */
final class BridgeNpcs {

	private record Look(int entity, UUID uuid, VeloUserRegistry.Entry entry, String pet) {
	}

	private static volatile List<Look> looks = List.of();
	private static int ticks;

	private BridgeNpcs() {
	}

	static void set(JsonObject m) {
		List<Look> out = new ArrayList<>();
		for (JsonElement e : VeloBridgeClient.arr(m, "npcs")) {
			JsonObject o = e.getAsJsonObject();
			UUID uuid = null;
			try {
				String u = VeloBridgeClient.str(o, "uuid", null);
				uuid = u == null ? null : UUID.fromString(u.length() == 32
						? u.replaceFirst("(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})", "$1-$2-$3-$4-$5") : u);
			} catch (IllegalArgumentException ignored) {
				// by entity id only
			}
			int entity = o.has("entity") ? o.get("entity").getAsInt() : -1;
			out.add(new Look(entity, uuid, new VeloUserRegistry.Entry("npc", VeloBridgeClient.str(o, "cape", null), VeloBridgeClient.str(o, "wings", null),
					VeloBridgeClient.str(o, "aura", null), false, null, VeloBridgeClient.str(o, "wear", null), false), VeloBridgeClient.str(o, "pet", null)));
		}
		looks = List.copyOf(out);
		ticks = 19; // resolve on the next tick
	}

	/** Once a second: entity ids -> UUIDs (NPCs come into range, respawn). */
	static void tick(MinecraftClient client) {
		if (++ticks % 20 != 0) {
			return;
		}
		List<Look> list = looks;
		if (list.isEmpty()) {
			return;
		}
		Map<UUID, VeloUserRegistry.Entry> map = new HashMap<>();
		Map<String, String> pets = new HashMap<>();
		for (Look l : list) {
			UUID uuid = l.uuid();
			if (l.entity() >= 0) {
				//? if <26.1 {
				var entity = client.world == null ? null : client.world.getEntityById(l.entity());
				if (entity != null) {
					uuid = entity.getUuid();
				}
				//?} else {
				/*var entity = client.level == null ? null : client.level.getEntity(l.entity());
				if (entity != null) {
					uuid = entity.getUUID();
				}
				*///?}
			}
			if (uuid != null) {
				map.put(uuid, l.entry());
				if (l.pet() != null) {
					pets.put(uuid.toString(), l.pet());
				}
			}
		}
		VeloUserRegistry.putNpcs(map);
		net.veloclient.velo.dev.pets.PetsModule.setNpcPets(pets);
	}

	static void reset() {
		looks = List.of();
		VeloUserRegistry.putNpcs(null);
		net.veloclient.velo.dev.pets.PetsModule.setNpcPets(null);
	}
}
