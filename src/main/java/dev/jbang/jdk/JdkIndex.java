package dev.jbang.jdk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import dev.jbang.ExitException;
import dev.jbang.Settings;
import dev.jbang.dependencies.DependencyResolver;
import dev.jbang.util.Json;
import dev.jbang.util.RequestedVersion;
import dev.jbang.util.Util;

/**
 * The list of downloadable JDKs. JBangLite uses the JVM index that the
 * Coursier project publishes to Maven Central as
 * <code>io.get-coursier.jvm.indices:index-&lt;platform&gt;</code>, so no
 * separate discovery service has to be reachable: the index travels over the
 * same Maven repository (and therefore the same mirrors, proxies and
 * credentials) that JBangLite already needs for <code>//DEPS</code>.
 *
 * The index maps a distribution and version to the distributor's own download
 * URL, for example
 * <code>temurin -&gt; 25.0.3 -&gt; tgz+https://github.com/adoptium/...tar.gz</code>.
 */
public final class JdkIndex {
	static final String INDEX_GROUP_ID = "io.get-coursier.jvm.indices";
	static final String INDEX_VERSION_RANGE = "[0,)";
	static final String INDEX_ENTRY_PREFIX = "coursier/jvm/indices/v1/";

	/** One downloadable JDK. */
	public static final class Entry {
		public final String distro;
		public final String version;
		public final String archiveType;
		public final String url;

		Entry(String distro, String version, String archiveType, String url) {
			this.distro = distro;
			this.version = version;
			this.archiveType = archiveType;
			this.url = url;
		}

		@Override
		public String toString() {
			return distro + " " + version + " (" + url + ")";
		}
	}

	private final String platform;
	private final Map<String, Object> index;

	private static JdkIndex cached;

	private JdkIndex(String platform, Map<String, Object> index) {
		this.platform = platform;
		this.index = index;
	}

	/** Loads (and caches) the index for the current platform. */
	public static JdkIndex instance() {
		if (cached == null) {
			String platform = platform();
			cached = new JdkIndex(platform, read(platform));
		}
		return cached;
	}

	private static Map<String, Object> read(String platform) {
		String source = INDEX_GROUP_ID + ":index-" + platform + ":" + INDEX_VERSION_RANGE;
		Util.verboseMsg("Resolving JDK index: " + source);
		String json;
		try {
			json = readFromJar(DependencyResolver.resolveArtifact(source), platform);
		} catch (IOException e) {
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
					"Could not read the JDK index for " + platform + ": " + e.getMessage(), e);
		}
		return Json.parseObject(json);
	}

	private static String readFromJar(Path jar, String platform) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(INDEX_ENTRY_PREFIX + platform + ".json");
			if (entry == null) {
				throw new IOException("No index for " + platform + " in " + jar);
			}
			try (InputStream is = zip.getInputStream(entry)) {
				return Util.readString(is);
			}
		}
	}

	/** The index name of the current platform, e.g. "linux-amd64". */
	public static String platform() {
		Util.OS os = Util.getOS();
		String osName;
		switch (os) {
		case linux:
			osName = "linux";
			break;
		case alpine_linux:
			// the index only lists glibc builds, which do not run on musl
			Util.warnMsg("The JDK index has no musl (Alpine) builds; install a JDK yourself");
			osName = "linux";
			break;
		case mac:
			osName = "darwin";
			break;
		case windows:
			osName = "windows";
			break;
		default:
			throw new ExitException(ExitException.EXIT_UNEXPECTED_STATE,
					"No JDKs can be downloaded for this operating system: " + os);
		}
		Util.Arch arch = Util.getArch();
		String archName;
		switch (arch) {
		case x64:
			archName = "amd64";
			break;
		case x32:
			archName = "x86";
			break;
		case aarch64:
		case arm64:
			archName = "arm64";
			break;
		case arm:
			archName = "arm";
			break;
		case ppc64le:
			archName = "ppc64le";
			break;
		case s390x:
			archName = "s390x";
			break;
		case riscv64:
			archName = "riscv64";
			break;
		default:
			throw new ExitException(ExitException.EXIT_UNEXPECTED_STATE,
					"No JDKs can be downloaded for this architecture: " + arch);
		}
		return osName + "-" + archName;
	}

	/**
	 * The newest version satisfying the request, looking at each configured
	 * distribution in turn.
	 */
	public Optional<Entry> find(RequestedVersion version) {
		for (String distro : Collections.singletonList(Settings.JDK_DISTRO)) {
			Optional<Entry> found = find(distro, version);
			if (found.isPresent()) {
				return found;
			}
			Util.verboseMsg("No JDK " + version + " for " + platform + " in distribution '" + distro + "'");
		}
		return Optional.empty();
	}

	Optional<Entry> find(String distro, RequestedVersion version) {
		Map<String, String> versions = versionsOf(distro);
		String bestVersion = null;
		String bestValue = null;
		for (Map.Entry<String, String> e : versions.entrySet()) {
			if (!version.matches(e.getKey())) {
				continue;
			}
			if (bestVersion == null || RequestedVersion.compare(RequestedVersion.componentsOf(e.getKey()),
					RequestedVersion.componentsOf(bestVersion)) > 0) {
				bestVersion = e.getKey();
				bestValue = e.getValue();
			}
		}
		if (bestVersion == null) {
			return Optional.empty();
		}
		// values have the form "<archive type>+<url>", e.g. "tgz+https://..."
		int sep = bestValue.indexOf('+');
		if (sep < 0) {
			Util.warnMsg("Unexpected JDK index entry for " + distro + " " + bestVersion + ": " + bestValue);
			return Optional.empty();
		}
		return Optional.of(new Entry(distro, bestVersion, bestValue.substring(0, sep), bestValue.substring(sep + 1)));
	}

	@SuppressWarnings("unchecked")
	private Map<String, String> versionsOf(String distro) {
		Object entry = index.get(distro);
		if (!(entry instanceof Map)) {
			return Collections.emptyMap();
		}
		Map<String, String> versions = new LinkedHashMap<>();
		((Map<String, Object>) entry).forEach((version, value) -> {
			if (value instanceof String) {
				versions.put(version, (String) value);
			}
		});
		return versions;
	}
}
