package net.veloclient.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ResolveItemTest {

	private static final Map<String, String> KNOWN = Map.of("pet_ember", "Ember Drake", "pet_mini", "Mini-Me", "cape_a", "Twin", "cape_b", "Twin");

	@Test
	void picksTheIdFromWhatWasTypedOrPicked() {
		assertEquals("pet_ember", ServerApi.resolveItem("pet_ember", KNOWN));
		assertEquals("pet_ember", ServerApi.resolveItem("Ember Drake (pet_ember)", KNOWN));   // the picker's label (the old bug)
		assertEquals("pet_mini", ServerApi.resolveItem("  mini-me ", KNOWN));
		assertEquals("pet_ember", ServerApi.resolveItem("PET_EMBER", KNOWN));
	}

	@Test
	void unknownOrAmbiguousGivesNull() {
		assertNull(ServerApi.resolveItem("Twin", KNOWN));
		assertNull(ServerApi.resolveItem("Dragon (pet_dragon)", KNOWN));
		assertNull(ServerApi.resolveItem(null, KNOWN));
	}
}
