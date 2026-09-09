package dev.jbang.util;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleFinder;
import java.lang.module.ModuleReference;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.jbang.dependencies.ArtifactInfo;
import dev.jbang.dependencies.MavenCoordinate;
import dev.jbang.source.Project;

/**
 * Module support for <code>//MODULE</code>: naming the module and generating a
 * <code>module-info.java</code> that requires the JDK modules and the modules
 * of the project's dependencies, the way JBang does it (JBang renders a
 * template, this builds the same file directly).
 */
public class ModuleUtil {

	public static boolean isModule(Path file) {
		return getModuleName(file) != null;
	}

	/** The module name of a jar, or null when it has none. */
	public static String getModuleName(Path file) {
		try {
			Set<ModuleReference> refs = ModuleFinder.of(file).findAll();
			return refs.stream()
				.findFirst()
				.map(r -> r.descriptor().name())
				.orElse(null);
		} catch (RuntimeException e) {
			Util.verboseMsg("Could not determine the module name of " + file + ": " + e);
			return null;
		}
	}

	/**
	 * The name of the module to build. An empty <code>//MODULE</code> means
	 * "make it a module but pick the name for me", in which case the artifact
	 * id of the //GAV, or else the name of the script, is used.
	 */
	public static String getModuleName(Project project) {
		String modName = project.getModuleName().orElse(null);
		if (modName == null) {
			return null;
		}
		if (!modName.isEmpty()) {
			return modName;
		}
		String name = project.getGav()
			.map(gav -> MavenCoordinate.fromString(gav).getArtifactId())
			.orElseGet(() -> Util.getBaseName(project.getMainSource().getFileName().toString()));
		return toModuleIdentifier(name);
	}

	/** The <code>module/mainclass</code> argument for java -m, or null. */
	public static String getModuleMain(Project project) {
		if (project.getModuleName().isPresent() && project.getMainClass() != null) {
			return getModuleName(project) + "/" + project.getMainClass();
		}
		return null;
	}

	/** Turns an arbitrary name into something usable as a module identifier. */
	static String toModuleIdentifier(String name) {
		String id = name.toLowerCase().replaceAll("[^a-z0-9.]", ".").replaceAll("\\.{2,}", ".");
		id = id.replaceAll("^\\.+", "").replaceAll("\\.+$", "");
		if (id.isEmpty() || !Character.isLetter(id.charAt(0))) {
			id = "m" + (id.isEmpty() ? "" : "." + id);
		}
		return id;
	}

	/**
	 * Writes a module-info.java for the project, requiring every JDK module and
	 * every module among its direct dependencies, and opening the main source's
	 * package.
	 */
	public static Path generateModuleInfo(Project project) throws IOException {
		Set<String> depKeys = project.getDependencies()
			.stream()
			.map(MavenCoordinate::fromString)
			.map(c -> c.getGroupId() + ":" + c.getArtifactId() + ":" + c.getType())
			.collect(Collectors.toSet());
		Stream<String> depModNames = project.resolveClassPath()
			.stream()
			.filter(a -> a.getCoordinate() != null
					&& depKeys.contains(a.getCoordinate().getGroupId() + ":"
							+ a.getCoordinate().getArtifactId() + ":" + a.getCoordinate().getType()))
			.map(ArtifactInfo::getFile)
			.map(ModuleUtil::getModuleName)
			.filter(Objects::nonNull);
		List<String> moduleNames = Stream.concat(listJdkModules().stream(), depModNames)
			.distinct()
			.collect(Collectors.toList());

		StringBuilder sb = new StringBuilder();
		sb.append("module ").append(getModuleName(project)).append(" {\n");
		for (String name : moduleNames) {
			sb.append("    requires ").append(name).append(";\n");
		}
		Optional<String> pkg = getSourcePackage(project.getMainSource());
		if (!pkg.isPresent()) {
			throw new dev.jbang.ExitException(dev.jbang.ExitException.EXIT_INVALID_INPUT,
					"Module code cannot work with the default package, adding a 'package' statement is required");
		}
		sb.append("    opens ").append(pkg.get()).append(";\n");
		sb.append("}\n");

		Path infoPath = project.getGeneratedSourcesDir().resolve("module-info.java");
		Files.createDirectories(infoPath.getParent());
		Util.writeString(infoPath, sb.toString());
		return infoPath;
	}

	static List<String> listJdkModules() {
		return ModuleFinder.ofSystem()
			.findAll()
			.stream()
			.map(m -> m.descriptor().name())
			.filter(n -> n.startsWith("java."))
			.sorted()
			.collect(Collectors.toList());
	}

	/** The package declared by a Java source file, if any. */
	public static Optional<String> getSourcePackage(Path source) {
		return Util.stringLines(Util.readString(source))
			.map(String::trim)
			.filter(l -> l.startsWith("package "))
			.map(l -> l.substring(8).replace(";", "").trim())
			.filter(p -> !p.isEmpty())
			.findFirst();
	}
}
