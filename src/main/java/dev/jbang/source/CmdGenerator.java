package dev.jbang.source;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import dev.jbang.ExitException;
import dev.jbang.Settings;
import dev.jbang.jdk.Jdk;

/**
 * Builds the <code>java</code> command line that runs a built project, applying
 * the directives that affect running: <code>//RUNTIME_OPTIONS</code>,
 * <code>//JAVA_OPTIONS</code>, <code>//PREVIEW</code> and the
 * <code>Add-Opens</code>, <code>Add-Exports</code> and
 * <code>Enable-Native-Access</code> entries of <code>//MANIFEST</code>.
 */
public class CmdGenerator {
	private final Project project;
	private final Path jar;
	private List<String> arguments = Collections.emptyList();
	private List<String> runtimeOptions = Collections.emptyList();

	public CmdGenerator(Project project, Path jar) {
		this.project = project;
		this.jar = jar;
	}

	public CmdGenerator arguments(List<String> arguments) {
		this.arguments = arguments != null ? arguments : Collections.emptyList();
		return this;
	}

	public CmdGenerator runtimeOptions(List<String> runtimeOptions) {
		this.runtimeOptions = runtimeOptions != null ? runtimeOptions : Collections.emptyList();
		return this;
	}

	/** The <code>java</code> command line, one argument per element. */
	public List<String> generate() throws IOException {
		Jdk jdk = project.getJdk();
		List<String> fullArgs = new ArrayList<>();
		fullArgs.add(jdk.javaCmd());
		fullArgs.addAll(project.getRuntimeOptions());
		fullArgs.addAll(runtimeOptions);

		List<String> optionalArgs = new ArrayList<>();
		if (jdk.majorVersion() >= 9) {
			addAllUnnamed(optionalArgs, project.getManifestAttributes().get(Project.ATTR_ADD_OPENS), "--add-opens=");
			addAllUnnamed(optionalArgs, project.getManifestAttributes().get(Project.ATTR_ADD_EXPORTS),
					"--add-exports=");
		}
		if (jdk.majorVersion() >= 22) {
			addAll(optionalArgs, project.getManifestAttributes().get(Project.ATTR_ENABLE_NATIVE_ACCESS),
					"--enable-native-access=");
		}
		project.getProperties().forEach((k, v) -> optionalArgs.add("-D" + k + "=" + v));
		if (project.enablePreview()) {
			optionalArgs.add("--enable-preview");
		}

		String classpath = project.getDependencyClassPath();
		String full = jar.toAbsolutePath().toString();
		if (!classpath.isEmpty()) {
			full = full + Settings.CP_SEPARATOR + classpath;
		}
		optionalArgs.addAll(Arrays.asList("-classpath", full));
		fullArgs.addAll(optionalArgs);

		String main = project.getMainClass();
		if (main == null) {
			throw new ExitException(ExitException.EXIT_INVALID_INPUT,
					"No main class deduced nor found. Use //MAIN <class> to name the main class.");
		}
		fullArgs.add(main);
		fullArgs.addAll(arguments);

		return fullArgs;
	}

	private static void addAllUnnamed(List<String> result, String manifestValue, String prefix) {
		if (manifestValue == null) {
			return;
		}
		Arrays.stream(manifestValue.trim().split("\\s+"))
			.filter(v -> !v.isEmpty())
			.forEach(v -> result.add(prefix + v + "=ALL-UNNAMED"));
	}

	private static void addAll(List<String> result, String manifestValue, String prefix) {
		if (manifestValue == null) {
			return;
		}
		Arrays.stream(manifestValue.trim().split("\\s+"))
			.filter(v -> !v.isEmpty())
			.forEach(v -> result.add(prefix + v));
	}
}
