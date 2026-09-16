package io.github.instreest.jkite;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import dev.jbang.ExitException;
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
 *     jdks/           JDKs installed by jkite
 *     urls/           files being downloaded
 *     .locks/         held while a cache entry is written
 *     dependency_cache.txt
 * </pre>
 *
 * Every one of them can be thrown away: the next run builds or fetches what it
 * needs again. <code>--clear-cache</code> does exactly that.
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
	/** The one JDK distribution jkite installs: Eclipse Temurin. */
	public static final String JDK_DISTRO = "temurin";
	/**
	 * The host {@link #JDK_DISTRO} publishes its archives on. The JVM index says
	 * where to download a JDK from, and nothing else vouches for what it says, so
	 * an archive is only fetched from here. Every Temurin entry in the index
	 * points at this host; one that does not is a reason to stop, not to follow.
	 */
	public static final String JDK_DOWNLOAD_HOST = "github.com";
	/**
	 * The account on {@link #JDK_DOWNLOAD_HOST} that publishes them. The host
	 * alone is not enough: anyone can put a release on github.com, so a URL is
	 * only followed when it also comes from this account. Every Temurin entry in
	 * the index is under it.
	 */
	public static final String JDK_DOWNLOAD_PATH_PREFIX = "/adoptium/";
	/**
	 * Where a download from {@link #JDK_DOWNLOAD_HOST} is allowed to redirect.
	 * GitHub answers a release asset with a redirect to a content host whose
	 * name it has changed before and will change again, so the suffix is
	 * allowed rather than one name that would turn a GitHub change into a jkite
	 * outage.
	 */
	public static final String JDK_REDIRECT_HOST_SUFFIX = ".githubusercontent.com";
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
		String v = System.getenv(ENV_CACHE_DIR + "_" + cclass.name().toUpperCase(Locale.ROOT));
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

	/**
	 * Throws away what the cache can produce again: the built jars, whatever a
	 * download left behind, and the resolved class paths. The installed JDKs
	 * are left where they are - they are pinned, there are few of them, and
	 * fetching one again costs minutes - and so is jkite's own jar, which is
	 * running.
	 *
	 * @return one line per thing it did, for the caller to print
	 */
	public static List<String> clearCache() {
		List<String> report = new ArrayList<>();
		report.add(removeContents(getCacheDir(CacheClass.jars), "built jars"));
		report.add(removeContents(getCacheDir(CacheClass.urls), "unfinished downloads"));
		Path deps = getDependencyCacheFile();
		if (Files.exists(deps)) {
			report.add(Util.deletePath(deps, true)
					? "removed the resolved dependencies of " + deps
					: "could not remove " + deps);
		}
		report.add("kept the JDKs in " + getCacheDir(CacheClass.jdks)
				+ " (remove that directory by hand to fetch them again)");
		return report;
	}

	private static String removeContents(Path dir, String what) {
		int removed = 0;
		int kept = 0;
		try (Stream<Path> entries = Files.list(dir)) {
			for (Path entry : entries.collect(Collectors.toList())) {
				if (Util.deletePath(entry, true)) {
					removed++;
				} else {
					kept++;
				}
			}
		} catch (IOException e) {
			return "could not read " + dir + ": " + e;
		}
		return "removed " + removed + " " + what + " from " + dir
				+ (kept > 0 ? " (" + kept + " could not be removed)" : "");
	}

	/** The lock files that keep concurrent runs out of each other's writes. */
	public static Path getLockDir() {
		return mkdirs(getCacheDir().resolve(".locks"));
	}

	/**
	 * Creates the directory if it is not there yet. A failure is reported here,
	 * where the directory and the variable that named it are still known;
	 * leaving it to whatever writes there next turns "the cache directory
	 * cannot be created" into an unrelated-looking error further on.
	 */
	private static Path mkdirs(Path dir) {
		if (!Files.isDirectory(dir) && !dir.toFile().mkdirs() && !Files.isDirectory(dir)) {
			throw new ExitException(ExitException.EXIT_UNEXPECTED_STATE,
					"Could not create the directory " + dir + ". Set " + ENV_DIR + " or " + ENV_CACHE_DIR
							+ " to a directory that can be written to.");
		}
		return dir;
	}
}
