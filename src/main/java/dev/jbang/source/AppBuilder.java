package dev.jbang.source;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import dev.jbang.ExitException;
import dev.jbang.dependencies.ArtifactInfo;
import dev.jbang.dependencies.MavenCoordinate;
import dev.jbang.jdk.Jdk;
import dev.jbang.util.CommandBuffer;
import dev.jbang.util.JavaUtil;
import dev.jbang.util.MainClassFinder;
import dev.jbang.util.ModuleUtil;
import dev.jbang.util.Util;

/**
 * Compiles a {@link Project} with the JDK's <code>javac</code> and packages the
 * result into a jar, applying the directives that affect the build:
 * <code>//COMPILE_OPTIONS</code>, <code>//FILES</code>, <code>//MODULE</code>,
 * <code>//MANIFEST</code>, <code>//JAVAAGENT</code>, <code>//MAIN</code>,
 * <code>//PREVIEW</code>, <code>//GAV</code> and <code>//DESCRIPTION</code>.
 *
 * An existing jar is reused when the sources and resources are unchanged (the
 * build directory name contains a hash of them), the dependencies are still
 * present and it was built with a JDK that satisfies the requested version.
 */
public class AppBuilder {
	public static final String ATTR_BUILD_JDK = "Build-Jdk";

	private final Project project;

	public AppBuilder(Project project) {
		this.project = project;
	}

	/** Builds the project (and its //DEPS sub-projects) and returns the jar. */
	public Path build() throws IOException {
		for (Project sub : project.getSubProjects()) {
			new AppBuilder(sub).build();
		}
		Path jar = project.getJarFile();
		if (!Util.isFresh() && isUpToDate(jar)) {
			Util.verboseMsg("No build required. Reusing jar from " + jar);
			return jar;
		}
		Files.createDirectories(project.getBuildDir());
		// Compile into a directory of our own so that concurrent builds of the
		// same script cannot delete each other's classes. The jar itself is
		// written atomically, so whichever build finishes last wins.
		Path compileDir = keepClasses()
				? project.getCompileDir()
				: Files.createTempDirectory(project.getBuildDir(), "classes-");
		Util.deletePath(compileDir, true);
		Files.createDirectories(compileDir);
		try {
			compile(compileDir);
			copyResources(compileDir);
			generatePom(compileDir);
			findMain(compileDir);
			findAgentMethods(compileDir);
			createJar(compileDir, jar);
		} finally {
			if (!keepClasses()) {
				Util.deletePath(compileDir, true);
			}
		}
		return jar;
	}

	/** Keeps the compiled classes around for inspection. */
	static boolean keepClasses() {
		return "true".equals(System.getProperty("jbang.build.keepclasses"));
	}

	private boolean isUpToDate(Path jar) {
		if (!Files.isReadable(jar)) {
			Util.verboseMsg("Build required as " + jar + " not readable or not found.");
			return false;
		}
		if (!project.resolveClassPath().stream().allMatch(ArtifactInfo::isUpToDate)) {
			Util.verboseMsg("Building as previously built jar found but its dependencies are not up-to-date.");
			return false;
		}
		try (JarFile jf = new JarFile(jar.toFile())) {
			Attributes attrs = jf.getManifest() != null ? jf.getManifest().getMainAttributes() : null;
			String buildJdk = attrs != null ? attrs.getValue(ATTR_BUILD_JDK) : null;
			if (buildJdk == null) {
				Util.verboseMsg("Building as previously built jar found but it has incomplete meta data.");
				return false;
			}
			int built = JavaUtil.parseJavaVersion(buildJdk);
			String requested = project.getJavaVersion();
			if (!JavaUtil.satisfiesRequestedVersion(requested, built)) {
				Util.verboseMsg("Building as the jar was built with Java " + built
						+ " which does not satisfy the requested version " + requested + ".");
				return false;
			}
			if (project.getJdk().majorVersion() < built) {
				Util.verboseMsg("Building as the jar was built with Java " + built
						+ " which is newer than the JDK available now.");
				return false;
			}
			if (project.getMainClass() == null) {
				project.setMainClass(attrs.getValue(Attributes.Name.MAIN_CLASS));
			}
			return true;
		} catch (IOException e) {
			Util.verboseMsg("Building as previously built jar could not be read: " + e);
			return false;
		}
	}

	private void compile(Path compileDir) throws IOException {
		Jdk jdk = project.getJdk();
		List<String> cmd = new ArrayList<>();
		cmd.add(jdk.javacCmd());
		if (project.enablePreview()) {
			cmd.add("--enable-preview");
			cmd.add("-source");
			cmd.add(Integer.toString(jdk.majorVersion()));
		}
		cmd.addAll(project.getCompileOptions());
		String cp = project.getDependencyClassPath();
		if (!cp.isEmpty()) {
			cmd.addAll(Arrays.asList(project.getModuleName().isPresent() ? "-p" : "-classpath", cp));
		}
		cmd.addAll(Arrays.asList("-d", compileDir.toAbsolutePath().toString()));
		cmd.addAll(project.getSources().stream().map(Path::toString).collect(Collectors.toList()));
		if (project.getModuleName().isPresent() && !hasModuleInfo()) {
			Path infoFile = ModuleUtil.generateModuleInfo(project);
			cmd.add(infoFile.toString());
		}

		Util.infoMsg("Building " + (project.isAgent() ? "javaagent" : "jar") + " for "
				+ project.getMainSource().getFileName() + "...");
		Util.verboseMsg("Compile: " + String.join(" ", cmd));
		ProcessBuilder pb = CommandBuffer.of(cmd).applyWindowsMaxProcessLimit().asProcessBuilder().inheritIO();
		Process process = pb.start();
		try {
			process.waitFor();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR, e);
		}
		if (process.exitValue() != 0) {
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR, "Error during compile");
		}
	}

	private boolean hasModuleInfo() {
		return project.getSources().stream().anyMatch(s -> s.getFileName().toString().equals("module-info.java"));
	}

	/** Copies the //FILES entries next to the classes so they end up in the jar. */
	private void copyResources(Path compileDir) {
		project.getResources().forEach(r -> r.copy(compileDir));
	}

	/**
	 * Writes the pom.xml that JBang also puts in the jar, so that other tools
	 * can see the //GAV, //DESCRIPTION and resolved dependencies.
	 */
	private void generatePom(Path compileDir) throws IOException {
		MavenCoordinate gav = project.getGav()
			.map(g -> MavenCoordinate.fromString(g).withVersion())
			.orElseGet(() -> new MavenCoordinate(MavenCoordinate.DUMMY_GROUP,
					Util.getBaseName(project.getMainSource().getFileName().toString()),
					MavenCoordinate.DEFAULT_VERSION));
		StringBuilder sb = new StringBuilder();
		sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
		sb.append("<project xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 "
				+ "http://maven.apache.org/xsd/maven-4.0.0.xsd\"\n");
		sb.append("\t\t xmlns=\"http://maven.apache.org/POM/4.0.0\"\n");
		sb.append("\t\t xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n");
		sb.append("\t<modelVersion>4.0.0</modelVersion>\n");
		sb.append("\t<groupId>").append(xml(gav.getGroupId())).append("</groupId>\n");
		sb.append("\t<artifactId>").append(xml(gav.getArtifactId())).append("</artifactId>\n");
		sb.append("\t<version>").append(xml(gav.getVersion())).append("</version>\n");
		sb.append("\t<description>").append(xml(project.getDescription().orElse(""))).append("</description>\n");
		sb.append("\t<dependencies>\n");
		for (ArtifactInfo a : project.resolveClassPath()) {
			if (a.getCoordinate() == null) {
				continue;
			}
			sb.append("\t\t<dependency>\n");
			sb.append("\t\t\t<groupId>").append(xml(a.getCoordinate().getGroupId())).append("</groupId>\n");
			sb.append("\t\t\t<artifactId>").append(xml(a.getCoordinate().getArtifactId())).append("</artifactId>\n");
			sb.append("\t\t\t<version>").append(xml(a.getCoordinate().getVersion())).append("</version>\n");
			sb.append("\t\t\t<scope>compile</scope>\n");
			sb.append("\t\t</dependency>\n");
		}
		sb.append("\t</dependencies>\n");
		sb.append("</project>\n");

		Path pomPath = compileDir.resolve("META-INF/maven/" + gav.getGroupId().replace(".", "/") + "/pom.xml");
		Files.createDirectories(pomPath.getParent());
		Util.writeString(pomPath, sb.toString());
	}

	private static String xml(String value) {
		return value == null ? ""
				: value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private void findMain(Path compileDir) throws IOException {
		if (project.getMainClass() != null) {
			return;
		}
		List<String> mains = MainClassFinder.findMainClasses(compileDir);
		if (mains.size() > 1) {
			// prefer the class named like the script file
			String suggested = Util.getBaseName(project.getMainSource().getFileName().toString());
			List<String> preferred = mains.stream()
				.filter(m -> m.equals(suggested) || m.endsWith("." + suggested))
				.collect(Collectors.toList());
			if (!preferred.isEmpty()) {
				mains = preferred;
			}
		}
		if (!mains.isEmpty()) {
			project.setMainClass(mains.get(0));
			if (mains.size() > 1) {
				Util.warnMsg("Could not locate unique main() method. Use --main to specify explicit main method. "
						+ "Falling back to use first found: " + String.join(",", mains));
			}
		}
	}

	/** For //JAVAAGENT scripts, records the premain and agentmain classes. */
	private void findAgentMethods(Path compileDir) throws IOException {
		if (!project.isAgent()) {
			return;
		}
		List<String> premains = MainClassFinder.findAgentClasses(compileDir, "premain");
		if (!premains.isEmpty()) {
			project.setPreMainClass(premains.get(0));
		}
		List<String> agentmains = MainClassFinder.findAgentClasses(compileDir, "agentmain");
		if (!agentmains.isEmpty()) {
			project.setAgentMainClass(agentmains.get(0));
		}
	}

	private void createJar(Path compileDir, Path jar) throws IOException {
		Manifest manifest = new Manifest();
		Attributes attrs = manifest.getMainAttributes();
		attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
		project.getManifestAttributes().forEach(attrs::putValue);
		// the full version, so that a pinned request can be checked against a
		// previously built jar
		attrs.putValue(ATTR_BUILD_JDK, project.getJdk().version());
		if (project.getMainClass() != null) {
			attrs.put(Attributes.Name.MAIN_CLASS, project.getMainClass());
		}
		Util.verboseMsg("Package: " + jar);
		Files.createDirectories(jar.getParent());
		// a temporary file of our own, so that concurrent builds of the same
		// script do not write into each other's jar
		Path tmp = Files.createTempFile(jar.getParent(), jar.getFileName().toString(), ".tmp");
		try (OutputStream os = Files.newOutputStream(tmp); JarOutputStream jos = new JarOutputStream(os, manifest);
				Stream<Path> files = Files.walk(compileDir)) {
			List<Path> entries = files.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
			for (Path f : entries) {
				String name = compileDir.relativize(f).toString().replace('\\', '/');
				JarEntry entry = new JarEntry(name);
				entry.setTime(f.toFile().lastModified());
				jos.putNextEntry(entry);
				try (InputStream is = Files.newInputStream(f)) {
					byte[] buf = new byte[65536];
					int n;
					while ((n = is.read(buf)) > 0) {
						jos.write(buf, 0, n);
					}
				}
				jos.closeEntry();
			}
		}
		Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
	}
}
