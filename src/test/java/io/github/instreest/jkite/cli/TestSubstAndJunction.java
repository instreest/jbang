package io.github.instreest.jkite.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;

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
 * What a subst drive and a junction do to a path on the way to javac.
 *
 * This exists because of a question with a practical answer behind it: if a
 * developer's real project directory is named in Japanese, and they work
 * through "subst X: C:\...\プロジェクト" so that everything they type is
 * X:\..., does jkite work?
 *
 * It matters twice over. Today jkite calls toAbsolutePath().normalize() on
 * the source, and normalize() is string folding - it does not ask the file
 * system anything - so the hypothesis is that X:\Report.java stays
 * X:\Report.java and javac never sees a Japanese character. If that holds,
 * subst is a real workaround for the ANSI code page problem, and it is one a
 * user can apply without changing any machine-wide setting.
 *
 * It also decides a change that was being considered. Replacing normalize()
 * with toRealPath() would make jkite run the same file a shell would when
 * symlinks and ".." are mixed. But toRealPath() asks the file system, and if
 * the file system answers "X:\ is really C:\...\プロジェクト", then that
 * change quietly takes the workaround away from exactly the people who need
 * it. So the second test measures what toRealPath() does to these paths,
 * separately from whether jkite works, because that is the fact the decision
 * turns on.
 *
 * Windows only: subst and junctions are Windows. Neither needs administrator
 * rights, unlike a symlink, which is why these two and not mklink.
 */
class TestSubstAndJunction extends AbstractScriptTest {

	private static final String JAPANESE = "\u30d7\u30ed\u30b8\u30a7\u30af\u30c8";

	private static final Path JKITE_JAR = Paths.get(System.getProperty("jkite.jar", "build/libs/jkite.jar"));

	private Map<String, String> env(Path jkiteDir) {
		Map<String, String> env = new HashMap<>(System.getenv());
		env.put("JAVA_HOME", System.getProperty("java.home"));
		env.put("JKITE_DIR", jkiteDir.toString());
		env.remove("JKITE_CACHE_DIR");
		env.put("JKITE_CONFIRM_DOWNLOADS", "always");
		return env;
	}

	private RunResult runJkite(String script, Path jkiteDir) throws Exception {
		assertTrue(Files.isRegularFile(JKITE_JAR),
				"the shaded jar is not built, so this is testing nothing: " + JKITE_JAR);
		return runProcess(Arrays.asList(System.getProperty("java.home") + "/bin/java",
				"-jar", JKITE_JAR.toAbsolutePath().toString(), script), env(jkiteDir));
	}

	/** An ASCII script, so that any non-ASCII on the command line is the directory's. */
	private void script(Path dir) throws IOException {
		Files.createDirectories(dir);
		Files.write(dir.resolve("Report.java"), ("class Report {\n"
				+ "  public static void main(String[] a) { System.out.println(\"ran\"); }\n"
				+ "}\n").getBytes(StandardCharsets.UTF_8));
	}

	private RunResult cmd(String line) throws Exception {
		return runProcess(Arrays.asList("cmd.exe", "/c", line), new HashMap<>(System.getenv()));
	}

	/**
	 * Maps a free drive letter to dir and returns it as "X:", or aborts if
	 * subst is not usable here - a runner without a free letter is not a
	 * failure of jkite's.
	 */
	private String subst(Path dir) throws Exception {
		for (char letter = 'Z'; letter >= 'S'; letter--) {
			if (Files.exists(Paths.get(letter + ":\\"))) {
				continue;
			}
			RunResult made = cmd("subst " + letter + ": \"" + dir + "\"");
			if (made.exitCode == 0) {
				return letter + ":";
			}
		}
		return abort("no drive letter could be substed here");
	}

	/**
	 * The question as a user would ask it: real directory in Japanese, subst
	 * drive in ASCII, script named in ASCII. Does it run?
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void aSubstDriveHidesAJapaneseDirectoryFromJavac() throws Exception {
		Path real = tempDir.resolve(JAPANESE + "-subst");
		script(real);
		String drive = subst(real);
		try {
			RunResult result = runJkite(drive + "\\Report.java", tempDir.resolve("home-subst"));
			String said = result.stdout + result.stderr;

			assertEquals(0, result.exitCode, said);
			assertTrue(said.contains("ran"), said);
		} finally {
			cmd("subst " + drive + " /d");
		}
	}

	/** The same through a junction, which is the other way to get an ASCII path. */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void aJunctionHidesAJapaneseDirectoryFromJavac() throws Exception {
		Path real = tempDir.resolve(JAPANESE + "-junction");
		script(real);
		Path link = tempDir.resolve("ascii-link");
		RunResult made = cmd("mklink /J \"" + link + "\" \"" + real + "\"");
		if (made.exitCode != 0) {
			abort("mklink /J is not usable here: " + made.stdout + made.stderr);
		}

		RunResult result = runJkite(link + "\\Report.java", tempDir.resolve("home-junction"));
		String said = result.stdout + result.stderr;

		assertEquals(0, result.exitCode, said);
		assertTrue(said.contains("ran"), said);
	}

	/**
	 * The fact the toRealPath() decision turns on, measured on its own: does
	 * asking the file system for the real path put the Japanese directory
	 * back?
	 *
	 * If it does, then switching Project from normalize() to toRealPath()
	 * would break both cases above, and the symlink correctness it buys costs
	 * the only workaround a user has for a name their code page cannot hold.
	 */
	@Test
	@EnabledOnOs(OS.WINDOWS)
	void toRealPathPutsTheJapaneseDirectoryBack() throws Exception {
		Path real = tempDir.resolve(JAPANESE + "-real");
		script(real);
		String drive = subst(real);
		Path link = tempDir.resolve("ascii-link-real");
		RunResult made = cmd("mklink /J \"" + link + "\" \"" + real + "\"");
		try {
			Path viaSubst = Paths.get(drive + "\\Report.java").toRealPath();
			assertTrue(viaSubst.toString().contains(JAPANESE),
					"subst survives toRealPath, so it would stay a workaround: " + viaSubst);

			if (made.exitCode == 0) {
				Path viaJunction = link.resolve("Report.java").toRealPath();
				assertTrue(viaJunction.toString().contains(JAPANESE),
						"the junction survives toRealPath: " + viaJunction);
			}
		} finally {
			cmd("subst " + drive + " /d");
		}
	}
}
