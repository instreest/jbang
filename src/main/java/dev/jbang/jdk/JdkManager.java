package dev.jbang.jdk;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import dev.jbang.ExitException;
import dev.jbang.Settings;
import dev.jbang.util.RequestedVersion;
import dev.jbang.util.Util;

/**
 * Finds JDKs already present on the machine and installs missing ones from the
 * download URLs listed in the {@link JdkIndex}. Search order for a requested
 * version:
 * <ol>
 * <li>the JVM running JBang</li>
 * <li>JAVA_HOME</li>
 * <li>javac found on the PATH</li>
 * <li>JDKs installed by JBang in the cache ($JBANGLITE_CACHE_DIR/jdks)</li>
 * <li>download and install into the cache</li>
 * </ol>
 */
public final class JdkManager {
	private final Path jdksDir;
	private final int defaultJavaVersion;
	private List<Jdk> installed;

	public JdkManager() {
		this(Settings.getCacheDir(Settings.CacheClass.jdks), Settings.getDefaultJavaVersion());
	}

	JdkManager(Path jdksDir, int defaultJavaVersion) {
		this.jdksDir = jdksDir;
		this.defaultJavaVersion = defaultJavaVersion;
	}

	/**
	 * Returns a JDK matching the requested version ("17", "17+", "25.0.3" or
	 * null for any), installing one if necessary.
	 */
	public Jdk getOrInstallJdk(String requestedVersion) {
		RequestedVersion version = requestedVersion != null
				? RequestedVersion.parse(requestedVersion)
				: RequestedVersion.ofMajor(defaultJavaVersion, true);
		Jdk jdk = getInstalledJdk(version);
		if (jdk == null) {
			jdk = install(version);
		}
		Util.verboseMsg("Using JDK: " + jdk + " [" + jdk.origin() + "]");
		return jdk;
	}

	/** Returns an already installed JDK matching the version, or null. */
	public Jdk getInstalledJdk(RequestedVersion version) {
		return listInstalled().stream()
			.filter(j -> version.matches(j.version()))
			.findFirst()
			.orElse(null);
	}

	/** All JDKs found, in search order (deduplicated by real path). */
	public List<Jdk> listInstalled() {
		if (installed == null) {
			List<Jdk> jdks = new ArrayList<>();
			add(jdks, Jdk.of(jre2jdk(Paths.get(System.getProperty("java.home"))), "current"));
			String javaHome = System.getenv("JAVA_HOME");
			if (javaHome != null && !javaHome.isEmpty()) {
				add(jdks, Jdk.of(jre2jdk(Paths.get(javaHome)), "JAVA_HOME"));
			}
			Path javac = Util.searchPath("javac");
			if (javac != null) {
				try {
					Path home = javac.toRealPath().getParent().getParent();
					add(jdks, Jdk.of(home, "PATH"));
				} catch (IOException e) {
					Util.verboseMsg("Could not resolve javac on PATH: " + e);
				}
			}
			listJBangJdks().forEach(j -> add(jdks, j));
			installed = jdks;
		}
		return installed;
	}

	private static void add(List<Jdk> jdks, Jdk jdk) {
		if (jdk != null && jdks.stream().noneMatch(j -> sameHome(j.home(), jdk.home()))) {
			jdks.add(jdk);
		}
	}

	private static boolean sameHome(Path a, Path b) {
		try {
			return Files.isSameFile(a, b);
		} catch (IOException e) {
			return a.toAbsolutePath().equals(b.toAbsolutePath());
		}
	}

	/** JDKs installed in the JBang cache, newest first. */
	public List<Jdk> listJBangJdks() {
		if (!Files.isDirectory(jdksDir)) {
			return new ArrayList<>();
		}
		try (Stream<Path> dirs = Files.list(jdksDir)) {
			return dirs
				.filter(Files::isDirectory)
				.filter(d -> !d.getFileName().toString().endsWith(".tmp"))
				.map(d -> Jdk.of(d, "jbang"))
				.filter(Objects::nonNull)
				.sorted(Comparator
					.comparing((Jdk j) -> RequestedVersion.componentsOf(j.version()),
							(a, b) -> RequestedVersion.compare(a, b))
					.reversed())
				.collect(Collectors.toList());
		} catch (IOException e) {
			Util.verboseMsg("Could not list " + jdksDir + ": " + e);
			return new ArrayList<>();
		}
	}

	/**
	 * Downloads and installs a JDK satisfying the request into
	 * $JBANGLITE_CACHE_DIR/jdks/&lt;version&gt;. The archive's SHA-256 is verified
	 * against the checksum published next to it. A lock file makes concurrent
	 * JBang processes wait for each other instead of installing on top of one
	 * another. Nothing outside that directory is touched: running a script
	 * never changes which JDK the next run picks.
	 */
	public Jdk install(RequestedVersion version) {
		if (Util.isOffline()) {
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
					"No suitable JDK was found for requested version " + version + " and we are offline");
		}
		JdkIndex.Entry entry = selectEntry(version);
		Path jdkDir = jdksDir.resolve(entry.version);
		try (Lock lock = Lock.acquire(jdksDir.resolve(".locks").resolve(entry.version + ".lock"))) {
			// another process may have installed this exact entry while we were
			// waiting for the lock; the directory is named after the entry, so
			// finding a JDK there means there is nothing left to download
			installed = null;
			Jdk existing = Jdk.of(jdkDir, "jbang");
			if (existing != null) {
				Util.verboseMsg("JDK " + entry.version + " is already installed: " + existing);
				return finish(existing);
			}
			Jdk jdk = download(entry, jdkDir);
			if (!version.matches(jdk.version())) {
				Util.warnMsg("The JDK index listed version " + entry.version + " for the request '"
						+ version + "' but the installed JDK reports " + jdk.version());
			}
			return finish(jdk);
		}
	}

	private JdkIndex.Entry selectEntry(RequestedVersion version) {
		JdkIndex index = JdkIndex.instance();
		// For an open request we install a single major version rather than the
		// newest JDK in existence: the default version when it satisfies the
		// request, the requested major otherwise.
		if (version.isOpen()) {
			RequestedVersion preferred = RequestedVersion
				.ofMajor(Math.max(version.major(), defaultJavaVersion), false);
			Optional<JdkIndex.Entry> entry = index.find(preferred);
			if (entry.isPresent()) {
				return entry.get();
			}
			Util.verboseMsg("No JDK " + preferred + " available, looking for any " + version);
		}
		return index.find(version)
			.orElseThrow(() -> new ExitException(ExitException.EXIT_INVALID_INPUT,
					"No JDK matching version '" + version + "' is available for "
							+ JdkIndex.platform() + " from " + Settings.JDK_DISTRO));
	}

	private Jdk download(JdkIndex.Entry entry, Path jdkDir) {
		Path tmpDir = jdksDir.resolve(entry.version + ".tmp");
		Path pkg = Settings.getCacheDir(Settings.CacheClass.urls)
			.resolve("bootstrap-jdk-" + entry.version + "." + entry.archiveType);
		Util.deletePath(tmpDir, true);
		Util.infoMsg("Downloading JDK " + entry.version + " (" + entry.distro
				+ "). Be patient, this can take several minutes...");
		Util.verboseMsg("Downloading " + entry.url);
		try {
			Downloader.download(entry.url, pkg);
			verifyChecksum(entry, pkg);
			Util.infoMsg("Installing JDK " + entry.version + "...");
			Unpacker.unpackJdk(pkg, tmpDir);
			if (!Jdk.resolveVersion(tmpDir).isPresent()) {
				throw new IOException("The JDK package does not seem to contain a valid JDK");
			}
			Util.deletePath(jdkDir, true);
			Files.move(tmpDir, jdkDir);
		} catch (IOException | RuntimeException e) {
			Util.deletePath(tmpDir, true);
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
					"Unable to download or install JDK version " + entry.version + " from " + entry.url
							+ " (" + e.getMessage() + ")",
					e);
		} finally {
			Util.deletePath(pkg, true);
		}
		Jdk jdk = Jdk.of(jdkDir, "jbang");
		if (jdk == null) {
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR, "Failed to find JDK in: " + jdkDir);
		}
		return jdk;
	}

	/**
	 * Verifies the downloaded archive against the SHA-256 published next to it
	 * (".sha256.txt", as used by Temurin and others). A mismatch aborts the
	 * installation; a checksum that cannot be fetched is only a warning, since
	 * not every distribution publishes one.
	 */
	private void verifyChecksum(JdkIndex.Entry entry, Path pkg) throws IOException {
		Optional<String> published = Downloader.tryReadString(entry.url + ".sha256.txt");
		if (!published.isPresent()) {
			Util.warnMsg("No published SHA-256 found for " + entry.url + ", skipping verification");
			return;
		}
		String expected = published.get().trim().split("\\s+")[0].toLowerCase();
		String actual = Util.sha256(pkg);
		if (!expected.equals(actual)) {
			Util.deletePath(pkg, true);
			throw new IOException("SHA-256 mismatch for " + entry.url
					+ ": expected " + expected + " but got " + actual);
		}
		Util.verboseMsg("SHA-256 verified: " + actual);
	}

	private Jdk finish(Jdk jdk) {
		installed = null;
		return jdk;
	}

	/** Maps a JRE folder inside a JDK to the JDK's home. */
	private static Path jre2jdk(Path jdkHome) {
		if (!Files.isRegularFile(jdkHome.resolve("release"))) {
			Path jh = jdkHome.toAbsolutePath();
			try {
				jh = jh.toRealPath();
			} catch (IOException e) {
				// ignore
			}
			if (jh.endsWith("jre") && Files.isRegularFile(jh.getParent().resolve("release"))) {
				return jh.getParent();
			}
		}
		return jdkHome;
	}

	/** An inter-process lock held for the duration of an installation. */
	private static final class Lock implements AutoCloseable {
		private final Path file;
		private final RandomAccessFile raf;
		private final FileLock lock;

		private Lock(Path file, RandomAccessFile raf, FileLock lock) {
			this.file = file;
			this.raf = raf;
			this.lock = lock;
		}

		static Lock acquire(Path file) {
			try {
				Files.createDirectories(file.toAbsolutePath().getParent());
				RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw");
				FileChannel channel = raf.getChannel();
				FileLock lock = channel.tryLock();
				if (lock == null) {
					Util.infoMsg("Waiting for another JBang process to finish installing a JDK...");
					lock = channel.lock();
				}
				return new Lock(file, raf, lock);
			} catch (IOException | RuntimeException e) {
				Util.verboseMsg("Could not lock " + file + ", continuing without a lock: " + e);
				return new Lock(file, null, null);
			}
		}

		@Override
		public void close() {
			try {
				if (lock != null) {
					lock.release();
				}
				if (raf != null) {
					raf.close();
				}
			} catch (IOException e) {
				Util.verboseMsg("Could not release the lock on " + file + ": " + e);
			}
		}
	}
}
