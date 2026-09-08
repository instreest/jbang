package dev.jbang;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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

/**
 * Compiles a {@link Project} with the JDK's <code>javac</code> and packages
 * the result into a jar. An existing jar is reused when the sources are
 * unchanged (the build directory name contains a hash of them), the
 * dependencies are still present and it was built with a JDK that satisfies
 * the requested Java version.
 */
public final class Builder {
	public static final String ATTR_BUILD_JDK = "Build-Jdk";

	private final Project project;

	public Builder(Project project) {
		this.project = project;
	}

	/** Builds the project if necessary and returns the jar file. */
	public Path build() throws IOException {
		Path jar = project.getJarFile();
		if (!Util.isFresh() && isUpToDate(jar)) {
			Util.verboseMsg("No build required. Reusing jar from " + jar);
			return jar;
		}
		// Compile into a directory of our own so that concurrent builds of the
		// same script cannot delete each other's classes. The jar itself is
		// written atomically, so whichever build finishes last wins.
		Files.createDirectories(project.getBuildDir());
		Path compileDir = keepClasses()
				? project.getCompileDir()
				: Files.createTempDirectory(project.getBuildDir(), "classes-");
		Util.deletePath(compileDir, true);
		Files.createDirectories(compileDir);
		try {
			compile(compileDir);
			findMain(compileDir);
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
			String requested = project.getJavaVersion();
			if (requested != null && !RequestedVersion.parse(requested).matches(buildJdk)) {
				Util.verboseMsg("Building as the jar was built with Java " + buildJdk
						+ " which does not satisfy the requested version " + requested + ".");
				return false;
			}
			if (project.getJdk().majorVersion() < Jdk.parseJavaVersion(buildJdk)) {
				Util.verboseMsg("Building as the jar was built with Java " + buildJdk
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
		cmd.add("-g");
		cmd.add("-parameters");
		String cp = project.getDependencyClassPath();
		if (!cp.isEmpty()) {
			cmd.addAll(Arrays.asList("-classpath", cp));
		}
		cmd.addAll(Arrays.asList("-d", compileDir.toAbsolutePath().toString()));
		cmd.addAll(project.getSources().stream().map(Path::toString).collect(Collectors.toList()));

		Util.infoMsg("Building jar for " + project.getMainSource().getFileName() + "...");
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

	private void createJar(Path compileDir, Path jar) throws IOException {
		Manifest manifest = new Manifest();
		Attributes attrs = manifest.getMainAttributes();
		attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
		// the full version, so that a pinned request like //JAVA 25.0.3 can be
		// checked against a previously built jar
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
		Files.move(tmp, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}
}
