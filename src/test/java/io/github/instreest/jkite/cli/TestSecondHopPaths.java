package io.github.instreest.jkite.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * The second hop, which the launcher tests never cross.
 *
 * A run is two process starts, not one. The launcher starts jkite.jar, and
 * jkite.jar starts a JVM for the script - a different command line, built by
 * CmdGenerator, carrying the built jar and every dependency jar on
 * -classpath. jkite.cmd was taught to hand java an 8.3 path because java.exe
 * converts its command line to the machine's ANSI code page; nothing was done
 * about the second hop, and the same conversion happens there.
 *
 * What makes it worth measuring rather than reasoning about is where the
 * non-ASCII comes from. It is not only an unusual profile name:
 * Project.getBuildDir() names the build directory after the script -
 * cache/jars/&lt;script&gt;.&lt;hash&gt; - so a script called レポート.java puts its own
 * name into the path of the jar that java is then handed. A developer naming
 * a script in their own language is the ordinary case, not the edge one.
 *
 * These run everywhere. On Linux and macOS they say what the behaviour should
 * be; on Windows they say what it is.
 */
class TestSecondHopPaths extends AbstractScriptTest {

	private static final String JAPANESE = "レポート";

	private static final Path JKITE_JAR = Paths.get(System.getProperty("jkite.jar", "build/libs/jkite.jar"));

	private Map<String, String> env(Path jkiteDir) {
		Map<String, String> env = new HashMap<>(System.getenv());
		env.put("JAVA_HOME", System.getProperty("java.home"));
		env.put("JKITE_DIR", jkiteDir.toString());
		env.remove("JKITE_CACHE_DIR");
		// nothing here declares //DEPS, so a download would be a bug, not a wait
		env.put("JKITE_CONFIRM_DOWNLOADS", "always");
		return env;
	}

	private RunResult runJkite(Path script, Path jkiteDir) throws Exception {
		assertTrue(Files.isRegularFile(JKITE_JAR),
				"the shaded jar is not built, so this is testing nothing: " + JKITE_JAR);
		return runProcess(Arrays.asList(System.getProperty("java.home") + "/bin/java",
				"-jar", JKITE_JAR.toAbsolutePath().toString(), script.toString()), env(jkiteDir));
	}

	/**
	 * A script that prints one word, so that running it is visible.
	 *
	 * The class is not public, which is what lets the file be called anything:
	 * javac insists a public class sit in a file named after it, so a script
	 * named レポート.java would have to declare a class called レポート. That is
	 * a rule of Java's and has nothing to do with what is being measured here,
	 * so it is kept out of the way.
	 */
	private Path script(Path dir, String name) throws IOException {
		Files.createDirectories(dir);
		Path file = dir.resolve(name + ".java");
		Files.write(file, ("class Report {\n"
				+ "  public static void main(String[] a) { System.out.println(\"ran\"); }\n"
				+ "}\n").getBytes(StandardCharsets.UTF_8));
		return file;
	}

	/** The baseline: everything ASCII, so a failure below is about the name. */
	@Test
	void anOrdinaryScriptRuns() throws Exception {
		Path script = script(tempDir.resolve("plain"), "Report");

		RunResult result = runJkite(script, tempDir.resolve("home"));

		assertEquals(0, result.exitCode, result.stdout + result.stderr);
		assertTrue(result.stdout.contains("ran"), result.stdout + result.stderr);
	}

	/**
	 * The ordinary case for anyone not writing in English. The script's name
	 * becomes part of the build directory, so this is the path java is handed
	 * on the second hop whatever the machine is called.
	 */
	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void aScriptNamedInJapaneseRuns() throws Exception {
		Path script = script(tempDir.resolve("plain2"), JAPANESE);

		RunResult result = runJkite(script, tempDir.resolve("home2"));

		assertEquals(0, result.exitCode,
				"a script named in Japanese did not run. Its name is part of the built jar's path, "
						+ "which is what jkite hands java on the second hop:\n"
						+ result.stdout + result.stderr);
		assertTrue(result.stdout.contains("ran"), result.stdout + result.stderr);
	}

	/** And the other way in: an ASCII script under a cache that is not. */
	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void anOrdinaryScriptUnderAJapaneseCacheRuns() throws Exception {
		Path script = script(tempDir.resolve("plain3"), "Report");

		RunResult result = runJkite(script, tempDir.resolve(JAPANESE + "-home"));

		assertEquals(0, result.exitCode,
				"a cache directory named in Japanese stopped the run:\n" + result.stdout + result.stderr);
		assertTrue(result.stdout.contains("ran"), result.stdout + result.stderr);
	}

	/**
	 * What Windows does instead, and where - which is not where I expected.
	 *
	 * I went looking for the run: jkite.jar starts a JVM for the script with
	 * the built jar and every dependency on -classpath, and java.exe converts
	 * its command line to the machine's ANSI code page. The measurement says
	 * it never gets that far. It stops one step earlier, at javac:
	 *
	 *   at jdk.compiler/...Arguments.checkDirectory
	 *   at java.base/sun.nio.fs.WindowsPath.parse
	 *   [jkite] [ERROR] Error during compile.
	 *
	 * WindowsPath.parse refusing the string is the giveaway. "?" is not a
	 * character a Windows path may contain, so the name had already been
	 * turned into question marks before javac looked at it - javac.exe is a
	 * launcher like java.exe and converts its command line the same way, and
	 * AppBuilder hands it "-d" and the build directory.
	 *
	 * Two things put a non-ASCII name in that directory, and they are not the
	 * same kind of problem. One is the cache directory, which is wherever
	 * JKITE_DIR says - the user's path, and on a Japanese Windows a Japanese
	 * name is inside the code page and works. The other is jkite's own doing:
	 * Project.getBuildDir() is cache/jars/&lt;script&gt;.&lt;hash&gt;, so a script called
	 * レポート.java puts its own name there. The hash already tells those
	 * directories apart; the name is only there to be read.
	 *
	 * Recorded rather than fixed, because the fix is a change to the cache
	 * layout and that is not mine to make quietly.
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void aNonAsciiPathStopsTheCompileOnWindows() throws Exception {
		Path script = script(tempDir.resolve("plain4"), JAPANESE);

		RunResult result = runJkite(script, tempDir.resolve("home4"));

		assertTrue(result.exitCode != 0,
				"a script named in Japanese now builds on Windows; this test and the note in "
						+ "README can go: " + result.stdout + result.stderr);
		assertTrue((result.stdout + result.stderr).contains("Error during compile"),
				"it still fails, but somewhere else than the compile: " + result.stdout + result.stderr);
	}
}
