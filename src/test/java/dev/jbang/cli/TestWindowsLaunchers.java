package dev.jbang.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Functional tests for the Windows launchers (jbang.cmd and jbang.ps1) using a
 * fake jbang.jar: exit codes must be propagated, output must be shown and the
 * command JBang asks to be executed (exit code 255) must be run by the launcher.
 */
@EnabledOnOs(OS.WINDOWS)
class TestWindowsLaunchers extends AbstractScriptTest {

	private Path binDir;
	private Path envFile;

	@BeforeEach
	void setupLaunchers() throws IOException {
		requirePowerShell();
		binDir = Files.createDirectories(tempDir.resolve("bin"));
		Files.copy(PS1_SCRIPT, binDir.resolve("jbang.ps1"));
		Files.copy(PS1_SCRIPT.resolveSibling("jbang.cmd"), binDir.resolve("jbang.cmd"));
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
	void ps1PropagatesExitCodeAndOutput() throws Exception {
		RunResult result = runLauncher(ps1Launcher(), "exit", "3");
		assertEquals(3, result.exitCode, result.stderr);
		assertTrue(result.stdout.contains("some output"), result.stdout);
		assertEquals(Arrays.asList("powershell", binDir.resolve("jbang.ps1").toString()),
				Files.readAllLines(envFile));
	}

	@Test
	void ps1ExecutesGeneratedCommand() throws Exception {
		RunResult result = runLauncher(ps1Launcher(), "exec", "cmd /c exit 5");
		assertEquals(5, result.exitCode, result.stderr);
	}

	private List<String> cmdLauncher() {
		return new ArrayList<>(Arrays.asList("cmd.exe", "/c", binDir.resolve("jbang.cmd").toString()));
	}

	private List<String> ps1Launcher() {
		return new ArrayList<>(Arrays.asList(psCommand, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
				binDir.resolve("jbang.ps1").toString()));
	}

	private RunResult runLauncher(List<String> command, String... args) throws Exception {
		command.addAll(Arrays.asList(args));
		Map<String, String> env = new HashMap<>(System.getenv());
		env.put("JAVA_HOME", System.getProperty("java.home"));
		env.put("JBANG_DIR", tempDir.resolve("jbang-home").toString());
		env.put("JBANG_CACHE_DIR", tempDir.resolve("cache").toString());
		env.put("JBANG_NO_VERSION_CHECK", "true");
		env.put("JBANG_TEST_ENV_FILE", envFile.toString());
		return runProcess(command, env);
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
