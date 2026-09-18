package io.github.instreest.jkite.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.instreest.jkite.jdk.Jdk;
import io.github.instreest.jkite.jdk.JdkManager;

/**
 * What javac is asked to do. Some of these options are there for a reason that
 * a successful compile does not show: it succeeds either way, and what differs
 * is what the class files mean.
 */
class TestCompileCommand {

	@TempDir
	Path dir;

	private List<String> commandFor(String script) throws IOException {
		Path source = dir.resolve("Tool.java");
		Files.write(source, script.getBytes(StandardCharsets.UTF_8));
		Project project = new Project(source, Collections.emptyMap());
		// the JVM running this test: first in the search order, and no install
		Jdk jdk = new JdkManager().listInstalled().get(0);
		return new AppBuilder(project).compileCommand(dir.resolve("classes"), jdk);
	}

	private static int indexOf(List<String> cmd, String option) {
		int i = cmd.indexOf(option);
		assertTrue(i >= 0, option + " is not in " + cmd);
		return i;
	}

	/**
	 * jkite reads the source as UTF-8 to find the directives. javac, left to
	 * itself, reads it in the platform's default charset - UTF-8 on a recent
	 * JDK, but not on an older one and not on a Windows machine with a legacy
	 * code page. The same file would then compile into different class files on
	 * two machines, with a string literal mangled rather than an error raised.
	 */
	@Test
	void javacIsToldTheEncodingTheDirectivesWereReadIn() throws IOException {
		List<String> cmd = commandFor("class Tool { }\n");

		assertEquals("UTF-8", cmd.get(indexOf(cmd, "-encoding") + 1), cmd.toString());
	}

	/**
	 * javac takes the last -encoding it is given, so jkite's has to come first
	 * for a script that means another one to be able to say so.
	 */
	@Test
	void aScriptCanStillAskForAnotherEncoding() throws IOException {
		List<String> cmd = commandFor("//COMPILE_OPTIONS -encoding ISO-8859-1\nclass Tool { }\n");

		int ours = indexOf(cmd, "-encoding");
		int theirs = cmd.lastIndexOf("-encoding");
		assertTrue(theirs > ours, "the script's own encoding does not come last: " + cmd);
		assertEquals("ISO-8859-1", cmd.get(theirs + 1), cmd.toString());
	}

	/** The sources come last, after every option, or javac reads them as one. */
	@Test
	void theSourcesComeAfterTheOptions() throws IOException {
		List<String> cmd = commandFor("class Tool { }\n");

		assertEquals(dir.resolve("Tool.java").toString(), cmd.get(cmd.size() - 1), cmd.toString());
	}
}
