package io.github.instreest.jkite.dependencies;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jbang.dependencies.MavenCoordinate;

/**
 * The dependency cache holds the entries of every script on this machine, and
 * runs that share it are not aware of each other. What one of them writes must
 * therefore keep what the others wrote, and what it reads must be a whole class
 * path or nothing at all.
 */
class TestDependencyCache {

	@TempDir
	Path dir;

	private Path file() {
		return dir.resolve("dependency_cache.txt");
	}

	private static List<ArtifactInfo> artifacts(String... coords) {
		return Arrays.stream(coords)
			.map(c -> new ArtifactInfo(MavenCoordinate.fromString(c), Paths.get("/does/not/matter/" + c + ".jar"), 1L))
			.collect(java.util.stream.Collectors.toList());
	}

	/**
	 * The entry another run stored while this one was resolving is in the file
	 * but not in what this one read at startup. Writing back what it read would
	 * drop it, and that run would resolve everything again on its next start.
	 */
	@Test
	void storingAnEntryKeepsTheOnesThisRunNeverSaw() {
		DependencyCache.merge(file(), "first", artifacts("com.example:one:1.0"));

		// a second run, which never saw the entry above
		DependencyCache.merge(file(), "second", artifacts("com.example:two:1.0"));

		Map<String, List<ArtifactInfo>> stored = DependencyCache.read(file());
		assertEquals(Arrays.asList("first", "second"), new java.util.ArrayList<>(stored.keySet()));
	}

	@Test
	void anEntryIsReadBackAsItWasWritten() {
		DependencyCache.merge(file(), "key", artifacts("com.example:one:1.0", "com.example:two:2.0"));

		List<ArtifactInfo> read = DependencyCache.read(file()).get("key");
		assertEquals(2, read.size());
		// the coordinate comes back naming the type it was given by default
		assertEquals("com.example:one:1.0@jar", read.get(0).getCoordinate().toMavenString());
		assertEquals(Paths.get("/does/not/matter/com.example:two:2.0.jar"), read.get(1).getFile());
		assertEquals(1L, read.get(1).getTimestamp());
	}

	/**
	 * A class path with one artifact missing still looks usable: every file it
	 * does name is there, so it would be handed out and the script would fail
	 * much later, on a class that cannot be found.
	 */
	@Test
	void aDamagedLineDropsItsWholeEntry() throws IOException {
		Files.write(file(), String.join("\n",
				"[good]",
				"com.example:one:1.0\t/does/not/matter/one.jar\t1",
				"",
				"[damaged]",
				"com.example:two:1.0\t/does/not/matter/two.jar\t1",
				"this line is not an artifact",
				"").getBytes(StandardCharsets.UTF_8));

		Map<String, List<ArtifactInfo>> read = DependencyCache.read(file());

		assertTrue(read.containsKey("good"));
		assertFalse(read.containsKey("damaged"), "an entry that cannot be read whole is not an entry");
	}

	@Test
	void anEntryWithAnUnreadableTimestampIsDroppedToo() throws IOException {
		Files.write(file(), String.join("\n",
				"[damaged]",
				"com.example:two:1.0\t/does/not/matter/two.jar\tnot-a-number",
				"").getBytes(StandardCharsets.UTF_8));

		assertEquals(Collections.emptySet(), DependencyCache.read(file()).keySet());
	}

	@Test
	void aMissingFileIsAnEmptyCache() {
		assertEquals(Collections.emptySet(), DependencyCache.read(file()).keySet());
	}
}
