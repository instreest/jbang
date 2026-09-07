package dev.jbang;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds JDKs already present on the machine and installs missing ones from
 * the Foojay Disco API (the same service the launcher scripts use). Search
 * order for a requested version:
 * <ol>
 * <li>the JVM running JBang</li>
 * <li>the default JDK link ($JBANG_DIR/currentjdk)</li>
 * <li>JAVA_HOME</li>
 * <li>javac found on the PATH</li>
 * <li>JDKs installed by JBang in the cache ($JBANG_CACHE_DIR/jdks)</li>
 * <li>download and install into the cache</li>
 * </ol>
 */
public final class JdkManager {
	private static final String FOOJAY_DIRECT_URI = "https://api.foojay.io/disco/v3.0/directuris?";

	private final Path jdksDir;
	private final Path defaultLink;
	private final int defaultJavaVersion;
	private List<Jdk> installed;

	public JdkManager() {
		this(Settings.getCacheDir(Settings.CacheClass.jdks), Settings.getDefaultJdkLink(),
				Settings.getDefaultJavaVersion());
	}

	JdkManager(Path jdksDir, Path defaultLink, int defaultJavaVersion) {
		this.jdksDir = jdksDir;
		this.defaultLink = defaultLink;
		this.defaultJavaVersion = defaultJavaVersion;
	}

	/**
	 * Returns a JDK matching the requested version ("17", "17+" or null for any),
	 * installing one if necessary.
	 */
	public Jdk getOrInstallJdk(String requestedVersion) {
		int version = requestedVersion != null ? Directives.minRequestedVersion(requestedVersion) : 0;
		boolean open = requestedVersion == null || Directives.isOpenVersion(requestedVersion);
		Jdk jdk = getInstalledJdk(version, open);
		if (jdk == null && version > 0 && open && version < defaultJavaVersion) {
			// Prefer installing the default version when any newer one will do
			jdk = getInstalledJdk(defaultJavaVersion, true);
			if (jdk == null) {
				jdk = install(defaultJavaVersion);
			}
		}
		if (jdk == null) {
			jdk = install(version > 0 ? version : defaultJavaVersion);
		}
		Util.verboseMsg("Using JDK: " + jdk + " [" + jdk.origin() + "]");
		return jdk;
	}

	/** Returns an already installed JDK matching the version, or null. */
	public Jdk getInstalledJdk(int version, boolean open) {
		return listInstalled().stream()
			.filter(j -> matches(j, version, open))
			.findFirst()
			.orElse(null);
	}

	private static boolean matches(Jdk jdk, int version, boolean open) {
		if (version <= 0) {
			return true;
		}
		return open ? jdk.majorVersion() >= version : jdk.majorVersion() == version;
	}

	/** All JDKs found, in search order (deduplicated by real path). */
	public List<Jdk> listInstalled() {
		if (installed == null) {
			List<Jdk> jdks = new ArrayList<>();
			add(jdks, Jdk.of(jre2jdk(Paths.get(System.getProperty("java.home"))), "current"));
			add(jdks, Jdk.of(defaultLink, "default"));
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
		if (jdk != null && jdks.stream().noneMatch(j -> sameHome(j, jdk))) {
			jdks.add(jdk);
		}
	}

	private static boolean sameHome(Jdk a, Jdk b) {
		try {
			return Files.isSameFile(a.home(), b.home());
		} catch (IOException e) {
			return a.home().toAbsolutePath().equals(b.home().toAbsolutePath());
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
				.sorted(Comparator.comparingInt(Jdk::majorVersion).reversed())
				.collect(Collectors.toList());
		} catch (IOException e) {
			Util.verboseMsg("Could not list " + jdksDir + ": " + e);
			return new ArrayList<>();
		}
	}

	/**
	 * Downloads the latest GA release of the given major version from Foojay and
	 * installs it as $JBANG_CACHE_DIR/jdks/&lt;version&gt;. When no default JDK
	 * is set yet, the new JDK becomes the default.
	 */
	public Jdk install(int version) {
		if (Util.isOffline()) {
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
					"No suitable JDK was found for requested version " + version + " and we are offline");
		}
		Path jdkDir = jdksDir.resolve(Integer.toString(version));
		Path tmpDir = jdksDir.resolve(version + ".tmp");
		Util.deletePath(tmpDir, true);
		String url = foojayUrl(version);
		Util.infoMsg("Downloading JDK " + version + ". Be patient, this can take several minutes...");
		Util.verboseMsg("Downloading " + url);
		Path pkg = null;
		try {
			String ext = Util.isWindows() ? "zip" : "tar.gz";
			pkg = Settings.getCacheDir(Settings.CacheClass.urls).resolve("bootstrap-jdk-" + version + "." + ext);
			Downloader.download(url, pkg);
			Util.infoMsg("Installing JDK " + version + "...");
			Unpacker.unpackJdk(pkg, tmpDir);
			if (!Jdk.resolveVersion(tmpDir).isPresent()) {
				throw new IOException("The JDK package does not seem to contain a valid JDK");
			}
			Util.deletePath(jdkDir, true);
			Files.move(tmpDir, jdkDir);
		} catch (IOException | RuntimeException e) {
			Util.deletePath(tmpDir, true);
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
					"Unable to download or install JDK version " + version + " (" + e.getMessage() + ")", e);
		} finally {
			if (pkg != null) {
				Util.deletePath(pkg, true);
			}
		}
		Jdk jdk = Jdk.of(jdkDir, "jbang");
		if (jdk == null) {
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR, "Failed to find JDK in: " + jdkDir);
		}
		installed = null;
		if (getDefaultJdk() == null) {
			setDefaultJdk(jdk);
		}
		return jdk;
	}

	static String foojayUrl(int version) {
		String distros = Settings.getJdkDistros();
		if (distros == null) {
			distros = (version == 8 || version == 11 || version >= 17) ? "temurin" : "aoj";
		}
		Util.OS os = Util.getOS();
		String libc = os == Util.OS.windows ? "c_std_lib"
				: os == Util.OS.mac ? "libc" : os == Util.OS.alpine_linux ? "musl" : "glibc";
		return FOOJAY_DIRECT_URI + "distro=" + distros
				+ "&javafx_bundled=false"
				+ "&libc_type=" + libc
				+ "&archive_type=" + (os == Util.OS.windows ? "zip" : "tar.gz")
				+ "&operating_system=" + os.name().replace('_', '-')
				+ "&package_type=jdk"
				+ "&version=" + version
				+ "&architecture=" + Util.getArch().name()
				+ "&latest=available";
	}

	/** The JDK the default link points to, or null. */
	public Jdk getDefaultJdk() {
		if (!Files.exists(defaultLink, LinkOption.NOFOLLOW_LINKS)) {
			return null;
		}
		if (!Files.exists(defaultLink)) {
			Util.verboseMsg("Removing broken default JDK link " + defaultLink);
			Util.deletePath(defaultLink, true);
			return null;
		}
		return Jdk.of(defaultLink, "default");
	}

	public void setDefaultJdk(Jdk jdk) {
		Jdk current = getDefaultJdk();
		if (current != null && sameHome(current, jdk)) {
			Util.infoMsg("Default JDK already set to " + jdk.majorVersion());
			return;
		}
		Util.createLink(defaultLink, jdk.home());
		Util.infoMsg("Default JDK set to " + jdk.majorVersion() + " (" + jdk.home() + ")");
		installed = null;
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
}
