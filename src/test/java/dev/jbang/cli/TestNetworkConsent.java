package dev.jbang.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Nothing is downloaded without the operator agreeing to it. The launcher can
 * see whether the jar and a JDK are here and asks about those; the jar asks
 * about a script's dependencies itself. JBANGLITE_NETWORK decides how: ask
 * (the default), allow, or deny.
 */
class TestNetworkConsent extends AbstractScriptTest {

	/** A launcher with no jar next to it, pinned at an address nothing answers. */
	private Path launcherWithoutJar() throws IOException {
		Path dir = Files.createDirectories(tempDir.resolve("bin"));
		Path launcher = dir.resolve("jbanglite");
		Files.copy(BASH_SCRIPT, launcher, StandardCopyOption.REPLACE_EXISTING);
		Files.copy(BASH_SCRIPT.resolveSibling("jbanglite-bootstrap-jar"), dir.resolve("jbanglite-bootstrap-jar"),
				StandardCopyOption.REPLACE_EXISTING);
		Files.copy(BASH_SCRIPT.resolveSibling("jbanglite-bootstrap-jdk"), dir.resolve("jbanglite-bootstrap-jdk"),
				StandardCopyOption.REPLACE_EXISTING);
		Files.write(dir.resolve("jbanglite.properties"),
				("distributionVersion=9.9.9\n"
						+ "distributionUrl=https://127.0.0.1:1/nowhere/jbanglite.jar\n"
						+ "distributionSha256Sum=00\n"
						+ "bootstrapJdkVersion=99.0.0\n"
						+ "bootstrapJdkUrl." + indexPlatform() + "=https://127.0.0.1:1/nowhere/jdk.tar.gz\n"
						+ "bootstrapJdkSha256Sum." + indexPlatform() + "=00\n")
					.getBytes(StandardCharsets.UTF_8));
		return launcher;
	}

	private Path launcher;
	private Path script;

	@BeforeEach
	void setUp() throws Exception {
		requireBash();
		launcher = launcherWithoutJar();
		script = tempDir.resolve("Hello.java");
		Files.write(script, "class Hello {}".getBytes(StandardCharsets.UTF_8));
	}

	private Map<String, String> env(String mode) {
		Map<String, String> env = baseBashEnv("consent");
		env.put("JAVA_HOME", System.getProperty("java.home"));
		if (mode == null) {
			env.remove("JBANGLITE_NETWORK");
		} else {
			env.put("JBANGLITE_NETWORK", mode);
		}
		return env;
	}

	@Test
	void withoutATerminalItAsksForNothingAndFetchesNothing() throws Exception {
		RunResult result = runProcess(bashCmd(launcher, script.toString()), env(null));

		assertNotEquals(0, result.exitCode);
		assertTrue(result.stderr.contains("jbanglite.jar 9.9.9"),
				"it should say what it wanted to download: " + result.stderr);
		assertTrue(result.stderr.contains("no terminal to ask on"), result.stderr);
		assertTrue(result.stderr.contains("JBANGLITE_NETWORK=allow"),
				"it should say how to permit it: " + result.stderr);
	}

	@Test
	void denyRefusesWithoutAsking() throws Exception {
		RunResult result = runProcess(bashCmd(launcher, script.toString()), env("deny"));

		assertNotEquals(0, result.exitCode);
		assertTrue(result.stderr.contains("JBANGLITE_NETWORK=deny forbids it"), result.stderr);
		assertTrue(result.stderr.contains("jbanglite.jar 9.9.9"), result.stderr);
	}

	@Test
	void anUnknownModeNamesTheThreeThatExist() throws Exception {
		RunResult result = runProcess(bashCmd(launcher, script.toString()), env("maybe"));

		assertNotEquals(0, result.exitCode);
		assertTrue(result.stderr.contains("ask, allow or deny"), result.stderr);
	}

	@Test
	void allowGoesStraightToTheDownload() throws Exception {
		RunResult result = runProcess(bashCmd(launcher, script.toString()), env("allow"));

		assertNotEquals(0, result.exitCode);
		assertTrue(result.stderr.contains("Error downloading JBangLite"),
				"it should have tried the download: " + result.stderr);
		assertTrue(!result.stderr.contains("no terminal to ask on"),
				"it should not have asked: " + result.stderr);
	}

	@Test
	void aJarAndAJdkThatAreAlreadyHereAreNotAskedAbout() throws Exception {
		// the fake jar takes a command and an exit code, and exits with it
		RunResult result = runProcess(bashCmd(bashLauncherWithJar(), "exit", "0"), env(null));

		assertEquals(0, result.exitCode, result.stderr);
		assertTrue(!result.stderr.contains("has to download"),
				"nothing was missing, so nothing should have been asked: " + result.stderr);
	}

	/**
	 * The Windows launcher has to gate the same downloads; fixing only the bash
	 * one would let Windows through. It cannot be run here, so this reads it.
	 */
	@Test
	void theWindowsLauncherAsksTheSameQuestion() throws Exception {
		String cmd = new String(Files.readAllBytes(CMD_SCRIPT), StandardCharsets.UTF_8);

		assertTrue(cmd.contains("JBANGLITE_NETWORK"), "jbanglite.cmd does not look at JBANGLITE_NETWORK");
		assertTrue(cmd.contains("Go ahead? [y/N]"), "jbanglite.cmd does not ask before downloading");
		assertTrue(cmd.contains("There is no terminal to ask on"),
				"jbanglite.cmd does not handle having nobody to ask");
	}
}
