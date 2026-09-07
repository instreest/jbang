package dev.jbang;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Small collection of helpers: messages, OS detection, file globbing, hashing
 * and process execution.
 */
public final class Util {
	public static final String ENV_RUNTIME_SHELL = "JBANG_RUNTIME_SHELL";

	private static boolean verbose;
	private static boolean quiet;
	private static boolean offline;
	private static boolean fresh;
	private static final Instant startTime = Instant.now();

	public enum OS {
		linux, alpine_linux, mac, windows, aix, unknown
	}

	public enum Arch {
		x32, x64, aarch64, arm, arm64, ppc64, ppc64le, s390x, riscv64, unknown
	}

	public enum Shell {
		bash, cmd, powershell
	}

	private Util() {
	}

	// ---------------------------------------------------------------- flags

	public static void setVerbose(boolean v) {
		verbose = v;
		if (v) {
			quiet = false;
		}
	}

	public static boolean isVerbose() {
		return verbose;
	}

	public static void setQuiet(boolean q) {
		quiet = q;
		if (q) {
			verbose = false;
		}
	}

	public static boolean isQuiet() {
		return quiet;
	}

	public static void setOffline(boolean o) {
		offline = o;
	}

	public static boolean isOffline() {
		return offline;
	}

	public static void setFresh(boolean f) {
		fresh = f;
	}

	public static boolean isFresh() {
		return fresh;
	}

	// ------------------------------------------------------------- messages

	public static void verboseMsg(String msg) {
		if (verbose) {
			System.err.println(msgHeader() + msg);
		}
	}

	public static void verboseMsg(String msg, Throwable e) {
		if (verbose) {
			System.err.println(msgHeader() + msg);
			e.printStackTrace();
		}
	}

	public static void infoMsg(String msg) {
		if (!quiet) {
			System.err.println(msgHeader() + msg);
		}
	}

	public static void warnMsg(String msg) {
		if (!quiet) {
			System.err.println(msgHeader() + "[WARN] " + msg);
		}
	}

	public static void errorMsg(String msg) {
		System.err.println(msgHeader() + "[ERROR] " + msg);
	}

	public static void errorMsg(String msg, Throwable e) {
		if (msg != null) {
			errorMsg(msg);
		} else if (e.getMessage() != null) {
			errorMsg(e.getMessage());
		} else {
			errorMsg(e.getClass().getName());
		}
		if (verbose) {
			e.printStackTrace();
		} else {
			infoMsg("Run with --verbose for more details. "
					+ "The --verbose must be placed before the jbang command. I.e. jbang --verbose run [...]");
		}
	}

	private static String msgHeader() {
		if (verbose) {
			Duration d = Duration.between(startTime, Instant.now());
			long s = d.getSeconds();
			long n = d.minus(s, ChronoUnit.SECONDS).toMillis();
			return String.format("[jbang] [%d:%03d] ", s, n);
		}
		return "[jbang] ";
	}

	// ------------------------------------------------------------------- OS

	public static OS getOS() {
		String os = System.getProperty("os.name").toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]+", "");
		if (os.startsWith("mac") || os.startsWith("osx")) {
			return OS.mac;
		} else if (os.startsWith("linux")) {
			return Files.exists(Paths.get("/etc/alpine-release")) ? OS.alpine_linux : OS.linux;
		} else if (os.startsWith("win")) {
			return OS.windows;
		} else if (os.startsWith("aix")) {
			return OS.aix;
		}
		return OS.unknown;
	}

	public static Arch getArch() {
		String arch = System.getProperty("os.arch").toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]+", "");
		if (arch.matches("^(x8664|amd64|ia32e|em64t|x64)$")) {
			return Arch.x64;
		} else if (arch.matches("^(x8632|x86|i[3-6]86|ia32|x32)$")) {
			return Arch.x32;
		} else if (arch.equals("aarch64")) {
			return Arch.aarch64;
		} else if (arch.equals("arm")) {
			return Arch.arm;
		} else if (arch.equals("arm64")) {
			return Arch.arm64;
		} else if (arch.equals("ppc64")) {
			return Arch.ppc64;
		} else if (arch.equals("ppc64le")) {
			return Arch.ppc64le;
		} else if (arch.equals("s390x")) {
			return Arch.s390x;
		} else if (arch.equals("riscv64")) {
			return Arch.riscv64;
		}
		return Arch.unknown;
	}

	public static boolean isWindows() {
		return getOS() == OS.windows;
	}

	public static boolean isMac() {
		return getOS() == OS.mac;
	}

	public static Shell getShell() {
		try {
			return Shell.valueOf(System.getenv(ENV_RUNTIME_SHELL));
		} catch (IllegalArgumentException | NullPointerException ex) {
			return isWindows() ? Shell.powershell : Shell.bash;
		}
	}

	public static Path getCwd() {
		return Paths.get("").toAbsolutePath();
	}

	// ---------------------------------------------------------------- files

	public static String readString(Path file) {
		try {
			return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new ExitException(ExitException.EXIT_UNEXPECTED_STATE, "Could not read content for " + file, e);
		}
	}

	public static Stream<String> stringLines(String text) {
		return Arrays.stream(text.split("\\r?\\n"));
	}

	public static String getBaseName(String fileName) {
		int p = fileName.lastIndexOf('.');
		return p > 0 ? fileName.substring(0, p) : fileName;
	}

	public static boolean isPattern(String pattern) {
		return pattern.contains("?") || pattern.contains("*");
	}

	public static boolean isValidPath(String path) {
		try {
			Paths.get(path);
			return true;
		} catch (InvalidPathException e) {
			return false;
		}
	}

	/**
	 * Expands a file pattern (glob) relative to baseDir and returns the matching
	 * paths as strings (relative if the pattern was relative). A pattern that is
	 * an existing folder is treated as if it ended in "/**". A plain (non glob)
	 * path is returned unchanged.
	 */
	public static List<String> explode(Path baseDir, String filePattern) {
		if (!isPattern(filePattern)) {
			if (isValidPath(filePattern) && Files.isDirectory(baseDir.resolve(filePattern))) {
				if (!filePattern.endsWith("/") && !filePattern.endsWith(File.separator)) {
					filePattern = filePattern + "/";
				}
				filePattern = filePattern + "**";
			} else {
				return Collections.singletonList(filePattern);
			}
		}

		final Path bd;
		final boolean useAbsPath;
		Path base = basePathWithoutPattern(filePattern);
		String fp;
		if (base.isAbsolute()) {
			bd = base;
			fp = filePattern.substring(bd.toString().length() + 1);
			useAbsPath = true;
		} else {
			bd = baseDir.resolve(base);
			fp = base.toString().isEmpty() ? filePattern : filePattern.substring(base.toString().length() + 1);
			useAbsPath = false;
		}
		if (!Files.isDirectory(bd)) {
			return Collections.emptyList();
		}
		PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + fp);
		List<String> results = new ArrayList<>();
		try {
			Files.walkFileTree(bd, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
					Path relpath = bd.relativize(file);
					if (matcher.matches(relpath)) {
						Path p = useAbsPath ? file : base.resolve(relpath);
						results.add(isWindows() ? p.toString().replace("\\", "/") : p.toString());
					}
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			throw new ExitException(ExitException.EXIT_INTERNAL_ERROR,
					"Problem looking for " + fp + " in " + bd + ": " + e, e);
		}
		Collections.sort(results);
		return results;
	}

	public static Path basePathWithoutPattern(String path) {
		int p1 = path.indexOf('?');
		int p2 = path.indexOf('*');
		int pp = p1 < 0 ? p2 : (p2 < 0 ? p1 : Math.min(p1, p2));
		if (pp >= 0) {
			String npath = isWindows() ? path.replace('\\', '/') : path;
			int ps = npath.lastIndexOf('/', pp);
			return ps >= 0 ? Paths.get(path.substring(0, ps + 1)) : Paths.get("");
		}
		return Paths.get(path);
	}

	public static boolean deletePath(Path path, boolean quietly) {
		try {
			if (Files.isSymbolicLink(path) || isJunction(path)) {
				Files.delete(path);
			} else if (Files.isDirectory(path)) {
				try (Stream<Path> s = Files.list(path)) {
					for (Path p : s.collect(Collectors.toList())) {
						deletePath(p, quietly);
					}
				}
				Files.delete(path);
			} else if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
				Files.delete(path);
			}
			return true;
		} catch (IOException e) {
			if (!quietly) {
				throw new ExitException(ExitException.EXIT_GENERIC_ERROR, "Could not delete " + path, e);
			}
			verboseMsg("Could not delete " + path + ": " + e);
			return false;
		}
	}

	/** True if the path is a link (symbolic link or Windows junction). */
	public static boolean isLink(Path path) {
		try {
			if (Files.isSymbolicLink(path)) {
				return true;
			}
			return isJunction(path);
		} catch (RuntimeException e) {
			return false;
		}
	}

	private static boolean isJunction(Path path) {
		if (!isWindows() || !Files.isDirectory(path)) {
			return false;
		}
		try {
			Path parent = path.toAbsolutePath().getParent();
			if (parent == null) {
				return false;
			}
			Path abs = parent.toRealPath().resolve(path.getFileName());
			return !abs.toRealPath().equals(abs.toRealPath(LinkOption.NOFOLLOW_LINKS));
		} catch (IOException e) {
			return false;
		}
	}

	/** Creates a link, using a junction on Windows and a symbolic link elsewhere. */
	public static void createLink(Path link, Path target) {
		try {
			Files.createDirectories(link.toAbsolutePath().getParent());
			if (Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
				deletePath(link, false);
			}
			Path absTarget = target.toAbsolutePath();
			if (isWindows() && Files.isDirectory(absTarget)) {
				String out = runCommand("cmd.exe", "/c", "mklink", "/j", link.toString(), absTarget.toString());
				if (out == null) {
					throw new IOException("mklink failed");
				}
			} else {
				Files.createSymbolicLink(link, absTarget);
			}
		} catch (IOException e) {
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
					"Failed to create link " + link + " -> " + target, e);
		}
	}

	public static String getStableID(Stream<String> inputs) {
		final MessageDigest digest;
		try {
			digest = MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new ExitException(ExitException.EXIT_INTERNAL_ERROR, e);
		}
		inputs.forEach(input -> digest.update(input.getBytes(StandardCharsets.UTF_8)));
		StringBuilder sb = new StringBuilder();
		for (byte b : digest.digest()) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	// ------------------------------------------------------------ processes

	/**
	 * Searches the directories in PATH (or the given path list) for an
	 * executable.
	 */
	public static Path searchPath(String cmd) {
		String envPath = System.getenv("PATH");
		return searchPath(cmd, envPath != null ? envPath : "");
	}

	public static Path searchPath(String cmd, String paths) {
		for (String dir : paths.split(File.pathSeparator)) {
			if (dir.isEmpty()) {
				continue;
			}
			Path base = Paths.get(dir).resolve(cmd);
			List<Path> candidates = isWindows()
					? Arrays.asList(Paths.get(base + ".exe"), Paths.get(base + ".bat"), Paths.get(base + ".cmd"))
					: Collections.singletonList(base);
			for (Path c : candidates) {
				if (Files.isRegularFile(c) && (isWindows() || Files.isExecutable(c))) {
					return c;
				}
			}
		}
		return null;
	}

	/**
	 * Runs a command and returns its combined output, or null if the command
	 * failed or could not be started.
	 */
	public static String runCommand(String... cmd) {
		try {
			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectErrorStream(true);
			Process p = pb.start();
			String out;
			try (InputStream is = p.getInputStream();
					BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
				out = br.lines().collect(Collectors.joining("\n"));
			}
			int exitCode = p.waitFor();
			if (exitCode == 0) {
				return out;
			}
			verboseMsg("Command failed: #" + exitCode + " - " + String.join(" ", cmd) + "\n" + out);
		} catch (IOException | InterruptedException ex) {
			verboseMsg("Error running: " + String.join(" ", cmd), ex);
		}
		return null;
	}

	public static String getJBangVersion() {
		String v = Util.class.getPackage() != null ? Util.class.getPackage().getImplementationVersion() : null;
		if (v == null) {
			try {
				java.net.URL res = Util.class.getResource("/META-INF/MANIFEST.MF");
				if (res != null) {
					try (InputStream is = res.openStream()) {
						java.util.jar.Manifest mf = new java.util.jar.Manifest(is);
						v = mf.getMainAttributes().getValue("JBang-Version");
					}
				}
			} catch (IOException e) {
				// ignore
			}
		}
		return v != null ? v : "unknown";
	}
}
