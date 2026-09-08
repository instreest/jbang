package dev.jbang;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;

/**
 * Everything known about a script: the main source file, the extra sources it
 * pulls in through <code>//SOURCES</code> (recursively), the
 * <code>//DEPS</code> coordinates and the <code>//JAVA</code> version. Also
 * decides where the build output goes:
 * <code>$JBANG_CACHE_DIR/jars/&lt;file&gt;.&lt;hash&gt;/&lt;base&gt;.jar</code>,
 * where the hash is derived from the contents of all sources, so a change in
 * any of them triggers a rebuild.
 */
public final class Project {
	private final Path mainSource;
	private final Set<Path> sources = new LinkedHashSet<>();
	private final Set<String> dependencies = new LinkedHashSet<>();
	private final Properties contextProperties;
	private String javaVersion;
	private String mainClass;
	private final Map<String, String> userProperties;
	private JdkManager jdkManager;

	// cached values
	private String stableId;
	private List<ArtifactInfo> classPath;
	private Jdk jdk;

	public Project(Path mainSource, Map<String, String> userProperties, List<String> extraDeps,
			String forcedJavaVersion) {
		this.mainSource = mainSource.toAbsolutePath().normalize();
		this.userProperties = userProperties;
		this.contextProperties = new Properties(System.getProperties());
		OsDetector.detect(contextProperties);
		contextProperties.putAll(userProperties);
		addSource(this.mainSource);
		dependencies.addAll(extraDeps);
		if (forcedJavaVersion != null) {
			// the command line overrides whatever the sources requested
			javaVersion = RequestedVersion.parse(forcedJavaVersion).toString();
		}
	}

	private Function<String, String> propertyReplacer() {
		return item -> PropertiesValueResolver.replaceProperties(item, contextProperties);
	}

	private void addSource(Path source) {
		if (!sources.add(source)) {
			return;
		}
		if (!Files.isReadable(source)) {
			throw new ExitException(ExitException.EXIT_INVALID_INPUT, "Source file could not be found or read: " + source);
		}
		Directives directives = new Directives(Util.readString(source), propertyReplacer());
		dependencies.addAll(directives.dependencies());
		String version = directives.javaVersion();
		if (version != null && (javaVersion == null
				|| RequestedVersion.parse(javaVersion).compareTo(RequestedVersion.parse(version)) < 0)) {
			javaVersion = version;
		}
		Path baseDir = source.getParent();
		for (String pattern : directives.sources()) {
			List<String> files = Util.explode(baseDir, pattern);
			if (files.isEmpty()) {
				Util.warnMsg("//SOURCES " + pattern + " (in " + source.getFileName() + ") matched no files");
			}
			for (String f : files) {
				addSource(baseDir.resolve(f).toAbsolutePath().normalize());
			}
		}
	}

	public Path getMainSource() {
		return mainSource;
	}

	public List<Path> getSources() {
		return new ArrayList<>(sources);
	}

	public List<String> getDependencies() {
		return new ArrayList<>(dependencies);
	}

	/** Requested Java version like "17" or "17+", or null. */
	public String getJavaVersion() {
		return javaVersion;
	}

	public Map<String, String> getUserProperties() {
		return userProperties;
	}

	public String getMainClass() {
		return mainClass;
	}

	public void setMainClass(String mainClass) {
		this.mainClass = mainClass;
	}

	public void setJdkManager(JdkManager jdkManager) {
		this.jdkManager = jdkManager;
	}

	public Jdk getJdk() {
		if (jdk == null) {
			if (jdkManager == null) {
				jdkManager = new JdkManager();
			}
			jdk = jdkManager.getOrInstallJdk(javaVersion);
		}
		return jdk;
	}

	public List<ArtifactInfo> resolveClassPath() {
		if (classPath == null) {
			classPath = DependencyResolver.resolve(getDependencies());
		}
		return classPath;
	}

	/** Class path of the dependencies only (without the script's jar). */
	public String getDependencyClassPath() {
		return resolveClassPath().stream()
			.map(a -> a.getFile().toAbsolutePath().toString())
			.distinct()
			.reduce((a, b) -> a + Settings.CP_SEPARATOR + b)
			.orElse("");
	}

	public String getStableId() {
		if (stableId == null) {
			stableId = Util.getStableID(sources.stream().map(Util::readString));
		}
		return stableId;
	}

	public Path getBuildDir() {
		return Settings.getCacheDir(Settings.CacheClass.jars)
			.resolve(mainSource.getFileName() + "." + getStableId());
	}

	public Path getJarFile() {
		return getBuildDir().resolve(Util.getBaseName(mainSource.getFileName().toString()) + ".jar");
	}

	public Path getCompileDir() {
		return getBuildDir().resolve("classes");
	}
}
