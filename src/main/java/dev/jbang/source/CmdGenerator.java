package dev.jbang.source;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import dev.jbang.ExitException;
import dev.jbang.Settings;
import dev.jbang.jdk.Jdk;
import dev.jbang.util.ModuleUtil;
import dev.jbang.util.Util;

/**
 * Builds the <code>java</code> command line that runs a built project, applying
 * the directives that affect running: <code>//RUNTIME_OPTIONS</code>,
 * <code>//JAVA_OPTIONS</code>, <code>//MODULE</code>, <code>//CDS</code>,
 * <code>//PREVIEW</code> and the <code>Add-Opens</code>,
 * <code>Add-Exports</code> and <code>Enable-Native-Access</code> entries of
 * <code>//MANIFEST</code>.
 */
public class CmdGenerator {
	private final Project project;
	private final Path jar;
	private List<String> arguments = Collections.emptyList();
	private List<String> runtimeOptions = Collections.emptyList();
	private boolean assertions;
	private boolean systemAssertions;
	private boolean classDataSharing;

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

	public CmdGenerator assertions(boolean assertions) {
		this.assertions = assertions;
		return this;
	}

	public CmdGenerator systemAssertions(boolean systemAssertions) {
		this.systemAssertions = systemAssertions;
		return this;
	}

	public CmdGenerator classDataSharing(boolean classDataSharing) {
		this.classDataSharing = classDataSharing;
		return this;
	}

	/** The <code>java</code> command line, one argument per element. */
	public List<String> generate() throws IOException {
		Jdk jdk = project.getJdk();
		boolean runAsModule = project.getModuleName().isPresent();
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
		if (assertions) {
			optionalArgs.add("-ea");
		}
		if (systemAssertions) {
			optionalArgs.add("-esa");
		}
		if (project.enablePreview()) {
			optionalArgs.add("--enable-preview");
		}

		String classpath = project.getDependencyClassPath();
		String full = jar.toAbsolutePath().toString();
		if (!classpath.isEmpty()) {
			full = full + Settings.CP_SEPARATOR + classpath;
		}
		optionalArgs.addAll(Arrays.asList(runAsModule ? "-p" : "-classpath", full));

		if (classDataSharing || project.enableCDS()) {
			if (jdk.majorVersion() >= 13) {
				Path cdsJsa = project.getJsaFile().toAbsolutePath();
				if (Files.exists(cdsJsa)) {
					Util.verboseMsg("CDS: Using shared archive classes from " + cdsJsa);
					optionalArgs.add("-XX:SharedArchiveFile=" + cdsJsa);
				} else {
					Util.verboseMsg("CDS: Archiving Classes At Exit at " + cdsJsa);
					optionalArgs.add("-XX:ArchiveClassesAtExit=" + cdsJsa);
				}
			} else {
				Util.warnMsg("ClassDataSharing can only be used on Java versions 13 and later, you are on "
						+ jdk.majorVersion() + ". Rerun with `--java 13+` to enforce the minimum version");
			}
		}
		fullArgs.addAll(optionalArgs);

		String main = project.getMainClass();
		if (main == null) {
			throw new ExitException(ExitException.EXIT_INVALID_INPUT,
					"No main class deduced, specified nor found. Use --main <main class> to specify a main class.");
		}
		if (runAsModule) {
			fullArgs.add("-m");
			fullArgs.add(ModuleUtil.getModuleMain(project));
		} else {
			fullArgs.add(main);
		}
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
