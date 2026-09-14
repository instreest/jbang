package io.github.instreest.jkite.jdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.instreest.jkite.Settings;

/**
 * The JVM index says where a JDK comes from and nothing in the project pins
 * what it says, so the one thing that keeps a bad index from choosing the
 * download is the host check.
 */
class TestJdkManager {

	@TempDir
	Path jdksDir;

	private static JdkIndex.Entry entry(String url) {
		return new JdkIndex.Entry(Settings.JDK_DISTRO, "25.0.3", "tar.gz", url);
	}

	@Test
	void theDistributionsOwnHostIsAccepted() throws IOException {
		new JdkManager().requireExpectedHost(
				entry("https://" + Settings.JDK_DOWNLOAD_HOST + "/adoptium/temurin25-binaries/releases/x.tar.gz"));
	}

	@Test
	void anyOtherHostIsRefused() {
		IOException e = assertThrows(IOException.class,
				() -> new JdkManager().requireExpectedHost(entry("https://evil.example/jdk.tar.gz")));
		assertTrue(e.getMessage().contains("evil.example"), e.getMessage());
		assertTrue(e.getMessage().contains(Settings.JDK_DOWNLOAD_HOST), e.getMessage());
	}

	@Test
	void aLookalikeHostIsRefused() {
		assertThrows(IOException.class, () -> new JdkManager()
			.requireExpectedHost(entry("https://github.com.evil.example/adoptium/jdk.tar.gz")));
	}

	@Test
	void aUrlWithNoHostIsRefused() {
		assertThrows(IOException.class,
				() -> new JdkManager().requireExpectedHost(entry("file:///tmp/jdk.tar.gz")));
	}

	/**
	 * A JDK carries where it was found, and --verbose prints it: a run that
	 * picked the wrong Java cannot be explained without it.
	 */
	@Test
	void aJdkFromTheCacheSaysWhereItCameFrom() throws IOException {
		fakeJdk("25.0.3", "25.0.3+9");

		List<Jdk> cached = new JdkManager(jdksDir, 17).listCachedJdks();

		assertEquals(1, cached.size());
		assertEquals(Jdk.Origin.CACHE, cached.get(0).origin());
		assertEquals("jkite", cached.get(0).origin().label(), "what --verbose prints for it");
	}

	@Test
	void theNewestOfTheCachedJdksComesFirst() throws IOException {
		fakeJdk("21.0.1", "21.0.1+12");
		fakeJdk("25.0.3", "25.0.3+9");

		List<String> found = new JdkManager(jdksDir, 17).listCachedJdks().stream()
			.map(Jdk::version)
			.collect(Collectors.toList());

		assertEquals(Arrays.asList("25.0.3+9", "21.0.1+12"), found);
	}

	/**
	 * Every place has a label of its own, and they are these. The labels are
	 * output, so a change to one is a change a reader sees; keeping them in one
	 * enum is what makes that a deliberate change rather than a missed rename.
	 */
	@Test
	void everyPlaceIsNamedAndNoTwoAreNamedAlike() {
		assertEquals("current", Jdk.Origin.CURRENT.label());
		assertEquals("JAVA_HOME", Jdk.Origin.JAVA_HOME.label());
		assertEquals("PATH", Jdk.Origin.PATH.label());
		assertEquals("jkite", Jdk.Origin.CACHE.label());

		Set<String> labels = Arrays.stream(Jdk.Origin.values())
			.map(Jdk.Origin::label)
			.collect(Collectors.toCollection(HashSet::new));
		assertEquals(Jdk.Origin.values().length, labels.size(), "two places with the same name explain nothing");
	}

	/** Enough of a JDK for Jdk.of: a javac to find and a release file to read. */
	private void fakeJdk(String dirName, String version) throws IOException {
		Path home = Files.createDirectories(jdksDir.resolve(dirName));
		Files.createDirectories(home.resolve("bin"));
		Files.write(home.resolve("bin").resolve("javac"), new byte[0]);
		Files.write(home.resolve("release"),
				("JAVA_VERSION=\"" + version + "\"\n").getBytes(StandardCharsets.UTF_8));
	}
}
