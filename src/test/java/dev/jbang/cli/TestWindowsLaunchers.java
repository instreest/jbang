package dev.jbang.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Functional tests for the Windows launcher (jbang.cmd) using a fake jbang.jar:
 * exit codes must be propagated, output must be shown and the command JBang
 * asks to be executed (exit code 255) must be run by the launcher. jbang.cmd is
 * self-contained, so there is no PowerShell launcher to hand over to.
 */
@EnabledOnOs(OS.WINDOWS)
class TestWindowsLaunchers extends AbstractScriptTest {

	private Path binDir;
	private Path envFile;

	@BeforeEach
	void setupLaunchers() throws IOException {
		binDir = Files.createDirectories(tempDir.resolve("bin"));
		Files.copy(CMD_SCRIPT, binDir.resolve("jbang.cmd"));
		createFakeJar(binDir.resolve("jbang.jar"));
		envFile = tempDir.resolve("env.txt");
	}

	@Test
	void cmdPropagatesExitCodeAndOutput() throws Exception {
		RunResult result = runLauncher(cmdLauncher(), "exit", "3");
		assertEquals(3, result.exitCode, result.stderr);
		assertTrue(result.stdout.contains("some output"), result.stdout);
		assertEquals(Arrays.asList("cmd", binDir.resolve("jbang.cmd").toString()), Files.readAllLines(envFile));
	}

	@Test
	void cmdExecutesGeneratedCommand() throws Exception {
		RunResult result = runLauncher(cmdLauncher(), "exec", "echo executed by cmd");
		assertEquals(0, result.exitCode, result.stderr);
		assertTrue(result.stdout.contains("executed by cmd"), result.stdout);

		result = runLauncher(cmdLauncher(), "exec", "cmd /c exit 5");
		assertEquals(5, result.exitCode, result.stderr);
	}

	@Test
	void cmdWarnsAndFallsBackToJarWithoutNativeBinary() throws Exception {
		RunResult result = runLauncher(cmdLauncher(), "JBANG_USE_NATIVE", "true", "exit", "3");
		assertEquals(3, result.exitCode, result.stderr);
		assertTrue(result.stderr.contains("WARNING: JBang native binary"), result.stderr);
		assertTrue(result.stdout.contains("some output"), result.stdout);
	}

	@Test
	void cmdRunsNativeBinaryDirectly() throws Exception {
		// A stand-in for jbang.bin.exe: a copy of jbang.cmd would recurse, so use cmd.exe itself
		Files.copy(Paths.get(System.getenv("ComSpec")), binDir.resolve("jbang.bin.exe"));
		RunResult result = runLauncher(cmdLauncher(), "JBANG_USE_NATIVE", "true", "/c", "echo native & exit 6");
		assertEquals(6, result.exitCode, result.stderr);
		assertTrue(result.stdout.contains("native"), result.stdout);
	}


	@Test
	void cmdIgnoresOldJavaHome() throws Exception {
		// With JAVA_HOME rejected and no JDK of its own, jbang.cmd tries to
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
	 * Makes the running JDK available as JBANG_DIR\currentjdk (as jbang.jar
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
		return new ArrayList<>(Arrays.asList("cmd.exe", "/c", binDir.resolve("jbang.cmd").toString()));
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
		env.put("JBANG_TEST_ENV_FILE", envFile.toString());
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

	private static void createFakeJar(Path jar) throws IOException {
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FakeJBang.class.getName());
		String classResource = FakeJBang.class.getName().replace('.', '/') + ".class";
		try (InputStream input = FakeJBang.class.getClassLoader().getResourceAsStream(classResource);
				JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
			if (input == null) {
				throw new IOException("Could not find test class: " + classResource);
			}
			output.putNextEntry(new JarEntry(classResource));
			input.transferTo(output);
			output.closeEntry();
		}
	}

	/**
	 * Stand-in for JBang: records the environment the launcher set up and then
	 * either exits with the given code ("exit N") or asks the launcher to run a
	 * command ("exec CMD", exit code 255).
	 */
	public static class FakeJBang {
		public static void main(String[] args) throws IOException {
			Path envFile = Paths.get(System.getenv("JBANG_TEST_ENV_FILE"));
			Files.write(envFile, Arrays.asList(System.getenv("JBANG_RUNTIME_SHELL"),
					System.getenv("JBANG_LAUNCH_CMD")), StandardCharsets.UTF_8);
			if ("exec".equals(args[0])) {
				System.out.println(args[1]);
				System.exit(255);
			} else {
				System.out.println("some output");
				System.exit(Integer.parseInt(args[1]));
			}
		}
	}
}
