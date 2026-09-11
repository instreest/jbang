package dev.jbang.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Runs a script end to end through jbanglite's main class: the script's stdin,
 * stdout, stderr and exit status must be those of the jbanglite process, and
 * the bash launcher must pass them through unchanged as well.
 */
class TestRun extends AbstractScriptTest {

	private static final String SCRIPT = String.join("\n",
			"public class Echo {",
			"  public static void main(String... args) throws Exception {",
			"    String line = new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine();",
			"    System.out.println(\"stdin: \" + line);",
			"    System.out.println(\"args: \" + String.join(\",\", args));",
			"    System.err.println(\"to stderr\");",
			"    System.exit(7);",
			"  }",
			"}", "");

	@Test
	void scriptStreamsAndExitStatusAreThoseOfJbanglite() throws Exception {
		Path script = tempDir.resolve("Echo.java");
		Files.write(script, SCRIPT.getBytes(StandardCharsets.UTF_8));
		Path stdin = tempDir.resolve("stdin.txt");
		Files.write(stdin, "hello from stdin\n".getBytes(StandardCharsets.UTF_8));

		List<String> cmd = new ArrayList<>(Arrays.asList(
				Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp", System.getProperty("java.class.path"),
				"dev.jbang.Main", "--offline", script.toString(), "a b", "c"));
		RunResult result = runProcess(cmd, env(), stdin);

		assertEquals(7, result.exitCode, result.stderr);
		assertTrue(result.stdout.contains("stdin: hello from stdin"), result.stdout);
		assertTrue(result.stdout.contains("args: a b,c"), result.stdout);
		assertTrue(result.stderr.contains("to stderr"), result.stderr);
	}

	@Test
	void bashLauncherPassesStreamsAndExitStatusThrough() throws Exception {
		requireBash();
		Path launcher = bashLauncherWithJar();
		RunResult result = runProcess(bashCmd(launcher, "exit", "3"), env());
		assertEquals(3, result.exitCode, result.stderr);
		assertTrue(result.stdout.contains("some output"), result.stdout);
		assertTrue(result.stderr.contains("some error output"), result.stderr);
	}

	private Map<String, String> env() {
		Map<String, String> env = new HashMap<>(baseBashEnv("run"));
		env.put("JAVA_HOME", System.getProperty("java.home"));
		return env;
	}
}
