package io.github.instreest.jkite.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Where folding a path and walking it give different answers.
 *
 * The shape that matters is a link followed by "..", because that is the one
 * where the two disagree about which file a path names - not which spelling
 * of it, which file. Everything else here is a guard on the ways this could
 * go wrong instead.
 */
class TestRealPath {

	@TempDir
	Path dir;

	private String read(Path at) throws IOException {
		return new String(Files.readAllBytes(at), StandardCharsets.UTF_8).trim();
	}

	private Path file(Path at, String contents) throws IOException {
		Files.createDirectories(at.getParent());
		Files.write(at, contents.getBytes(StandardCharsets.UTF_8));
		return at;
	}

	/**
	 * The whole point, in one test.
	 *
	 * real/link points at elsewhere, so "real/link/../which.txt" walks into
	 * elsewhere and then back out of it, landing at the top - while folding
	 * the string cancels "link" against ".." and stays inside real. Two
	 * different files, and each says which one it is, so this reads the file
	 * back rather than comparing path spellings and hoping.
	 *
	 * The third read is the anchor: handing the unfolded path straight to the
	 * file system is what a shell does, and it has to agree with the answer
	 * RealPath gives, or this test is measuring its own fixture.
	 */
	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void aLinkFollowedByDotDotNamesTheFileTheShellWouldOpen() throws Exception {
		file(dir.resolve("real/which.txt"), "folded");
		file(dir.resolve("which.txt"), "walked");
		Files.createDirectories(dir.resolve("elsewhere"));
		Files.createSymbolicLink(dir.resolve("real/link"), dir.resolve("elsewhere"));
		Path typed = dir.resolve("real/link/../which.txt");

		Path resolved = RealPath.of(typed);

		assertEquals("walked", read(resolved),
				"it folded the string and reached the other file: " + resolved);
		assertEquals(read(typed), read(resolved),
				"it does not agree with what the file system does with the path as typed");
		assertEquals("folded", read(typed.toAbsolutePath().normalize()),
				"the fixture no longer distinguishes the two, so this test proves nothing");
	}

	/** A path with no links in it is left where it was, so nothing else moves. */
	@Test
	void anOrdinaryPathIsUnchanged() throws Exception {
		Path made = file(dir.resolve("plain/Report.java"), "x");

		assertEquals(made.toRealPath(), RealPath.of(dir.resolve("plain/./Report.java")));
	}

	/** Relative in, absolute out, as before. */
	@Test
	void aRelativePathIsStillMadeAbsolute() {
		assertTrue(RealPath.of(Path.of(".")).isAbsolute());
	}

	/**
	 * A path that is not there is folded rather than thrown about, because
	 * toRealPath() needs the file to exist and whoever asked has a better
	 * message for a missing script than an IOException.
	 */
	@Test
	void aPathThatIsNotThereFallsBackToFolding() {
		Path missing = dir.resolve("nope/../Report.java");

		assertEquals(dir.resolve("Report.java"), RealPath.of(missing));
	}
}
