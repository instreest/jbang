package io.github.instreest.jkite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

/**
 * Where jkite keeps things when JKITE_DIR says nothing.
 *
 * This has to be the directory the launcher already chose. The launcher runs
 * before any JVM exists and reads it out of the environment - $HOME on POSIX,
 * %USERPROFILE% on Windows - while Java's user.home on Linux comes from the
 * passwd entry instead. Anything that sets HOME without editing /etc/passwd
 * makes those two differ, and a container image, a systemd unit and
 * "sudo -u" all do.
 */
class TestHomeDir {

	@Test
	void theEnvironmentIsPreferredOverUserHome() {
		assertEquals(Paths.get("/home/app"), Settings.homeDir("/home/app", "/nonexistent"));
	}

	@Test
	void userHomeIsUsedWhenTheEnvironmentSaysNothing() {
		assertEquals(Paths.get("/home/real"), Settings.homeDir(null, "/home/real"));
		assertEquals(Paths.get("/home/real"), Settings.homeDir("", "/home/real"));
		assertEquals(Paths.get("/home/real"), Settings.homeDir("   ", "/home/real"));
	}

	/**
	 * A relative HOME would put the cache wherever the run happened to start,
	 * so every directory would get one. It is not what the launcher did with
	 * it either, which is the thing this is trying to agree with.
	 */
	@Test
	void aRelativeHomeIsNotUsed() {
		assertEquals(Paths.get("/home/real"), Settings.homeDir("relative/path", "/home/real"));
	}
}
