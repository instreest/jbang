package dev.jbang;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** An installed JDK: its home directory and version. */
public final class Jdk {
	private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

	private final Path home;
	private final String version;
	private final int majorVersion;
	private final String origin;

	Jdk(Path home, String version, String origin) {
		this.home = home;
		this.version = version;
		this.majorVersion = parseJavaVersion(version);
		this.origin = origin;
	}

	public Path home() {
		return home;
	}

	public String version() {
		return version;
	}

	public int majorVersion() {
		return majorVersion;
	}

	/** Where the JDK was found (e.g. "JAVA_HOME", "PATH", "jbang"). */
	public String origin() {
		return origin;
	}

	public String javaCmd() {
		return bin("java");
	}

	public String javacCmd() {
		return bin("javac");
	}

	private String bin(String cmd) {
		if (Util.isWindows()) {
			cmd += ".exe";
		}
		return home.resolve("bin").resolve(cmd).toAbsolutePath().toString();
	}

	@Override
	public String toString() {
		return version + " (" + home + ")";
	}

	// ---------------------------------------------------------------- helpers

	public static boolean hasJavac(Path home) {
		Path bin = home.resolve("bin");
		return Files.isRegularFile(bin.resolve("javac")) || Files.isRegularFile(bin.resolve("javac.exe"));
	}

	/**
	 * Creates a Jdk for the given home directory if it contains a JDK (javac and
	 * a version that could be determined), otherwise returns null.
	 */
	static Jdk of(Path home, String origin) {
		if (home == null || !Files.isDirectory(home) || !hasJavac(home)) {
			return null;
		}
		Path real = home;
		try {
			real = home.toRealPath();
		} catch (IOException e) {
			// keep as-is
		}
		Optional<String> version = resolveVersion(real);
		return version.map(v -> new Jdk(home, v, origin)).orElse(null);
	}

	static Optional<String> resolveVersion(Path home) {
		Optional<String> res = readVersionFromReleaseFile(home);
		if (!res.isPresent()) {
			Path java = home.resolve("bin").resolve(Util.isWindows() ? "java.exe" : "java");
			if (Files.isRegularFile(java)) {
				String out = Util.runCommand(java.toString(), "-version");
				res = Optional.ofNullable(parseJavaOutput(out));
			}
		}
		return res;
	}

	static Optional<String> readVersionFromReleaseFile(Path home) {
		Path release = home.resolve("release");
		if (!Files.isRegularFile(release)) {
			return Optional.empty();
		}
		try (Stream<String> lines = Files.lines(release)) {
			return lines
				.filter(l -> l.startsWith("JAVA_VERSION=") || l.startsWith("JAVA_RUNTIME_VERSION="))
				.map(Jdk::parseJavaOutput)
				.filter(v -> v != null)
				.findFirst();
		} catch (IOException | java.io.UncheckedIOException e) {
			return Optional.empty();
		}
	}

	static String parseJavaOutput(String output) {
		if (output != null) {
			Matcher m = QUOTED.matcher(output);
			if (m.find()) {
				return m.group(1);
			}
		}
		return null;
	}

	public static int parseJavaVersion(String version) {
		if (version != null) {
			try {
				String[] nums = version.split("[-.+]");
				String num = nums.length > 1 && nums[0].equals("1") ? nums[1] : nums[0];
				return Integer.parseInt(num);
			} catch (NumberFormatException ex) {
				// ignore
			}
		}
		return 0;
	}
}
