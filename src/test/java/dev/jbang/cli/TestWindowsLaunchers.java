package dev.jbang.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Functional tests for the Windows launcher (jbanglite.cmd) using a fake jbanglite.jar:
 * exit codes and output must pass through unchanged. jbanglite.cmd is
 * self-contained, so there is no PowerShell launcher to hand over to.
 */
@EnabledOnOs(OS.WINDOWS)
class TestWindowsLaunchers extends AbstractScriptTest {

	private Path binDir;

	@BeforeEach
	void setupLaunchers() throws IOException {
		binDir = Files.createDirectories(tempDir.resolve("bin"));
		Files.copy(CMD_SCRIPT, binDir.resolve("jbanglite.cmd"));
		createFakeJar(binDir.resolve("jbanglite.jar"));
	}

	@Test
	void cmdPropagatesExitCodeAndOutput() throws Exception {
		RunResult result = runLauncher(cmdLauncher(), "exit", "3");
		assertEquals(3, result.exitCode, result.stderr);
		assertTrue(result.stdout.contains("some output"), result.stdout);
		assertTrue(result.stderr.contains("some error output"), result.stderr);
	}




	@Test
	void cmdIgnoresOldJavaHome() throws Exception {
		// With JAVA_HOME rejected and no JDK of its own, jbanglite.cmd tries to
		// download one; an unreachable JVM index makes that fail quickly.
		RunResult result = runLauncher(cmdLauncher(), "JAVA_HOME", createFakeJdk("1.8.0_292"),
				"JBANG_JVM_INDEX_BASEURL", "http://localhost:1/nowhere", "JBANG_DOWNLOAD_RETRY", "0", "exit", "3");
		assertTrue(result.exitCode != 0, result.stderr);
		assertTrue(result.stderr.contains("older than Java 11"), result.stderr);
		assertTrue(result.stderr.contains("Could not read the JVM index"), result.stderr);
	}

	@Test
	void cmdPrefersBootstrapJdkOverJavaHome() throws Exception {
		linkCachedJdk();
		RunResult result = runLauncher(cmdLauncher(), "JAVA_HOME", createFakeJdk("1.8.0_292"), "exit", "3");
		assertEquals(3, result.exitCode, result.stderr);
		assertFalse(result.stderr.contains("JAVA_HOME"), result.stderr);
	}


	@Test
	void cmdPrefersCurrentJdkOverJavaHome() throws Exception {
		linkCurrentJdk();
		RunResult result = runLauncher(cmdLauncher(), "JAVA_HOME", createFakeJdk("1.8.0_292"), "exit", "3");
		assertEquals(3, result.exitCode, result.stderr);
		assertFalse(result.stderr.contains("JAVA_HOME"), result.stderr);
	}


	@Test
	void cmdIgnoresJavaHomeOfUnknownVersion() throws Exception {
		RunResult result = runLauncher(cmdLauncher(), "JAVA_HOME", createFakeJdk(null),
				"JBANG_JVM_INDEX_BASEURL", "http://localhost:1/nowhere", "JBANG_DOWNLOAD_RETRY", "0", "exit", "3");
		assertTrue(result.exitCode != 0, result.stderr);
		assertTrue(result.stderr.contains("could not be determined"), result.stderr);
	}




	/**
	 * Makes the running JDK available as JBANG_CACHE_DIR\jdks\bootstrap (as if
	 * the launcher had downloaded it) so the launchers have a JDK to fall back
	 * to when JAVA_HOME is rejected, without downloading one.
	 */
	private void linkCachedJdk() throws Exception {
		Path jdks = Files.createDirectories(tempDir.resolve("cache/jdks"));
		link(jdks.resolve("bootstrap"), Paths.get(System.getProperty("java.home")));
	}

	/**
	 * Makes the running JDK available as JBANG_DIR\currentjdk (as jbanglite.jar
	 * does for the JDK a script asks for with //JAVA).
	 */
	private void linkCurrentJdk() throws Exception {
		Path jbangHome = Files.createDirectories(tempDir.resolve("jbang-home"));
		link(jbangHome.resolve("currentjdk"), Paths.get(System.getProperty("java.home")));
	}

	private void link(Path link, Path target) throws Exception {
		RunResult result = runProcess(
				Arrays.asList("cmd.exe", "/c", "mklink", "/j", link.toString(), target.toString()), System.getenv());
		assertEquals(0, result.exitCode, result.stderr);
	}


	private List<String> cmdLauncher() {
		return new ArrayList<>(Arrays.asList("cmd.exe", "/c", binDir.resolve("jbanglite.cmd").toString()));
	}


	private RunResult runLauncher(List<String> command, String... args) throws Exception {
		Map<String, String> env = new HashMap<>(System.getenv());
		int i = 0;
		// leading "NAME", "value" pairs are environment variables
		while (args.length - i > 2 && (args[i].startsWith("JBANG_") || args[i].equals("JAVA_HOME"))) {
			env.put(args[i], args[i + 1]);
			i += 2;
		}
		command.addAll(Arrays.asList(args).subList(i, args.length));
		env.put("JAVA_HOME", System.getProperty("java.home"));
		env.put("JBANG_DIR", tempDir.resolve("jbang-home").toString());
		env.put("JBANG_CACHE_DIR", tempDir.resolve("cache").toString());
		env.put("JBANG_NO_VERSION_CHECK", "true");
		return runProcess(command, env);
	}

	/**
	 * Creates a directory that looks like a JDK of the given version (no
	 * 'release' file when null) but whose java.exe would fail: the launchers must
	 * not pick it.
	 */
	private String createFakeJdk(String version) throws IOException {
		Path jdk = Files.createDirectories(tempDir.resolve("oldjdk"));
		Files.createDirectories(jdk.resolve("bin"));
		Files.write(jdk.resolve("bin/javac.exe"), new byte[0]);
		Files.write(jdk.resolve("bin/java.exe"), new byte[0]);
		if (version != null) {
			Files.write(jdk.resolve("release"),
					Arrays.asList("JAVA_VERSION=\"" + version + "\"", "OS_NAME=\"Windows\""), StandardCharsets.UTF_8);
		}
		return jdk.toString();
	}
}
