package pl.kingdomcraft.boatrace.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ArenaDisplayNameTest {

	@Test
	void priorityIsCustomThenConfigThenDerived() {
		final Arena arena = new Arena("Monaco_GP");

		assertEquals("Monaco GP", arena.getDisplayName());

		arena.setConfigName("Monaco Grand Prix");
		assertEquals("Monaco Grand Prix", arena.getDisplayName());

		arena.setDisplayName("Custom");
		assertEquals("Custom", arena.getDisplayName());

		arena.setDisplayName(null);
		assertEquals("Monaco Grand Prix", arena.getDisplayName());

		arena.setConfigName(" ");
		assertEquals("Monaco GP", arena.getDisplayName());
	}

	@Test
	void fallbackCopyKeepsTheConfigName() {
		final Arena arena = new Arena("1");
		arena.setConfigName("Silverstone");

		assertEquals("Silverstone", arena.inWorld("other").getDisplayName());
	}
}
