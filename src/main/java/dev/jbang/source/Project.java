package dev.jbang.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.jbang.ExitException;
import dev.jbang.Settings;
import dev.jbang.dependencies.ArtifactInfo;
import dev.jbang.dependencies.DependencyResolver;
import dev.jbang.dependencies.DependencyUtil;
import dev.jbang.dependencies.MavenRepo;
import dev.jbang.jdk.Jdk;
import dev.jbang.jdk.JdkManager;
import dev.jbang.source.parser.Directives;
import dev.jbang.source.parser.KeyValue;
import dev.jbang.util.JavaUtil;
import dev.jbang.util.ModuleUtil;
import dev.jbang.util.OsDetector;
import dev.jbang.util.PropertiesValueResolver;
import dev.jbang.util.Util;

/**
 * Everything known about a script: its sources, the resources, dependencies,
 * options and settings gathered from the <code>//</code>-directives of the main
 * file and of every file it pulls in with <code>//SOURCES</code>.
 *
 * The directives are parsed by {@link Directives}, which is mirrored from
 * JBang, and are applied here with the same rules JBang uses: description, GAV,
 * main class and module name come from the main file only, everything else
 * accumulates over all files.
 *
 * The build output goes to
 * <code>$JBANG_CACHE_DIR/jars/&lt;file&gt;.&lt;hash&gt;/&lt;base&gt;.jar</code>,
 * where the hash covers the contents of all sources and resources, so a change
 * in any of them triggers a rebuild.
 */
public class Project {
	public static final String ATTR_PREMAIN_CLASS = "Premain-Class";
	public static final String ATTR_AGENT_CLASS = "Agent-Class";
	public static final String ATTR_ADD_EXPORTS = "Add-Exports";
	public static final String ATTR_ADD_OPENS = "Add-Opens";
	public static final String ATTR_ENABLE_NATIVE_ACCESS = "Enable-Native-Access";

	/** A file to copy into the jar, as declared by <code>//FILES</code>. */
	public static final class FileRef {
		private final Path source;
		private final Path target;

		FileRef(Path source, Path target) {
			this.source = source;
			this.target = target;
		}

		public Path getSource() {
			return source;
		}

		public Path to(Path parent) {
			return parent.resolve(target != null ? target : source.getFileName());
		}

		void copy(Path destroot) {
			Path to = to(destroot);
			Util.verboseMsg("Copying " + source + " to " + to);
			try {
				Files.createDirectories(to.getParent());
				Files.copy(source, to, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				throw new ExitException(ExitException.EXIT_UNEXPECTED_STATE,
						"Could not copy " + source + " to " + to, e);
			}
		}
	}

	private final Path mainSource;
	private final Set<Path> sources = new LinkedHashSet<>();
	private final List<FileRef> resources = new ArrayList<>();
	private final Set<String> dependencies = new LinkedHashSet<>();
	private final List<MavenRepo> repositories = new ArrayList<>();
	private final List<String> compileOptions = new ArrayList<>();
	private final List<String> runtimeOptions = new ArrayList<>();
	private final Map<String, String> manifestAttributes = new LinkedHashMap<>();
	private final List<Project> subProjects = new ArrayList<>();
	private final List<KeyValue> docs = new ArrayList<>();
	private final Map<String, String> properties;
	private final Properties contextProperties;

	private String javaVersion;
	private String mainClass;
	private String moduleName;
	private String gav;
	private String description;
	private boolean agent;
	private boolean enableCDS;
	private boolean enablePreview;

	private final Path mainBaseDir;
	private JdkManager jdkManager;

	// cached values
	private String stableId;
	private List<ArtifactInfo> classPath;
	private Jdk jdk;

	public Project(Path mainSource, Map<String, String> properties, List<String> extraDeps,
			List<String> extraRepos, List<String> extraCompileOptions, List<String> extraRuntimeOptions,
			String forcedJavaVersion, String forcedMainClass, String forcedModuleName) {
		this(mainSource, null, properties, extraDeps, extraRepos, extraCompileOptions, extraRuntimeOptions,
				forcedJavaVersion, forcedMainClass, forcedModuleName);
	}

	/**
	 * As above, with the directory that relative <code>//SOURCES</code> and
	 * <code>//FILES</code> of the main source are resolved against; null means
	 * the main source's own directory. Used when the main source was read from
	 * stdin and lives in the cache, but was written from the working directory.
	 */
	public Project(Path mainSource, Path mainBaseDir, Map<String, String> properties, List<String> extraDeps,
			List<String> extraRepos, List<String> extraCompileOptions, List<String> extraRuntimeOptions,
			String forcedJavaVersion, String forcedMainClass, String forcedModuleName) {
		this(mainSource, mainBaseDir, properties, new LinkedHashSet<>());
		dependencies.addAll(extraDeps);
		extraRepos.forEach(r -> addRepository(DependencyUtil.toMavenRepo(replaceProperties(r))));
		compileOptions.addAll(extraCompileOptions);
		runtimeOptions.addAll(extraRuntimeOptions);
		if (forcedJavaVersion != null) {
			JavaUtil.checkRequestedVersion(forcedJavaVersion);
			javaVersion = forcedJavaVersion;
		}
		if (forcedMainClass != null) {
			mainClass = forcedMainClass;
		}
		if (forcedModuleName != null) {
			moduleName = forcedModuleName;
		}
	}

	private Project(Path mainSource, Path mainBaseDir, Map<String, String> properties, Set<Path> beingBuilt) {
		this.mainSource = mainSource.toAbsolutePath().normalize();
		this.mainBaseDir = mainBaseDir != null ? mainBaseDir.toAbsolutePath().normalize() : null;
		this.properties = properties;
		this.contextProperties = new Properties(System.getProperties());
		OsDetector.detect(contextProperties);
		contextProperties.putAll(properties);
		if (!beingBuilt.add(this.mainSource)) {
			throw new ExitException(ExitException.EXIT_INVALID_INPUT,
					"Self-referencing project dependency found for: '" + this.mainSource + "'");
		}
		addSource(this.mainSource, true, beingBuilt);
	}

	private String replaceProperties(String item) {
		return PropertiesValueResolver.replaceProperties(item, contextProperties);
	}

	private Function<String, String> propertyReplacer() {
		return this::replaceProperties;
	}

	/**
	 * Reads a source file and applies its directives, then does the same for
	 * every file it names with //SOURCES.
	 */
	private void addSource(Path source, boolean main, Set<Path> beingBuilt) {
		if (!sources.add(source)) {
			return;
		}
		if (!Files.isReadable(source)) {
			throw new ExitException(ExitException.EXIT_INVALID_INPUT,
					"Source file could not be found or read: " + source);
		}
		Directives directives = new Directives.Extended(Util.readString(source), propertyReplacer());
		Path baseDir = main && mainBaseDir != null ? mainBaseDir : source.getParent();

		if (main) {
			description = directives.description();
			gav = directives.gav();
			if (mainClass == null) {
				mainClass = directives.mainMethod();
			}
			if (moduleName == null) {
				moduleName = directives.module();
			}
			agent = directives.isAgent();
			enableCDS = directives.enableCDS();
			enablePreview = enablePreview || directives.enablePreview();
			// as JBang does for Java sources, so that debugging and named
			// parameters keep working
			compileOptions.add("-g");
			compileOptions.add("-parameters");
		}

		dependencies.addAll(directives.binaryDependencies());
		addRepositories(directives.repositories());
		compileOptions.addAll(directives.compileOptions());
		runtimeOptions.addAll(directives.runtimeOptions());
		docs.addAll(directives.collectDocs());
		directives.manifestOptions().forEach(this::putManifestAttribute);
		directives.agentOptions().forEach(this::putManifestAttribute);
		resources.addAll(toFileRefs(directives.files(), baseDir));

		String version = directives.javaVersion();
		if (version != null && JavaUtil.checkRequestedVersion(version)
				&& new JavaUtil.RequestedVersionComparator().compare(javaVersion, version) > 0) {
			javaVersion = version;
		}

		for (String srcDep : directives.sourceDependencies()) {
			Path dep = baseDir.resolve(replaceProperties(srcDep)).toAbsolutePath().normalize();
			subProjects.add(new Project(dep, null, properties, beingBuilt));
		}

		for (String pattern : directives.sources()) {
			List<String> files = Util.explode(null, baseDir, pattern);
			if (files.isEmpty()) {
				Util.warnMsg("//SOURCES " + pattern + " (in " + source.getFileName() + ") matched no files");
			}
			for (String f : files) {
				addSource(baseDir.resolve(f).toAbsolutePath().normalize(), false, beingBuilt);
			}
		}
	}

	private void putManifestAttribute(KeyValue kv) {
		if (!kv.getKey().isEmpty()) {
			manifestAttributes.put(kv.getKey(), kv.getValue() != null ? kv.getValue() : "true");
		}
	}

	/** Turns //FILES entries (with globs and optional aliases) into copy jobs. */
	private List<FileRef> toFileRefs(List<KeyValue> files, Path baseDir) {
		return files.stream()
			.flatMap(kv -> Directives.explodeFileRef(null, baseDir, kv).stream())
			.map(ref -> {
				String[] split = ref.split("=", 2);
				String src = split.length == 1 ? split[0] : split[1];
				String dest = split.length == 1 ? null : split[0];
				Path target = dest != null && !dest.isEmpty() ? Paths.get(dest) : null;
				if (target != null && target.isAbsolute()) {
					throw new ExitException(ExitException.EXIT_INVALID_INPUT,
							"Only relative paths allowed in //FILES. Found absolute path: " + dest);
				}
				Path from = baseDir.resolve(src).toAbsolutePath().normalize();
				if (!Files.isReadable(from)) {
					throw new ExitException(ExitException.EXIT_INVALID_INPUT,
							"File could not be found or read: " + from);
				}
				if (target != null && dest.endsWith("/")) {
					target = target.resolve(from.getFileName());
				}
				return new FileRef(from, target);
			})
			.collect(Collectors.toList());
	}

	// ------------------------------------------------------------- accessors

	public Path getMainSource() {
		return mainSource;
	}

	public List<Path> getSources() {
		return new ArrayList<>(sources);
	}

	public List<FileRef> getResources() {
		return Collections.unmodifiableList(resources);
	}

	public List<String> getDependencies() {
		return new ArrayList<>(dependencies);
	}

	public List<MavenRepo> getRepositories() {
		return Collections.unmodifiableList(repositories);
	}

	public void addRepositories(List<MavenRepo> repos) {
		repos.forEach(this::addRepository);
	}

	private void addRepository(MavenRepo repo) {
		if (repositories.stream().noneMatch(r -> r.getId().equals(repo.getId()))) {
			repositories.add(repo);
		}
	}

	public List<String> getCompileOptions() {
		return Collections.unmodifiableList(compileOptions);
	}

	public List<String> getRuntimeOptions() {
		return Collections.unmodifiableList(runtimeOptions);
	}

	public Map<String, String> getManifestAttributes() {
		return manifestAttributes;
	}

	public void setAgentMainClass(String value) {
		manifestAttributes.put(ATTR_AGENT_CLASS, value);
	}

	public void setPreMainClass(String value) {
		manifestAttributes.put(ATTR_PREMAIN_CLASS, value);
	}

	public List<Project> getSubProjects() {
		return Collections.unmodifiableList(subProjects);
	}

	public List<KeyValue> getDocs() {
		return Collections.unmodifiableList(docs);
	}

	public Map<String, String> getProperties() {
		return properties;
	}

	/** Requested Java version like "17" or "17+", or null. */
	public String getJavaVersion() {
		return javaVersion;
	}

	public String getMainClass() {
		return mainClass;
	}

	public void setMainClass(String mainClass) {
		this.mainClass = mainClass;
	}

	public Optional<String> getModuleName() {
		return Optional.ofNullable(moduleName);
	}

	public Optional<String> getGav() {
		return Optional.ofNullable(gav);
	}

	public Optional<String> getDescription() {
		return Optional.ofNullable(description);
	}

	public boolean isAgent() {
		return agent;
	}

	public boolean enableCDS() {
		return enableCDS;
	}

	public boolean enablePreview() {
		return enablePreview;
	}

	public void setEnablePreview(boolean enablePreview) {
		this.enablePreview = enablePreview;
	}

	public void setJdkManager(JdkManager jdkManager) {
		this.jdkManager = jdkManager;
	}

	public Jdk getJdk() {
		if (jdk == null) {
			if (jdkManager == null) {
				jdkManager = new JdkManager();
			}
			String requested = javaVersion;
			if (requested == null && moduleName != null) {
				// modules need at least Java 9
				requested = "9+";
			}
			jdk = jdkManager.getOrInstallJdk(requested);
		}
		return jdk;
	}

	/** The dependencies of this project and of all its sub-projects. */
	public List<ArtifactInfo> resolveClassPath() {
		if (classPath == null) {
			DependencyResolver resolver = new DependencyResolver()
				.addRepositories(repositories)
				.addDependencies(getDependencies());
			for (Project sub : subProjects) {
				resolver.addRepositories(sub.getRepositories()).addDependencies(sub.getDependencies());
				resolver.addClassPath(sub.getJarFile().toAbsolutePath().toString());
			}
			classPath = resolver.resolve();
		}
		return classPath;
	}

	/** Class path of the dependencies only, without this project's own jar. */
	public String getDependencyClassPath() {
		return resolveClassPath().stream()
			.map(a -> a.getFile().toAbsolutePath().toString())
			.distinct()
			.collect(Collectors.joining(Settings.CP_SEPARATOR));
	}

	public String getModuleNameOrDefault() {
		return ModuleUtil.getModuleName(this);
	}

	public String getStableId() {
		if (stableId == null) {
			Stream<String> srcs = sources.stream().map(Util::readString);
			Stream<String> ress = resources.stream().map(r -> safeRead(r.getSource()));
			Stream<String> subs = subProjects.stream().map(Project::getStableId);
			stableId = Util.getStableID(Stream.concat(Stream.concat(srcs, ress), subs));
		}
		return stableId;
	}

	private static String safeRead(Path file) {
		try {
			return Util.readString(file);
		} catch (Exception e) {
			return "";
		}
	}

	public Path getBuildDir() {
		return Settings.getCacheDir(Settings.CacheClass.jars)
			.resolve(mainSource.getFileName() + "." + getStableId());
	}

	public Path getJarFile() {
		return getBuildDir().resolve(Util.getBaseName(mainSource.getFileName().toString()) + ".jar");
	}

	public Path getJsaFile() {
		return getBuildDir().resolve(Util.getBaseName(mainSource.getFileName().toString()) + ".jsa");
	}

	public Path getCompileDir() {
		return getBuildDir().resolve("classes");
	}

	public Path getGeneratedSourcesDir() {
		return getBuildDir().resolve("generated");
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Project && mainSource.equals(((Project) o).mainSource);
	}

	@Override
	public int hashCode() {
		return Objects.hash(mainSource);
	}
}
