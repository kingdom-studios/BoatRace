package pl.kingdomcraft.boatrace.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.boatrace.captainhook.CaptainHookHook;

class ArenaDisplayNameTest {

	@Test
	void defaultNameIsTheArenaNameMadeReadable() {
		assertEquals("Monaco GP", new Arena("Monaco_GP").getDisplayName());
		assertEquals("Ice Ring 2", new Arena("Ice-Ring_2").getDisplayName());
		assertEquals("Spa", new Arena("Spa").getDisplayName());
		assertEquals("A B", new Arena("A__-B").getDisplayName(), "runs of separators become one space");
		assertEquals("_", new Arena("_").getDisplayName(), "a name that would become empty stays as it is");
	}

	@Test
	void customNameWinsAndCanBeCleared() {
		final Arena arena = new Arena("monaco_gp");

		arena.setDisplayName("  Monaco Grand Prix ");
		assertEquals("Monaco Grand Prix", arena.getDisplayName());
		assertEquals("Monaco Grand Prix", arena.getCustomDisplayName());

		arena.setDisplayName("   ");
		assertNull(arena.getCustomDisplayName());
		assertEquals("monaco gp", arena.getDisplayName());

		arena.setDisplayName("X");
		arena.setDisplayName(null);
		assertNull(arena.getCustomDisplayName());
	}

	@Test
	void aCopyInAnotherWorldKeepsTheName() {
		final Arena arena = new Arena("monaco_gp");

		arena.setDisplayName("Monaco");

		assertEquals("Monaco", arena.inWorld("copy").getDisplayName());
	}

	@Test
	void trackIdIsStablePerArenaNameAndNeverTheDisplayName() {
		// the website joins on this id: it follows the ARENA name only, so renaming the display name never breaks stats
		final Arena arena = new Arena("Monaco_GP");

		assertEquals("monaco_gp", CaptainHookHook.trackId(arena.getName()));

		arena.setDisplayName("Totally Different");
		assertEquals("monaco_gp", CaptainHookHook.trackId(arena.getName()));

		// characters outside a-z0-9_ become _, long names are cut to 19 characters
		assertEquals("ice_ring_2", CaptainHookHook.trackId("Ice-Ring_2"));
		assertEquals("a_very_long_arena_n", CaptainHookHook.trackId("A_Very_Long_Arena_Name_Indeed"));
	}
}
