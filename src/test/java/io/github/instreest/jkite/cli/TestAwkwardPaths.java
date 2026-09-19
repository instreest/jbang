package io.github.instreest.jkite.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Paths nobody tests on, which is where anyone who is not writing in English
 * lives. jkite is installed into a project directory and run from wherever
 * that project is checked out, and a developer's checkout sits under their
 * profile - so a name with a non-ASCII character in it is not an edge case,
 * it is Tuesday in most of the world.
 *
 * The other one is length. Windows stops at 260 characters unless long paths
 * are turned on for the machine, and a cache of unpacked JDKs under a profile
 * directory is exactly the shape that gets there.
 *
 * These were written because a review said this was unexamined and I could
 * not examine it from a Linux machine. The Windows ones run on the Windows
 * job, which is where the answer is.
 */
class TestAwkwardPaths extends AbstractScriptTest {

	/**
	 * Two names rather than one, because how far a machine gets depends on
	 * what its filename encoding can express, and the two answers are worth
	 * separating.
	 *
	 * LATIN is inside every Western ANSI code page, so a Windows machine in
	 * en-US - which is what the CI runner is - can hold it. It carries a
	 * combining accent, which is where macOS normalising filenames differs
	 * from everyone else not doing so.
	 *
	 * WIDE cannot be held by such a machine at all: Japanese needs code page
	 * 932 and Cyrillic 1251. On a Japanese Windows machine, which is a machine
	 * jkite is meant to work on, the CJK part is ordinary.
	 *
	 * Neither carries an emoji. That is not squeamishness - see
	 * anEmojiInThePathIsTheJdkGivingUp below, which is where the one character
	 * class that does not work is pinned down.
	 */
	private static final String LATIN = "cafe\u0301-Un\u00efcode\u0301";
	private static final String WIDE = "\u30d7\u30ed\u30b8\u30a7\u30af\u30c8-\u041f\u0440\u043e\u0435\u043a\u0442";
	private static final String EMOJI = "tools-\ud83d\udee0";

	/**
	 * Whether this JVM can even name such a file. sun.jnu.encoding decides,
	 * and it follows the machine's locale: a container with no LANG set gets
	 * ASCII and cannot express any of this - not a jkite limitation, and not
	 * something to fail a build over, but worth saying out loud rather than
	 * passing quietly.
	 */
	private static void requireExpressible(String name) {
		try {
			Paths.get(name);
		} catch (InvalidPathException e) {
			abort("this JVM cannot name a file '" + name + "': sun.jnu.encoding is "
					+ System.getProperty("sun.jnu.encoding") + ", so there is nothing to test here");
		}
	}

	private Path installInto(Path dir, Path script) throws IOException {
		Files.createDirectories(dir);
		boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
		Path launcher = dir.resolve(windows ? "jkite.cmd" : "jkite");
		Files.copy(script, launcher, StandardCopyOption.REPLACE_EXISTING);
		String bootstrap = windows ? "jkite-bootstrap-jdk.cmd" : "jkite-bootstrap-jdk";
		Files.copy(script.resolveSibling(bootstrap), dir.resolve(bootstrap),
				StandardCopyOption.REPLACE_EXISTING);
		Files.write(dir.resolve("jkite.properties"),
				("bootstrapJdkVersion=99.0.0\n"
						+ "bootstrapJdkUrl." + indexPlatform() + "=https://127.0.0.1:1/nowhere/jdk\n"
						+ "bootstrapJdkSha256Sum." + indexPlatform() + "=00\n")
					.getBytes(StandardCharsets.UTF_8));
		createFakeJar(dir.resolve("jkite.jar"));
		return launcher;
	}

	private Map<String, String> env(Path home) {
		Map<String, String> env = new HashMap<>(System.getenv());
		env.put("JAVA_HOME", System.getProperty("java.home"));
		env.put("JKITE_DIR", home.resolve(".jkite").toString());
		env.put("JKITE_CACHE_DIR", home.resolve(".jkite/cache").toString());
		env.put("JKITE_DOWNLOAD_RETRY", "0");
		return env;
	}

	private List<String> command(Path launcher, String... args) {
		List<String> cmd = new ArrayList<>();
		if (launcher.getFileName().toString().endsWith(".cmd")) {
			cmd.addAll(Arrays.asList("cmd.exe", "/c", launcher.toString()));
		} else {
			cmd.addAll(Arrays.asList("bash", launcher.toString()));
		}
		cmd.addAll(Arrays.asList(args));
		return cmd;
	}

	/**
	 * The exit code is the point: the launcher has to find its jar, start a
	 * JVM and hand back what the script returned, with every one of those
	 * paths carrying characters the platform's legacy encoding may not have.
	 */
	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void aProjectInAnAccentedDirectoryRuns() throws Exception {
		runInADirectoryNamed(LATIN);
	}

	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void aProjectInAJapaneseOrCyrillicDirectoryRuns() throws Exception {
		runInADirectoryNamed(WIDE);
	}

	/**
	 * What Windows does instead, recorded rather than wished away.
	 *
	 * Installed under a directory whose name the console's active code page
	 * cannot hold, jkite.cmd cannot find its own jar: java is handed
	 * "...\\??????-??????\\jkite\\jkite.jar" and says it cannot access it. The
	 * path is mangled between the batch file and java, not before - the test
	 * hands cmd.exe a correct UTF-16 command line, and Windows itself has no
	 * trouble with the directory. What cannot hold it is %~dp0, which comes
	 * back through the code page the console happens to be in: 1252 on the CI
	 * runner, 932 on a Japanese machine, and never all of Unicode.
	 *
	 * The cache directory is fine, which is the useful half of this: it
	 * arrives in an environment variable, and those are UTF-16 all the way.
	 * See aCacheDirectoryWithANonAsciiNameWorks, which passes here.
	 *
	 * The candidate fix is "chcp 65001" at the top of jkite.cmd, and it needs
	 * judging on a real Windows machine rather than from a CI log: it changes
	 * the console for whatever runs afterwards, and batch parsing under
	 * UTF-8 has its own history. Until then this test says what happens, and
	 * fails the day it stops happening.
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void aNonAsciiInstallDirectoryIsMangledByTheConsoleCodePage() throws Exception {
		Path base = Files.createDirectories(tempDir.resolve(WIDE));
		Path launcher = installInto(base.resolve("jkite"), CMD_SCRIPT);

		RunResult result = runProcess(command(launcher, "exit", "3"), env(base));

		assertEquals(1, result.exitCode,
				"jkite.cmd now works under a non-ASCII path; delete this test, enable the two "
						+ "above on Windows and take the note out of README: " + result.stderr);
		assertTrue(result.stderr.contains("Unable to access jarfile"), result.stderr);
		assertTrue(result.stderr.contains("?"), "the path was not mangled after all: " + result.stderr);
	}

	private void runInADirectoryNamed(String name) throws Exception {
		requireBashUnlessWindows();
		requireExpressible(name);
		Path base = Files.createDirectories(tempDir.resolve(name));
		Path launcher = installInto(base.resolve("jkite"), scriptForThisPlatform());

		RunResult result = runProcess(command(launcher, "exit", "3"), env(base));

		assertEquals(3, result.exitCode, "stdout: " + result.stdout + "\nstderr: " + result.stderr);
	}

	/** And the cache it writes into, which is a second set of the same paths. */
	@Test
	void aCacheDirectoryWithANonAsciiNameWorks() throws Exception {
		requireBashUnlessWindows();
		requireExpressible(LATIN);
		Path base = Files.createDirectories(tempDir.resolve("plain"));
		Path launcher = installInto(base.resolve("jkite"), scriptForThisPlatform());
		Path home = Files.createDirectories(tempDir.resolve(LATIN + "-home"));

		RunResult result = runProcess(command(launcher, "exit", "3"), env(home));

		assertEquals(3, result.exitCode, "stdout: " + result.stdout + "\nstderr: " + result.stderr);
	}

	/**
	 * Long enough to be past 260 characters, which is where Windows stops
	 * unless the machine has long paths turned on. If this fails on the
	 * Windows job, that is the answer to the question and it belongs in
	 * README rather than being quietly unknown.
	 */
	@Test
	void aDeeplyNestedProjectRuns() throws Exception {
		requireBashUnlessWindows();
		Path base = tempDir;
		while (base.toString().length() < 280) {
			base = base.resolve("a-directory-with-a-long-enough-name");
		}
		Files.createDirectories(base);
		Path launcher = installInto(base.resolve("jkite"), scriptForThisPlatform());
		assertTrue(launcher.toString().length() > 260,
				"the path is not long enough to be testing anything: " + launcher.toString().length());

		RunResult result = runProcess(command(launcher, "exit", "3"), env(base));

		assertEquals(3, result.exitCode, "stdout: " + result.stdout + "\nstderr: " + result.stderr);
	}

	/** A script whose own file name is not ASCII, which is what gets compiled. */
	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void aScriptWithANonAsciiNameIsFound() throws Exception {
		requireBash();
		requireExpressible(WIDE);
		Path launcher = bashLauncherWithJar();
		Path script = tempDir.resolve("\u30ec\u30dd\u30fc\u30c8.java");
		Files.write(script, "class Report {}".getBytes(StandardCharsets.UTF_8));

		// "exit 3" is the fake jar's own protocol and has to be argv[0..1];
		// the script's name reaches the launcher through the file it opens
		RunResult result = runProcess(
				Arrays.asList("bash", launcher.toString(), "exit", "3"),
				env(tempDir));

		assertEquals(3, result.exitCode, "stdout: " + result.stdout + "\nstderr: " + result.stderr);
		assertTrue(Files.exists(script), "the script vanished: " + script);
	}

	/**
	 * The one character class that does not work, pinned down so that it is a
	 * known limit rather than a mystery bug report.
	 *
	 * Installed under a directory whose name contains a character outside the
	 * Basic Multilingual Plane, "java -jar" fails before main() with a JNI
	 * error and "Error decoding percent encoded characters": the launcher is
	 * fine, and the JDK cannot turn that path into the file: URL it puts on
	 * its own class path. I narrowed it by hand, one character class at a
	 * time, against a jar of my own: Japanese, Cyrillic and a combining accent
	 * all run, and only the emoji does this.
	 *
	 * It is the JDK's, not jkite's, and there is nothing jkite can do from the
	 * shell script that runs java. What jkite can do is know about it, so this
	 * asserts the failure. If a future JDK fixes it this test fails, which is
	 * the notification.
	 */
	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void anEmojiInThePathIsTheJdkGivingUp() throws Exception {
		requireBash();
		requireExpressible(EMOJI);
		Path base = Files.createDirectories(tempDir.resolve(EMOJI));
		Path launcher = installInto(base.resolve("jkite"), BASH_SCRIPT);

		RunResult result = runProcess(command(launcher, "exit", "3"), env(base));

		assertEquals(1, result.exitCode, "the JDK now runs a jar under a non-BMP path; "
				+ "this test and the note in README can go: " + result.stderr);
		assertTrue(result.stderr.contains("Error decoding percent encoded characters"),
				"it failed, but not the way it used to: " + result.stderr);
	}

	/**
	 * Where the path is lost, asked in a way that cannot be answered by an
	 * encoding artefact.
	 *
	 * The symptom is java saying "Unable to access jarfile ...??????...", and
	 * those question marks prove nothing on their own: java writes that
	 * message to stderr through the console code page, so a path that arrived
	 * perfectly intact would still be printed with them. Two different faults
	 * produce the same picture - cmd.exe losing the name when it expands
	 * %~dp0, or the name surviving as far as java and being lost there - and
	 * they have different fixes. chcp only helps the first.
	 *
	 * So this asks cmd.exe a yes-or-no question instead of reading anything
	 * back: from inside a batch file in that directory, does the jar next to
	 * me exist? "if exist" goes through the filesystem, not through a code
	 * page, and writing FOUND or MISSING is pure ASCII either way. FOUND means
	 * %~dp0 is intact inside cmd and the loss happens on the way to java;
	 * MISSING means cmd never had it.
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void whereTheNonAsciiPathIsActuallyLost() throws Exception {
		Path base = Files.createDirectories(tempDir.resolve(WIDE));
		Path dir = base.resolve("jkite");
		installInto(dir, CMD_SCRIPT);
		Path probe = dir.resolve("probe.cmd");
		Files.write(probe, ("@echo off\r\n"
				+ "if exist \"%~dp0jkite.jar\" (echo FOUND) else (echo MISSING)\r\n"
				+ "chcp\r\n").getBytes(StandardCharsets.US_ASCII));

		RunResult result = runProcess(Arrays.asList("cmd.exe", "/c", probe.toString()),
				new HashMap<>(System.getenv()));

		// Not an assertion about which one is right - it is a measurement, and
		// the message carries it into the log either way.
		assertTrue(result.stdout.contains("FOUND") || result.stdout.contains("MISSING"),
				"the probe said neither: " + result.stdout + result.stderr);
		assertTrue(result.stdout.contains("FOUND"),
				"cmd.exe cannot see a file next to its own batch file under this name, so %~dp0 is "
						+ "where the name is lost and chcp is the thing to try. Probe said: "
						+ result.stdout.trim());
	}

	private static Path scriptForThisPlatform() {
		return System.getProperty("os.name").toLowerCase().contains("win") ? CMD_SCRIPT : BASH_SCRIPT;
	}

	private void requireBashUnlessWindows() {
		if (!System.getProperty("os.name").toLowerCase().contains("win")) {
			requireBash();
		}
	}
}
