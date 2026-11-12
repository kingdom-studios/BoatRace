package pl.kingdomcraft.captainhook.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The protocol package exists in both modules (they share no code and run in different processes). This test
 * fails the build when the two copies differ, so the proxy and the servers can never disagree on the protocol.
 */
class ProtocolParityTest {

	private static final String PACKAGE = "src/main/java/pl/kingdomcraft/captainhook/protocol";

	@Test
	void bothModulesHaveTheSameProtocolClasses() throws IOException {
		final Path velocity = Paths.get(PACKAGE);
		final Path bukkit = Paths.get("..", "bukkit").resolve(PACKAGE);
		final List<String> files = list(velocity);

		assertFalse(files.isEmpty(), "No protocol classes found in " + velocity.toAbsolutePath());
		assertEquals(files, list(bukkit), "The protocol packages of velocity and bukkit hold different files");

		for (final String file : files)
			assertArrayEquals(Files.readAllBytes(velocity.resolve(file)), Files.readAllBytes(bukkit.resolve(file)),
					file + " differs between the velocity and the bukkit module, copy the change to the other one");
	}

	private static List<String> list(final Path directory) throws IOException {
		try (Stream<Path> files = Files.list(directory)) {
			return files.map(file -> file.getFileName().toString()).sorted().collect(Collectors.toList());
		}
	}
}
