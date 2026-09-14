package io.github.instreest.jkite;

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
 * $JKITE_DIR (~/.jkite)
 *   cache/            ($JKITE_CACHE_DIR)
 *     jars/           compiled scripts
 *     jdks/           JDKs installed by JBang
 *     stdin/          scripts read from stdin, by content hash
 *     urls/           downloaded files
 *     dependency_cache.txt
 * </pre>
 */
public final class Settings {
	public static final String ENV_DIR = "JKITE_DIR";
	public static final String ENV_CACHE_DIR = "JKITE_CACHE_DIR";
	public static final String ENV_MAVEN_REPO = "JKITE_MAVEN_REPO";
	public static final String ENV_DEFAULT_JAVA_VERSION = "JKITE_DEFAULT_JAVA_VERSION";
	public static final String ENV_JDK_INDEX = "JKITE_JDK_INDEX";
	public static final String ENV_DOWNLOAD_RETRY = "JKITE_DOWNLOAD_RETRY";
	public static final String ENV_DOWNLOAD_RETRY_DELAY = "JKITE_DOWNLOAD_RETRY_DELAY";
	/** auto / always / never: whether a download is confirmed before it starts. */
	public static final String ENV_CONFIRM_DOWNLOADS = "JKITE_CONFIRM_DOWNLOADS";
	public static final String ENV_ASSUME_YES = "JKITE_ASSUME_YES";

	public static final String CP_SEPARATOR = File.pathSeparator;
	public static final String DEPENDENCY_CACHE_FILE = "dependency_cache.txt";

	public static final int DEFAULT_JAVA_VERSION = 17;
	/** The one JDK distribution JKite installs: Eclipse Temurin. */
	public static final String JDK_DISTRO = "temurin";
	/**
	 * The host {@link #JDK_DISTRO} publishes its archives on. The JVM index says
	 * where to download a JDK from, and nothing else vouches for what it says, so
	 * an archive is only fetched from here. Every Temurin entry in the index
	 * points at this host; one that does not is a reason to stop, not to follow.
	 */
	public static final String JDK_DOWNLOAD_HOST = "github.com";
	public static final int DEFAULT_DOWNLOAD_RETRY = 5;

	public enum CacheClass {
		urls, jars, jdks
	}

	private Settings() {
	}

	public static Path getConfigDir() {
		String jd = System.getenv(ENV_DIR);
		Path dir = jd != null ? Paths.get(jd) : Paths.get(System.getProperty("user.home")).resolve(".jkite");
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

	/** Optional override of the local Maven repository (JKITE_MAVEN_REPO). */
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

	/**
	 * How the download gate asks before fetching a JDK or dependencies:
	 * "auto" (the default: only when there is a terminal to ask on), "always"
	 * or "never".
	 */
	public static String getConfirmDownloads() {
		String v = System.getenv(ENV_CONFIRM_DOWNLOADS);
		return v != null && !v.trim().isEmpty() ? v.trim().toLowerCase() : "auto";
	}

	/** JKITE_ASSUME_YES, the environment's form of <code>--yes</code>. */
	public static boolean isAssumeYes() {
		String v = System.getenv(ENV_ASSUME_YES);
		if (v == null) {
			return false;
		}
		String s = v.trim().toLowerCase();
		return s.equals("1") || s.equals("true") || s.equals("yes");
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
