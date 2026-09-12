package dev.jbang.dependencies;

import java.io.Closeable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.ConfigurationProperties;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy;

import eu.maveniverse.maven.mima.context.Context;
import eu.maveniverse.maven.mima.context.ContextOverrides;
import dev.jbang.ExitException;
import dev.jbang.Settings;
import dev.jbang.util.Util;

/**
 * Resolves Maven coordinates (including their transitive dependencies) to
 * local files using Maven Resolver through MIMA. Only Maven Central is used
 * as remote repository (plus mirrors/proxies from ~/.m2/settings.xml); the
 * local repository is the standard ~/.m2/repository unless JBANGLITE_MAVEN_REPO is set.
 */
public final class DependencyResolver {
	private final Set<MavenRepo> repositories = new LinkedHashSet<>();
	private final Set<String> dependencies = new LinkedHashSet<>();
	private final Set<String> classPaths = new LinkedHashSet<>();

	public DependencyResolver addRepositories(List<MavenRepo> repos) {
		repositories.addAll(repos);
		return this;
	}

	public DependencyResolver addDependencies(List<String> deps) {
		dependencies.addAll(deps);
		return this;
	}

	public DependencyResolver addClassPath(String classPath) {
		classPaths.add(classPath);
		return this;
	}

	/**
	 * Resolves the collected dependencies and appends any explicit class path
	 * entries (such as the jars of sub-projects) to the result.
	 */
	public List<ArtifactInfo> resolve() {
		List<ArtifactInfo> artifacts = new ArrayList<>(
				resolve(new ArrayList<>(dependencies), new ArrayList<>(repositories)));
		for (String cp : classPaths) {
			// NB: File is more lenient about odd paths than Path
			artifacts.add(new ArtifactInfo(null, new java.io.File(cp).toPath()));
		}
		return artifacts;
	}

	/**
	 * Resolves the given coordinates and returns the artifacts on the class path
	 * in dependency order. Results are cached on disk (keyed by the list of
	 * coordinates) and reused as long as the files are unchanged.
	 */
	public static List<ArtifactInfo> resolve(List<String> deps, List<MavenRepo> repos) {
		if (deps.isEmpty()) {
			return Collections.emptyList();
		}
		List<String> depIds = new ArrayList<>(new LinkedHashSet<>(deps));
		Util.verboseMsg("Resolving artifact(s): " + String.join(", ", depIds));
		if (!repos.isEmpty()) {
			Util.verboseMsg("Repositories: "
					+ repos.stream().map(MavenRepo::toString).collect(Collectors.joining(", ")));
		}
		String key = repos.stream().map(MavenRepo::toString).collect(Collectors.joining(","))
				+ "|" + String.join(Settings.CP_SEPARATOR, depIds);
		if (!Util.isFresh()) {
			List<ArtifactInfo> cached = DependencyCache.find(key);
			if (cached != null) {
				Util.verboseMsg("Resolved artifact(s) from cache: " + cached);
				return cached;
			}
		}
		Util.infoMsg("Resolving dependencies...");
		try (Session resolver = new Session(Util.isOffline(), Util.isFresh(), repos)) {
			List<ArtifactInfo> artifacts = resolver.doResolve(depIds);
			Util.infoMsg("Dependencies resolved");
			DependencyCache.store(key, artifacts);
			Util.verboseMsg("Resolved artifact(s): " + artifacts);
			return artifacts;
		}
	}

	/**
	 * Resolves a single artifact (without its dependencies) and returns its
	 * local file. The coordinate may use a version range such as
	 * <code>[0,)</code> to get the newest available version. Because this goes
	 * through Maven Resolver, mirrors, proxies and credentials configured in
	 * <code>~/.m2/settings.xml</code> apply, and the result is cached in the
	 * local repository.
	 */
	public static Path resolveArtifact(String coord) {
		try (Session resolver = new Session(Util.isOffline(), Util.isFresh(), Collections.emptyList())) {
			return resolver.doResolveArtifact(coord);
		}
	}

	/** The local Maven repository in use (e.g. ~/.m2/repository). */
	public static Path getLocalMavenRepo() {
		try (Session r = new Session(true, false, Collections.emptyList())) {
			return r.context.repositorySystemSession().getLocalRepository().getBasedir().toPath();
		}
	}

	/** A Maven Resolver session, configured the way JBangLite needs it. */
	private static final class Session implements Closeable {
	private final Context context;

	private Session(boolean offline, boolean updateCache, List<MavenRepo> repositories) {
		Map<String, String> userProperties = new HashMap<>();
		// avoid being blocked by servers that reject the default "Java" user agent
		userProperties.put("aether.connector.userAgent", "JBangLite/" + Util.getVersion());

		ContextOverrides.Builder overrides = ContextOverrides.create()
			.userProperties(userProperties)
			.offline(offline)
			.withUserSettings(true)
			.withLocalRepositoryOverride(Settings.getLocalMavenRepoOverride())
			.repositories(toRemoteRepositories(repositories))
			.addRepositoriesOp(ContextOverrides.AddRepositoriesOp.REPLACE)
			.snapshotUpdatePolicy(updateCache ? ContextOverrides.SnapshotUpdatePolicy.ALWAYS : null);
		if (!Util.isQuiet()) {
			overrides.repositoryListener(new ProgressListener());
		}
		this.context = new JBangLiteRuntime().create(overrides.build());
	}

	@Override
	public void close() {
		context.close();
	}

	/**
	 * Maven Central plus whatever //REPOS (or --repos) asked for. Mirrors,
	 * proxies and credentials still come from ~/.m2/settings.xml.
	 */
	private static List<RemoteRepository> toRemoteRepositories(List<MavenRepo> repositories) {
		if (repositories.isEmpty()) {
			return Collections.singletonList(ContextOverrides.CENTRAL);
		}
		return repositories.stream()
			.map(r -> new RemoteRepository.Builder(r.getId(), "default", r.getUrl()).build())
			.collect(Collectors.toList());
	}



	private Path doResolveArtifact(String coord) {
		Artifact artifact = toArtifact(coord);
		String version = artifact.getVersion();
		if (version.startsWith("[") || version.startsWith("(")) {
			try {
				VersionRangeResult range = context.repositorySystem()
					.resolveVersionRange(context.repositorySystemSession(),
							new VersionRangeRequest()
								.setArtifact(artifact)
								.setRepositories(context.remoteRepositories()));
				if (range.getHighestVersion() == null) {
					throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
							"No version of " + coord + " is available");
				}
				artifact = artifact.setVersion(range.getHighestVersion().toString());
			} catch (VersionRangeResolutionException e) {
				throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
						"Could not resolve version range of " + coord + ": " + e.getMessage(), e);
			}
		}
		try {
			ArtifactResult result = context.repositorySystem()
				.resolveArtifact(context.repositorySystemSession(),
						new ArtifactRequest()
							.setArtifact(artifact)
							.setRepositories(context.remoteRepositories()));
			return result.getArtifact().getFile().toPath();
		} catch (ArtifactResolutionException e) {
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
					"Could not resolve " + coord + ": " + e.getMessage(), e);
		}
	}


	private List<ArtifactInfo> doResolve(List<String> depIds) {
		context.repositorySystemSession().getData().set("depIds", depIds);
		// Maven is by default "forgiving" for dependency POM loading: here we want to
		// ensure that all enlisted deps exists for sure
		DefaultRepositorySystemSession strictSession = new DefaultRepositorySystemSession(
				context.repositorySystemSession());
		strictSession.setArtifactDescriptorPolicy(new SimpleArtifactDescriptorPolicy(false, false));
		try {
			Map<String, List<Dependency>> scopeDeps = depIds.stream()
				.map(coord -> toDependency(toArtifact(coord)))
				.collect(Collectors.groupingBy(Dependency::getScope));

			List<Dependency> deps = scopeDeps.getOrDefault(JavaScopes.COMPILE, Collections.emptyList());
			List<Dependency> managedDeps = deps.stream()
				.flatMap(d -> getManagedDependencies(strictSession, d).stream())
				.collect(Collectors.toList());

			if (scopeDeps.containsKey("import")) {
				// @pom coordinates are BOMs: their managed dependencies are applied
				// to the ordinary dependencies (which may then omit a version)
				List<Dependency> boms = scopeDeps.get("import");
				List<Dependency> mdeps = boms.stream()
					.flatMap(d -> getManagedDependencies(strictSession, d).stream())
					.collect(Collectors.toList());
				deps = deps.stream().map(d -> applyManagedDependencies(d, mdeps)).collect(Collectors.toList());
				managedDeps.addAll(0, mdeps);
			}

			CollectRequest collectRequest = new CollectRequest()
				.setManagedDependencies(managedDeps)
				.setDependencies(deps)
				.setRepositories(context.remoteRepositories());
			DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, null);
			List<ArtifactResult> artifacts = context.repositorySystem()
				.resolveDependencies(context.repositorySystemSession(), dependencyRequest)
				.getArtifactResults();
			return artifacts.stream()
				.map(ArtifactResult::getArtifact)
				.map(Session::toArtifactInfo)
				.collect(Collectors.toList());
		} catch (DependencyResolutionException ex) {
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
					"Could not resolve dependencies: " + ex.getMessage(), ex);
		}
	}

	private Dependency applyManagedDependencies(Dependency d, List<Dependency> managedDeps) {
		Artifact art = d.getArtifact();
		if (art.getVersion().isEmpty()) {
			Optional<Artifact> ma = managedDeps.stream()
				.map(Dependency::getArtifact)
				.filter(a -> a.getGroupId().equals(art.getGroupId())
						&& a.getArtifactId().equals(art.getArtifactId()))
				.findFirst();
			if (ma.isPresent()) {
				return new Dependency(ma.get(), d.getScope(), d.getOptional(), d.getExclusions());
			}
		}
		return d;
	}

	private List<Dependency> getManagedDependencies(RepositorySystemSession session, Dependency dependency) {
		return resolveDescriptor(session, dependency.getArtifact()).getManagedDependencies();
	}

	private ArtifactDescriptorResult resolveDescriptor(RepositorySystemSession session, Artifact artifact) {
		try {
			if (artifact.getVersion().trim().isEmpty()) {
				return new ArtifactDescriptorResult(
						new ArtifactDescriptorRequest(artifact, context.remoteRepositories(), ""));
			}
			// the version may be a range; descriptors can only be read for exact versions
			VersionRangeRequest rangeRequest = new VersionRangeRequest()
				.setArtifact(artifact)
				.setRepositories(context.remoteRepositories());
			VersionRangeResult rangeResult = context.repositorySystem().resolveVersionRange(session, rangeRequest);
			if (rangeResult.getVersions().isEmpty()) {
				throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
						"Could not resolve version range: " + artifact);
			}
			String version = rangeResult.getVersions().get(rangeResult.getVersions().size() - 1).toString();
			ArtifactDescriptorRequest descriptorRequest = new ArtifactDescriptorRequest()
				.setArtifact(artifact.setVersion(version))
				.setRepositories(context.remoteRepositories());
			return context.repositorySystem().readArtifactDescriptor(session, descriptorRequest);
		} catch (VersionRangeResolutionException | ArtifactDescriptorException ex) {
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
					"Could not read artifact descriptor for " + artifact, ex);
		}
	}

	private static Dependency toDependency(Artifact artifact) {
		return new Dependency(artifact,
				"pom".equalsIgnoreCase(artifact.getExtension()) ? "import" : JavaScopes.COMPILE);
	}

	private static Artifact toArtifact(String coord) {
		MavenCoordinate c = MavenCoordinate.fromString(coord);
		return new DefaultArtifact(c.getGroupId(), c.getArtifactId(), c.getClassifier(), c.getType(),
				c.getVersion() != null ? c.getVersion() : "");
	}

	private static ArtifactInfo toArtifactInfo(Artifact artifact) {
		MavenCoordinate coord = new MavenCoordinate(artifact.getGroupId(), artifact.getArtifactId(),
				artifact.getVersion(), artifact.getClassifier(), artifact.getExtension());
		return new ArtifactInfo(coord, artifact.getFile().toPath());
	}

	/** Prints the coordinates the user asked for while they are being resolved. */
	private final class ProgressListener extends AbstractRepositoryListener {
		@Override
		public void artifactResolving(RepositoryEvent event) {
			print(event.getArtifact());
		}

		@Override
		public void artifactDownloading(RepositoryEvent event) {
			print(event.getArtifact());
		}

		@SuppressWarnings("unchecked")
		private void print(Artifact art) {
			RepositorySystemSession session = context.repositorySystemSession();
			List<String> depIds = (List<String>) session.getData().get("depIds");
			if (depIds == null) {
				return;
			}
			Set<String> ids = (Set<String>) session.getData().computeIfAbsent("ids", () -> new HashSet<>(depIds));
			Set<String> printed = (Set<String>) session.getData().computeIfAbsent("printed", HashSet::new);
			String id = art.getGroupId() + ":" + art.getArtifactId();
			String coord = id + ":" + art.getVersion();
			if (!printed.contains(id) && (ids.contains(id) || ids.contains(coord) || Util.isVerbose())) {
				Util.infoMsg("   " + coord);
				printed.add(id);
			}
		}
	}
	}
}
