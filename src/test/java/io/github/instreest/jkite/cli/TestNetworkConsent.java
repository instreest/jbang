package io.github.instreest.jkite.cli;

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
 * The launcher fetches the jar and a JDK before any JVM exists, so the gate in
 * the jar cannot cover those two. It asks about them itself, under the same
 * contract the jar uses: JKITE_CONFIRM_DOWNLOADS is auto, always or never,
 * and JKITE_ASSUME_YES or --yes answers yes in advance.
 */
class TestNetworkConsent extends AbstractScriptTest {

	/** A launcher with no jar next to it, pinned at an address nothing answers. */
	private Path launcherWithoutJar() throws IOException {
		Path dir = Files.createDirectories(tempDir.resolve("bin"));
		Path launcher = dir.resolve("jkite");
		Files.copy(BASH_SCRIPT, launcher, StandardCopyOption.REPLACE_EXISTING);
		Files.copy(BASH_SCRIPT.resolveSibling("jkite-bootstrap-jar"), dir.resolve("jkite-bootstrap-jar"),
				StandardCopyOption.REPLACE_EXISTING);
		Files.copy(BASH_SCRIPT.resolveSibling("jkite-bootstrap-jdk"), dir.resolve("jkite-bootstrap-jdk"),
				StandardCopyOption.REPLACE_EXISTING);
		Files.write(dir.resolve("jkite.properties"),
				("distributionVersion=9.9.9\n"
						+ "distributionUrl=https://127.0.0.1:1/nowhere/jkite.jar\n"
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

	/** @param mode null to leave JKITE_CONFIRM_DOWNLOADS unset */
	private Map<String, String> env(String mode) {
		Map<String, String> env = baseBashEnv("consent");
		env.put("JAVA_HOME", System.getProperty("java.home"));
		env.remove("JKITE_ASSUME_YES");
		if (mode == null) {
			env.remove("JKITE_CONFIRM_DOWNLOADS");
		} else {
			env.put("JKITE_CONFIRM_DOWNLOADS", mode);
		}
		return env;
	}

	@Test
	void autoWithNobodyToAskSaysWhatItFetchesAndCarriesOn() throws Exception {
		RunResult result = runProcess(bashCmd(launcher, script.toString()), env(null));

		assertTrue(result.stderr.contains("jkite.jar 9.9.9"),
				"it should say what it is about to download: " + result.stderr);
		assertTrue(result.stderr.contains("Error downloading JKite"),
				"auto carries on when there is nobody to ask: " + result.stderr);
	}

	@Test
	void alwaysWithNobodyToAskFetchesNothing() throws Exception {
		RunResult result = runProcess(bashCmd(launcher, script.toString()), env("always"));

		assertNotEquals(0, result.exitCode);
		assertTrue(result.stderr.contains("jkite.jar 9.9.9"), result.stderr);
		assertTrue(result.stderr.contains("JKITE_CONFIRM_DOWNLOADS=always"),
				"it should name the setting that stopped it: " + result.stderr);
		assertTrue(!result.stderr.contains("Error downloading JKite"),
				"nothing should have been fetched: " + result.stderr);
	}

	@Test
	void neverGoesStraightToTheDownload() throws Exception {
		RunResult result = runProcess(bashCmd(launcher, script.toString()), env("never"));

		assertTrue(result.stderr.contains("Error downloading JKite"), result.stderr);
		assertTrue(!result.stderr.contains("JKite has to download"),
				"never says nothing: " + result.stderr);
	}

	@Test
	void assumeYesAnswersInAdvance() throws Exception {
		Map<String, String> env = env("always");
		env.put("JKITE_ASSUME_YES", "1");
		RunResult result = runProcess(bashCmd(launcher, script.toString()), env);

		assertTrue(result.stderr.contains("Error downloading JKite"),
				"always plus assume-yes downloads: " + result.stderr);
	}

	@Test
	void theYesOptionAnswersInAdvanceToo() throws Exception {
		RunResult result = runProcess(bashCmd(launcher, "--yes", script.toString()), env("always"));

		assertTrue(result.stderr.contains("Error downloading JKite"),
				"--yes reaches the launcher, not only the jar: " + result.stderr);
	}

	@Test
	void anUnknownModeIsIgnoredWithAWarning() throws Exception {
		RunResult result = runProcess(bashCmd(launcher, script.toString()), env("maybe"));

		assertTrue(result.stderr.contains("Ignoring invalid JKITE_CONFIRM_DOWNLOADS"), result.stderr);
		assertTrue(result.stderr.contains("Error downloading JKite"),
				"an unknown mode falls back to auto: " + result.stderr);
	}

	@Test
	void aJarAndAJdkThatAreAlreadyHereAreNotAskedAbout() throws Exception {
		// the fake jar takes a command and an exit code, and exits with it
		RunResult result = runProcess(bashCmd(bashLauncherWithJar(), "exit", "0"), env(null));

		assertEquals(0, result.exitCode, result.stderr);
		assertTrue(!result.stderr.contains("has to download"),
				"nothing was missing, so nothing should have been said: " + result.stderr);
	}

	/**
	 * The Windows launcher has to gate the same downloads under the same
	 * contract; fixing only the bash one would let Windows through. It cannot be
	 * run here, so this reads it.
	 */
	@Test
	void theWindowsLauncherUsesTheSameContract() throws Exception {
		String cmd = new String(Files.readAllBytes(CMD_SCRIPT), StandardCharsets.UTF_8);

		assertTrue(cmd.contains("JKITE_CONFIRM_DOWNLOADS"),
				"jkite.cmd does not look at JKITE_CONFIRM_DOWNLOADS");
		assertTrue(cmd.contains("JKITE_ASSUME_YES"), "jkite.cmd ignores JKITE_ASSUME_YES");
		assertTrue(cmd.contains("Continue? [Y/n]"), "jkite.cmd does not ask before downloading");
		assertTrue(cmd.contains("There is no terminal to ask on"),
				"jkite.cmd does not handle having nobody to ask");
	}
}
