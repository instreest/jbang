package dev.jbang;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import dev.jbang.util.Util;

/**
 * Locations and environment driven settings. The directory layout is kept
 * identical to the one used by the full JBang so that the launcher scripts
 * (and tools such as java-call-hierarchy-exporter) keep working:
 *
 * <pre>
 * $JBANGLITE_DIR (~/.jbanglite)
 *   cache/            ($JBANGLITE_CACHE_DIR)
 *     jars/           compiled scripts
 *     jdks/           JDKs installed by JBang
 *     stdin/          scripts read from stdin, by content hash
 *     urls/           downloaded files
 *     dependency_cache.txt
 * </pre>
 */
public final class Settings {
	public static final String ENV_DIR = "JBANGLITE_DIR";
	public static final String ENV_CACHE_DIR = "JBANGLITE_CACHE_DIR";
	public static final String ENV_MAVEN_REPO = "JBANGLITE_MAVEN_REPO";
	public static final String ENV_DEFAULT_JAVA_VERSION = "JBANGLITE_DEFAULT_JAVA_VERSION";
	/** ask / allow / deny: whether anything may be downloaded. Empty means ask. */
	public static final String ENV_NETWORK = "JBANGLITE_NETWORK";
	public static final String ENV_DOWNLOAD_RETRY = "JBANGLITE_DOWNLOAD_RETRY";
	public static final String ENV_DOWNLOAD_RETRY_DELAY = "JBANGLITE_DOWNLOAD_RETRY_DELAY";

	public static final String CP_SEPARATOR = File.pathSeparator;
	public static final String DEPENDENCY_CACHE_FILE = "dependency_cache.txt";

	public static final int DEFAULT_JAVA_VERSION = 17;
	/** The one JDK distribution JBangLite installs: Eclipse Temurin. */
	public static final String JDK_DISTRO = "temurin";
	public static final int DEFAULT_DOWNLOAD_RETRY = 5;

	public enum CacheClass {
		urls, jars, jdks
	}

	private Settings() {
	}

	public static Path getConfigDir() {
		String jd = System.getenv(ENV_DIR);
		Path dir = jd != null ? Paths.get(jd) : Paths.get(System.getProperty("user.home")).resolve(".jbanglite");
		return mkdirs(dir);
	}

	public static Path getCacheDir() {
		String v = System.getenv(ENV_CACHE_DIR);
		Path dir = v != null ? Paths.get(v) : getConfigDir().resolve("cache");
		return mkdirs(dir);
	}

	public static Path getCacheDir(CacheClass cclass) {
		String v = System.getenv(ENV_CACHE_DIR + "_" + cclass.name().toUpperCase());
		Path dir = v != null ? Paths.get(v) : getCacheDir().resolve(cclass.name());
		return mkdirs(dir);
	}

	public static Path getDependencyCacheFile() {
		return getCacheDir().resolve(DEPENDENCY_CACHE_FILE);
	}

	/** Optional override of the local Maven repository (JBANGLITE_MAVEN_REPO). */
	public static Path getLocalMavenRepoOverride() {
		String repo = System.getenv(ENV_MAVEN_REPO);
		return repo != null ? Paths.get(repo) : null;
	}

	public static int getDefaultJavaVersion() {
		String v = System.getenv(ENV_DEFAULT_JAVA_VERSION);
		if (v != null) {
			try {
				return Integer.parseInt(v.trim());
			} catch (NumberFormatException e) {
				Util.warnMsg("Ignoring invalid " + ENV_DEFAULT_JAVA_VERSION + ": " + v);
			}
		}
		return DEFAULT_JAVA_VERSION;
	}

	/** Number of extra download attempts, see also the launcher scripts. */
	public static int getDownloadRetry() {
		return intFromEnv(ENV_DOWNLOAD_RETRY, DEFAULT_DOWNLOAD_RETRY);
	}

	/** Seconds between download attempts, 0 meaning exponential backoff. */
	public static int getDownloadRetryDelay() {
		return intFromEnv(ENV_DOWNLOAD_RETRY_DELAY, 0);
	}

	private static int intFromEnv(String name, int defaultValue) {
		String v = System.getenv(name);
		if (v != null && !v.trim().isEmpty()) {
			try {
				return Integer.parseInt(v.trim());
			} catch (NumberFormatException e) {
				Util.warnMsg("Ignoring invalid " + name + ": " + v);
			}
		}
		return defaultValue;
	}

	private static Path mkdirs(Path dir) {
		if (!Files.isDirectory(dir)) {
			dir.toFile().mkdirs();
		}
		return dir;
	}
}
