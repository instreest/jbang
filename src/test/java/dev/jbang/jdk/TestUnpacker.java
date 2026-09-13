package dev.jbang.jdk;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import java.util.function.Consumer;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jbang.util.Util;

/**
 * Unpacking a JDK archive: the layout that comes out, and the entries that must
 * not be unpacked at all.
 */
class TestUnpacker {

	@TempDir
	Path dir;

	// -------------------------------------------------------------------------
	// what a JDK archive looks like
	// -------------------------------------------------------------------------

	@Test
	void stripsTheRootFolderOfATar() throws IOException {
		Path archive = tar("jdk.tar.gz", tar -> {
			dirEntry(tar, "jdk-25.0.3+9/");
			dirEntry(tar, "jdk-25.0.3+9/bin/");
			fileEntry(tar, "jdk-25.0.3+9/bin/java", "binary", 0755);
			fileEntry(tar, "jdk-25.0.3+9/release", "JAVA_VERSION=\"25.0.3\"", 0644);
		});
		Path out = dir.resolve("out");

		Unpacker.unpackJdk(archive, out);

		assertThat(Files.isRegularFile(out.resolve("bin/java")), is(true));
		assertThat(new String(Files.readAllBytes(out.resolve("release")), StandardCharsets.UTF_8),
				containsString("25.0.3"));
	}

	@Test
	void keepsTheExecutableBitOfATar() throws IOException {
		assumeFalse(Util.isWindows(), "POSIX permissions");
		Path archive = tar("jdk.tar.gz", tar -> {
			fileEntry(tar, "jdk/bin/java", "binary", 0755);
			fileEntry(tar, "jdk/lib/modules", "data", 0644);
		});
		Path out = dir.resolve("out");

		Unpacker.unpackJdk(archive, out);

		Set<PosixFilePermission> java = Files.getPosixFilePermissions(out.resolve("bin/java"));
		assertThat(java.contains(PosixFilePermission.OWNER_EXECUTE), is(true));
		assertThat(java.contains(PosixFilePermission.OTHERS_EXECUTE), is(true));
		assertThat(Files.getPosixFilePermissions(out.resolve("lib/modules"))
			.contains(PosixFilePermission.OWNER_EXECUTE), is(false));
	}

	@Test
	void readsPathsTooLongForAPlainTarHeader() throws IOException {
		// > 100 characters, so the name only exists in a pax header; reading the
		// plain header alone would write a truncated name without failing
		StringBuilder deep = new StringBuilder("jdk/lib/src");
		while (deep.length() < 160) {
			deep.append("/subdirectory");
		}
		String longPath = deep + "/TheClassWithAVeryLongNameIndeed.java";
		Path archive = tar("jdk.tar.gz", tar -> fileEntry(tar, longPath, "source", 0644));
		Path out = dir.resolve("out");

		Unpacker.unpackJdk(archive, out);

		Path expected = out.resolve(longPath.substring("jdk/".length()));
		assertThat(Files.isRegularFile(expected), is(true));
	}

	@Test
	void createsTheLinksInsideTheArchive() throws IOException {
		assumeFalse(Util.isWindows(), "symbolic links need a privilege on Windows");
		Path archive = tar("jdk.tar.gz", tar -> {
			fileEntry(tar, "jdk/bin/java", "binary", 0755);
			symlink(tar, "jdk/bin/javaw", "java");
		});
		Path out = dir.resolve("out");

		Unpacker.unpackJdk(archive, out);

		Path link = out.resolve("bin/javaw");
		assertThat(Files.isSymbolicLink(link), is(true));
		assertThat(Files.readSymbolicLink(link).toString(), is("java"));
	}

	@Test
	void stripsTheRootFolderOfAZipAndKeepsItsModes() throws IOException {
		assumeFalse(Util.isWindows(), "POSIX permissions");
		Path archive = zip("jdk.zip", zip -> {
			fileEntry(zip, "jdk-25/bin/java.exe", "binary", 0755);
			fileEntry(zip, "jdk-25/release", "JAVA_VERSION=\"25.0.3\"", 0644);
		});
		Path out = dir.resolve("out");

		Unpacker.unpackJdk(archive, out);

		assertThat(Files.isRegularFile(out.resolve("release")), is(true));
		assertThat(Files.getPosixFilePermissions(out.resolve("bin/java.exe"))
			.contains(PosixFilePermission.OWNER_EXECUTE), is(true));
	}

	// -------------------------------------------------------------------------
	// what must not be unpacked
	// -------------------------------------------------------------------------

	@Test
	void refusesAnEntryThatLeavesTheOutputDirectory() throws IOException {
		Path archive = tar("evil.tar.gz", tar -> fileEntry(tar, "jdk/../../../escaped.txt", "owned", 0644));
		Path out = dir.resolve("out");

		IOException e = assertThrows(IOException.class, () -> Unpacker.unpackJdk(archive, out));

		assertThat(e.getMessage(), containsString("outside of the target dir"));
		assertThat(Files.exists(dir.getParent().resolve("escaped.txt")), is(false));
	}

	@Test
	void anEntryBesideTheRootFolderIsNotUnpacked() throws IOException {
		// it climbs out of the root folder, so after stripping that folder
		// nothing is left of the path: there is no place for it in a JDK
		Path archive = tar("jdk.tar.gz", tar -> {
			fileEntry(tar, "jdk/../climbed.txt", "content", 0644);
			fileEntry(tar, "jdk/release", "JAVA_VERSION=\"25\"", 0644);
		});
		Path out = dir.resolve("out");

		Unpacker.unpackJdk(archive, out);

		assertThat(Files.isRegularFile(out.resolve("release")), is(true));
		assertThat(Files.exists(out.resolve("climbed.txt")), is(false));
		assertThat(Files.exists(dir.resolve("climbed.txt")), is(false));
	}

	@Test
	void refusesALinkThatPointsOutOfTheOutputDirectory() throws IOException {
		assumeFalse(Util.isWindows(), "symbolic links need a privilege on Windows");
		Path archive = tar("evil.tar.gz", tar -> symlink(tar, "jdk/escape", "../../.."));
		Path out = dir.resolve("out");

		IOException e = assertThrows(IOException.class, () -> Unpacker.unpackJdk(archive, out));

		assertThat(e.getMessage(), containsString("points outside of the target dir"));
	}

	@Test
	void refusesToWriteThroughALinkThatLeavesTheOutputDirectory() throws IOException {
		assumeFalse(Util.isWindows(), "symbolic links need a privilege on Windows");
		// the classic two-entry escape: a link out of the tree, then a file
		// written "into" it. The link itself is refused first; were it allowed,
		// the write through it has to be refused too.
		Path outside = Files.createDirectory(dir.resolve("outside"));
		Path archive = tar("evil.tar.gz", tar -> {
			symlink(tar, "jdk/link", outside.toString());
			fileEntry(tar, "jdk/link/planted.txt", "owned", 0644);
		});
		Path out = dir.resolve("out");

		assertThrows(IOException.class, () -> Unpacker.unpackJdk(archive, out));

		assertThat(Files.exists(outside.resolve("planted.txt")), is(false));
	}

	@Test
	void ignoresEntriesThatAreNotPartOfAJdk() throws IOException {
		Path archive = tar("jdk.tar.gz", tar -> {
			TarArchiveEntry fifo = new TarArchiveEntry("jdk/dev/pipe", TarArchiveEntry.LF_FIFO);
			put(tar, fifo, null);
			fileEntry(tar, "jdk/release", "JAVA_VERSION=\"25\"", 0644);
		});
		Path out = dir.resolve("out");

		Unpacker.unpackJdk(archive, out);

		assertThat(Files.exists(out.resolve("dev/pipe"), LinkOption.NOFOLLOW_LINKS), is(false));
		assertThat(Files.isRegularFile(out.resolve("release")), is(true));
	}

	@Test
	void refusesAnArchiveItDoesNotKnow() throws IOException {
		Path archive = Files.write(dir.resolve("jdk.7z"), new byte[] { 1, 2, 3 });

		IOException e = assertThrows(IOException.class, () -> Unpacker.unpackJdk(archive, dir.resolve("out")));

		assertThat(e.getMessage(), containsString("Unsupported archive format"));
	}

	// -------------------------------------------------------------------------
	// archive building
	// -------------------------------------------------------------------------

	private Path tar(String name, Consumer<TarArchiveOutputStream> content) throws IOException {
		Path archive = dir.resolve(name);
		try (OutputStream os = Files.newOutputStream(archive);
				TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(os))) {
			tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
			content.accept(tar);
			tar.finish();
		}
		return archive;
	}

	private Path zip(String name, Consumer<ZipArchiveOutputStream> content) throws IOException {
		Path archive = dir.resolve(name);
		try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(Files.newOutputStream(archive))) {
			content.accept(zip);
			zip.finish();
		}
		return archive;
	}

	private static void dirEntry(TarArchiveOutputStream tar, String name) {
		TarArchiveEntry entry = new TarArchiveEntry(name);
		entry.setMode(0755 | TarArchiveEntry.LF_DIR);
		put(tar, entry, null);
	}

	private static void fileEntry(TarArchiveOutputStream tar, String name, String content, int mode) {
		TarArchiveEntry entry = new TarArchiveEntry(name);
		entry.setSize(content.getBytes(StandardCharsets.UTF_8).length);
		entry.setMode(mode);
		put(tar, entry, content);
	}

	private static void symlink(TarArchiveOutputStream tar, String name, String target) {
		TarArchiveEntry entry = new TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK);
		entry.setLinkName(target);
		put(tar, entry, null);
	}

	private static void put(TarArchiveOutputStream tar, TarArchiveEntry entry, String content) {
		try {
			tar.putArchiveEntry(entry);
			if (content != null) {
				tar.write(content.getBytes(StandardCharsets.UTF_8));
			}
			tar.closeArchiveEntry();
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}

	private static void fileEntry(ZipArchiveOutputStream zip, String name, String content, int mode) {
		try {
			ZipArchiveEntry entry = new ZipArchiveEntry(name);
			entry.setUnixMode(mode);
			zip.putArchiveEntry(entry);
			zip.write(content.getBytes(StandardCharsets.UTF_8));
			zip.closeArchiveEntry();
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}
}
