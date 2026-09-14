package io.github.instreest.jkite.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the build id has to notice.
 *
 * A jar is reused whenever the id it was built under comes up again, so
 * anything that would make the build come out differently has to change the id.
 * Each test here is a change that a previous version of the id did not see, and
 * that therefore served a jar built from something else.
 */
class TestBuildCache {

	@TempDir
	Path dir;

	private String idOf(Path script) {
		return idOf(script, Collections.emptyMap());
	}

	private String idOf(Path script, Map<String, String> properties) {
		return new Project(script, properties).getStableId();
	}

	private Path write(String name, String content) throws IOException {
		Path file = dir.resolve(name);
		Files.createDirectories(file.getParent());
		Files.write(file, content.getBytes(StandardCharsets.UTF_8));
		return file;
	}

	@Test
	void theSameProjectKeepsTheSameId() throws IOException {
		write("Helper.java", "class Helper { static final int ANSWER = 41; }\n");
		Path script = write("Tool.java", "//SOURCES Helper.java\nclass Tool { }\n");

		assertEquals(idOf(script), idOf(script));
	}

	@Test
	void aLiteralChangedInADeclaredSourceIsNoticed() throws IOException {
		write("Helper.java", "class Helper { static final int ANSWER = 41; }\n");
		Path script = write("Tool.java", "//SOURCES Helper.java\nclass Tool { }\n");
		String before = idOf(script);

		write("Helper.java", "class Helper { static final int ANSWER = 42; }\n");

		assertNotEquals(before, idOf(script));
	}

	/**
	 * Both files are invalid UTF-8 in the same places, so reading them as text
	 * gives the same replacement characters and the same hash. The bytes are
	 * what ends up in the jar, so the bytes are what has to be hashed.
	 */
	@Test
	void aChangeOnlyTheBytesShowIsNoticed() throws IOException {
		Path icon = dir.resolve("icon.bin");
		Files.write(icon, new byte[] { (byte) 0x89, 'P', 'N', 'G', (byte) 0x80 });
		Path script = write("Tool.java", "//FILES icon.bin\nclass Tool { }\n");
		String before = idOf(script);

		Files.write(icon, new byte[] { (byte) 0x89, 'P', 'N', 'G', (byte) 0x81 });

		assertNotEquals(before, idOf(script));
	}

	/**
	 * The name is what the resource is called inside the jar, so renaming one
	 * that a glob picks up changes the jar even though no content changed.
	 */
	@Test
	void aRenamedResourceIsNoticed() throws IOException {
		write("data/a.txt", "v1");
		Path script = write("Tool.java", "//FILES data/*.txt\nclass Tool { }\n");
		String before = idOf(script);

		Files.move(dir.resolve("data/a.txt"), dir.resolve("data/b.txt"));

		assertNotEquals(before, idOf(script));
	}

	/**
	 * The directive is the same text in both runs; what it resolves to is not,
	 * and that is what javac is given.
	 */
	@Test
	void aPropertyThatChangesTheCompileOptionsIsNoticed() throws IOException {
		Path script = write("Tool.java", "//COMPILE_OPTIONS ${extra:-g}\nclass Tool { }\n");

		assertNotEquals(idOf(script, property("extra", "-parameters")),
				idOf(script, property("extra", "-g:none")));
	}

	/** Same again for a directive that ends up in the manifest. */
	@Test
	void aPropertyThatChangesTheManifestIsNoticed() throws IOException {
		Path script = write("Tool.java", "//MANIFEST Built-For=${target:dev}\nclass Tool { }\n");

		assertNotEquals(idOf(script, property("target", "production")),
				idOf(script, property("target", "staging")));
	}

	/** A property the directives do not use changes nothing about the build. */
	@Test
	void aPropertyThatChangesNothingIsNotNoticed() throws IOException {
		Path script = write("Tool.java", "class Tool { }\n");

		assertEquals(idOf(script, property("unused", "a")), idOf(script, property("unused", "b")));
	}

	private static Map<String, String> property(String key, String value) {
		Map<String, String> properties = new HashMap<>();
		properties.put(key, value);
		return properties;
	}
}
