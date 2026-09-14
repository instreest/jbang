package io.github.instreest.jkite.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Runs a script end to end through jkite's main class: the script's stdin,
 * stdout, stderr and exit status must be those of the jkite process, and
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
	void scriptStreamsAndExitStatusAreThoseOfJkite() throws Exception {
		Path script = tempDir.resolve("Echo.java");
		Files.write(script, SCRIPT.getBytes(StandardCharsets.UTF_8));
		Path stdin = tempDir.resolve("stdin.txt");
		Files.write(stdin, "hello from stdin\n".getBytes(StandardCharsets.UTF_8));

		RunResult result = runProcess(jkite(script.toString(), "a b", "c"), env(), stdin);

		assertEquals(7, result.exitCode, result.stderr);
		assertTrue(result.stdout.contains("stdin: hello from stdin"), result.stdout);
		assertTrue(result.stdout.contains("args: a b,c"), result.stdout);
		assertTrue(result.stderr.contains("to stderr"), result.stderr);
	}

	@Test
	void optionsAreAcceptedAnywhereBeforeTheScriptAndNeverAfterIt() throws Exception {
		Path script = tempDir.resolve("Echo.java");
		Files.write(script, SCRIPT.getBytes(StandardCharsets.UTF_8));
		Path stdin = tempDir.resolve("stdin.txt");
		Files.write(stdin, "in\n".getBytes(StandardCharsets.UTF_8));

		// options in any order before the script; -R and --verbose after the
		// script are the script's, and so is everything after --
		List<String> cmd = new ArrayList<>(java());
		cmd.addAll(Arrays.asList("--offline", "--quiet", "-Dk=v", "-R-Xmx64m", "--", script.toString(), "-Rx",
				"--verbose", "--", "-"));
		RunResult result = runProcess(cmd, env(), stdin);

		assertEquals(7, result.exitCode, result.stderr);
		assertTrue(result.stdout.contains("args: -Rx,--verbose,--,-"), result.stdout);
		assertFalse(result.stderr.contains("[jkite]"), result.stderr); // --quiet took effect
	}

	@Test
	void anOptionThatOverridesTheScriptsDirectivesIsRefused() throws Exception {
		Path script = tempDir.resolve("Echo.java");
		Files.write(script, SCRIPT.getBytes(StandardCharsets.UTF_8));

		// what a script needs is declared in the script, not on the command line
		RunResult result = runProcess(jkite("--java", "17", script.toString()), env());

		assertEquals(2, result.exitCode, result.stderr);
		assertTrue(result.stderr.contains("Unknown option: --java"), result.stderr);
	}

	@Test
	void aDirectoryIsRejectedAsInvalidInput() throws Exception {
		RunResult result = runProcess(jkite(tempDir.toString()), env());
		assertEquals(2, result.exitCode, result.stderr);
		assertTrue(result.stderr.contains("is a directory"), result.stderr);
	}

	@Test
	void bashLauncherRunsTheBootstrapScriptWithoutItsExecuteBit() throws Exception {
		requireBash();
		Path launcher = bashLauncherWithJar();
		Path bootstrap = launcher.resolveSibling("jkite-bootstrap-jdk");
		bootstrap.toFile().setExecutable(false);
		Map<String, String> env = env();
		// no usable JDK anywhere, so the launcher has to run the bootstrap
		// script, which fails fast at the unreachable address it is pinned to
		env.remove("JAVA_HOME");
		env.put("PATH", pathWithoutJava());
		env.put("JKITE_DOWNLOAD_RETRY", "0");
		env.put("no_proxy", "localhost,127.0.0.1");
		env.put("NO_PROXY", "localhost,127.0.0.1");
		RunResult result = runProcess(bashCmd(launcher, "exit", "3"), env);
		assertTrue(result.stderr.contains("Error downloading the JDK"), result.stderr);
		assertFalse(result.stderr.contains("Permission denied"), result.stderr);
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

	/**
	 * A jar in a build directory is the jar those inputs produce, and jkite says
	 * so by recording its fingerprint next to it. A jar that is not that one is
	 * built again rather than handed to java.
	 *
	 * The jar this test damages still opens as a jar - the manifest and the
	 * entries are all readable - so every other check passes it. Only java
	 * refuses it, with "Invalid or corrupt jarfile", which is what a run would
	 * have ended in.
	 */
	@Test
	void aCachedJarThatIsNotTheOneBuiltThereIsBuiltAgain() throws Exception {
		Path script = tempDir.resolve("Hello.java");
		Files.write(script, ("public class Hello { public static void main(String... a) {"
				+ " System.out.println(\"hello from the jar\"); } }\n").getBytes(StandardCharsets.UTF_8));
		Map<String, String> env = env();

		RunResult first = runProcess(jkite(script.toString()), env);
		assertEquals(0, first.exitCode, first.stderr);
		assertTrue(first.stdout.contains("hello from the jar"), first.stdout);

		Path jar = builtJar();
		Files.write(jar, "x".getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);

		RunResult second = runProcess(jkite(script.toString()), env);

		assertEquals(0, second.exitCode, second.stderr);
		assertTrue(second.stdout.contains("hello from the jar"), second.stdout);
		assertTrue(second.stderr.contains("is not the jar that was built there"), second.stderr);
	}

	/** The one jar under the cache directory this test's runs share. */
	private Path builtJar() throws Exception {
		Path jars = tempSubDir("cache-run").resolve("jars");
		try (Stream<Path> dirs = Files.list(jars)) {
			return dirs.map(d -> d.resolve("Hello.jar"))
				.filter(Files::isRegularFile)
				.findFirst()
				.orElseThrow(() -> new AssertionError("no jar was built under " + jars));
		}
	}

	/** jkite's main class on this JVM, the test classpath included. */
	private static List<String> java() {
		return Arrays.asList(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
				"-cp", System.getProperty("java.class.path"), "io.github.instreest.jkite.Main");
	}

	private static List<String> jkite(String... args) {
		List<String> cmd = new ArrayList<>(java());
		cmd.add("--offline");
		cmd.addAll(Arrays.asList(args));
		return cmd;
	}

	private Map<String, String> env() {
		Map<String, String> env = new HashMap<>(baseBashEnv("run"));
		env.put("JAVA_HOME", System.getProperty("java.home"));
		return env;
	}
}
