package dev.jbang;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JBangLite command line.
 *
 * <pre>
 * jbang [global options] [run] [run options] &lt;script.java&gt; [args...]
 * jbang [global options] build [run options] &lt;script.java&gt;
 * jbang [global options] info classpath [--deps-only] &lt;script.java&gt;
 * jbang [global options] info jar &lt;script.java&gt;
 * jbang [global options] jdk default [version]
 * jbang [global options] jdk install &lt;version&gt;
 * jbang [global options] jdk list
 * jbang version
 * </pre>
 *
 * Like the full JBang, <code>run</code> does not start the script itself: it
 * prints the java command line on stdout and exits with status 255, which the
 * launcher scripts (jbang, jbang.cmd, jbang.ps1) turn into an exec.
 */
public final class Main {
	private static final List<String> COMMANDS = Arrays.asList("run", "build", "info", "jdk", "version", "help");

	/** Always the real stdout, even if something redirected System.out. */
	private static final PrintStream realOut = new PrintStream(new FileOutputStream(FileDescriptor.out), true);

	private Main() {
	}

	public static void main(String... args) {
		int exitCode;
		try {
			exitCode = run(new ArrayList<>(Arrays.asList(args)));
		} catch (ExitException e) {
			if (e.getStatus() != 0 && e.getStatus() != ExitException.EXIT_EXECUTE && e.getMessage() != null) {
				Util.errorMsg(null, e);
			}
			exitCode = e.getStatus();
		} catch (IOException | IllegalArgumentException | IllegalStateException e) {
			Util.errorMsg(null, e);
			exitCode = ExitException.EXIT_GENERIC_ERROR;
		} catch (Exception e) {
			Util.errorMsg(null, e);
			exitCode = ExitException.EXIT_INTERNAL_ERROR;
		}
		if (exitCode != 0) {
			System.exit(exitCode);
		}
	}

	static int run(List<String> args) throws IOException {
		// global options come before the command / script
		while (!args.isEmpty() && args.get(0).startsWith("-")) {
			String opt = args.remove(0);
			switch (opt) {
			case "--verbose":
				Util.setVerbose(true);
				break;
			case "--quiet":
				Util.setQuiet(true);
				break;
			case "--fresh":
				Util.setFresh(true);
				break;
			case "-o":
			case "--offline":
				Util.setOffline(true);
				break;
			case "-h":
			case "--help":
				printHelp();
				return ExitException.EXIT_OK;
			case "-V":
			case "--version":
				realOut.println(Util.getJBangVersion());
				return ExitException.EXIT_OK;
			default:
				// not a global option: it belongs to the implicit "run" command
				args.add(0, opt);
				return runScript(args, true);
			}
		}
		Util.verboseMsg("jbang version " + Util.getJBangVersion());
		if (args.isEmpty()) {
			printHelp();
			return ExitException.EXIT_INVALID_INPUT;
		}
		String cmd = args.get(0);
		if (!COMMANDS.contains(cmd)) {
			// implicit run
			return runScript(args, true);
		}
		args.remove(0);
		switch (cmd) {
		case "run":
			return runScript(args, true);
		case "build":
			return runScript(args, false);
		case "info":
			return info(args);
		case "jdk":
			return jdk(args);
		case "version":
			realOut.println(Util.getJBangVersion());
			return ExitException.EXIT_OK;
		default:
			printHelp();
			return ExitException.EXIT_OK;
		}
	}

	/** Options shared by run, build and info. */
	private static final class ScriptOptions {
		String javaVersion;
		String mainClass;
		final List<String> deps = new ArrayList<>();
		final Map<String, String> properties = new LinkedHashMap<>();
		final List<String> runtimeOptions = new ArrayList<>();
		boolean depsOnly;
		String script;
		final List<String> userArgs = new ArrayList<>();

		static ScriptOptions parse(List<String> args) {
			ScriptOptions o = new ScriptOptions();
			int i = 0;
			while (i < args.size()) {
				String a = args.get(i++);
				if (o.script != null) {
					o.userArgs.add(a);
					continue;
				}
				String value = null;
				int eq = a.indexOf('=');
				String key = a;
				if (a.startsWith("--") && eq > 0) {
					key = a.substring(0, eq);
					value = a.substring(eq + 1);
				}
				switch (key) {
				case "--java":
				case "-j":
					o.javaVersion = value != null ? value : next(args, i++, key);
					break;
				case "--main":
				case "-m":
					o.mainClass = value != null ? value : next(args, i++, key);
					break;
				case "--deps":
					o.deps.addAll(Arrays.asList((value != null ? value : next(args, i++, key)).split(",")));
					break;
				case "--runtime-option":
				case "-R":
					o.runtimeOptions.add(value != null ? value : next(args, i++, key));
					break;
				case "--deps-only":
					o.depsOnly = true;
					break;
				case "--verbose":
					Util.setVerbose(true);
					break;
				case "--quiet":
					Util.setQuiet(true);
					break;
				case "--fresh":
					Util.setFresh(true);
					break;
				case "-o":
				case "--offline":
					Util.setOffline(true);
					break;
				case "--":
					if (i < args.size()) {
						o.script = args.get(i++);
					}
					break;
				default:
					if (a.startsWith("-D") && a.length() > 2) {
						String prop = a.substring(2);
						int p = prop.indexOf('=');
						o.properties.put(p > 0 ? prop.substring(0, p) : prop, p > 0 ? prop.substring(p + 1) : "");
					} else if (a.startsWith("-R")) {
						o.runtimeOptions.add(a.substring(2));
					} else if (a.startsWith("-") && !a.equals("-")) {
						throw new ExitException(ExitException.EXIT_INVALID_INPUT, "Unknown option: " + a);
					} else {
						o.script = a;
					}
				}
			}
			if (o.script == null) {
				throw new ExitException(ExitException.EXIT_INVALID_INPUT, "Missing required parameter: '<scriptOrFile>'");
			}
			return o;
		}

		private static String next(List<String> args, int i, String key) {
			if (i >= args.size()) {
				throw new ExitException(ExitException.EXIT_INVALID_INPUT, "Missing value for option " + key);
			}
			return args.get(i);
		}

		Project project() {
			Path file = Paths.get(script);
			if (!Files.isRegularFile(file)) {
				throw new ExitException(ExitException.EXIT_INVALID_INPUT,
						"Script or alias could not be found or read: '" + script + "'");
			}
			if (!file.toString().endsWith(".java")) {
				throw new ExitException(ExitException.EXIT_INVALID_INPUT,
						"Only .java source files are supported by JBangLite: '" + script + "'");
			}
			Project prj = new Project(file, properties, deps, javaVersion);
			if (mainClass != null) {
				prj.setMainClass(mainClass);
			}
			return prj;
		}
	}

	private static int runScript(List<String> args, boolean execute) throws IOException {
		ScriptOptions opts = ScriptOptions.parse(args);
		Project prj = opts.project();
		Path jar = new Builder(prj).build();
		if (!execute) {
			return ExitException.EXIT_OK;
		}
		if (prj.getMainClass() == null) {
			throw new ExitException(ExitException.EXIT_INVALID_INPUT,
					"No main class deduced, specified nor found. Use --main <main class> to specify a main class.");
		}
		Jdk jdk = prj.getJdk();
		List<String> cmd = new ArrayList<>();
		cmd.add(jdk.javaCmd());
		cmd.addAll(opts.runtimeOptions);
		prj.getUserProperties().forEach((k, v) -> cmd.add("-D" + k + "=" + v));
		String cp = jar.toAbsolutePath().toString();
		String deps = prj.getDependencyClassPath();
		if (!deps.isEmpty()) {
			cp += Settings.CP_SEPARATOR + deps;
		}
		cmd.addAll(Arrays.asList("-classpath", cp));
		cmd.add(prj.getMainClass());
		cmd.addAll(opts.userArgs);
		String cmdline = CommandBuffer.of(cmd).applyWindowsMaxCliLimit().asCommandLine();
		Util.verboseMsg("run: " + cmdline);
		realOut.println(cmdline);
		return ExitException.EXIT_EXECUTE;
	}

	private static int info(List<String> args) {
		if (args.isEmpty()) {
			throw new ExitException(ExitException.EXIT_INVALID_INPUT,
					"Missing required subcommand for 'info' (classpath, jar)");
		}
		String sub = args.remove(0);
		ScriptOptions opts = ScriptOptions.parse(args);
		Project prj = opts.project();
		switch (sub) {
		case "classpath": {
			List<String> cp = new ArrayList<>();
			if (!opts.depsOnly) {
				cp.add(prj.getJarFile().toAbsolutePath().toString());
			}
			prj.resolveClassPath().forEach(a -> cp.add(a.getFile().toAbsolutePath().toString()));
			realOut.println(String.join(Settings.CP_SEPARATOR, cp));
			return ExitException.EXIT_OK;
		}
		case "jar":
			realOut.println(prj.getJarFile().toAbsolutePath());
			return ExitException.EXIT_OK;
		default:
			throw new ExitException(ExitException.EXIT_INVALID_INPUT, "Unknown info subcommand: " + sub);
		}
	}

	private static int jdk(List<String> args) {
		if (args.isEmpty()) {
			throw new ExitException(ExitException.EXIT_INVALID_INPUT,
					"Missing required subcommand for 'jdk' (default, install, list)");
		}
		String sub = args.remove(0);
		JdkManager jdkMan = new JdkManager();
		switch (sub) {
		case "default": {
			if (args.isEmpty()) {
				Jdk def = jdkMan.getDefaultJdk();
				realOut.println(def != null ? "Default JDK: " + def : "No default JDK set");
			} else {
				jdkMan.setDefaultJdk(jdkMan.getOrInstallJdk(args.get(0)));
			}
			return ExitException.EXIT_OK;
		}
		case "install":
		case "i": {
			if (args.isEmpty()) {
				throw new ExitException(ExitException.EXIT_INVALID_INPUT, "Missing required parameter: '<version>'");
			}
			RequestedVersion version = RequestedVersion.parse(args.get(0));
			Jdk existing = jdkMan.listJBangJdks().stream()
				.filter(j -> version.matches(j.version())).findFirst().orElse(null);
			if (existing != null) {
				Util.infoMsg("JDK is already installed: " + existing);
			} else {
				Util.infoMsg("Installed JDK: " + jdkMan.install(version));
			}
			return ExitException.EXIT_OK;
		}
		case "list":
		case "l": {
			Jdk def = jdkMan.getDefaultJdk();
			realOut.println("Installed JDKs (<=default):");
			for (Jdk j : jdkMan.listInstalled()) {
				boolean isDef;
				try {
					isDef = def != null && Files.isSameFile(def.home(), j.home());
				} catch (IOException e) {
					isDef = false;
				}
				Path home = j.home();
				if ("default".equals(j.origin())) {
					try {
						home = home.toRealPath();
					} catch (IOException e) {
						// keep the link path
					}
				}
				realOut.println("   " + j.majorVersion() + " (" + j.version() + ", " + j.origin() + ") "
						+ home + (isDef ? " <" : ""));
			}
			return ExitException.EXIT_OK;
		}
		default:
			throw new ExitException(ExitException.EXIT_INVALID_INPUT, "Unknown jdk subcommand: " + sub);
		}
	}

	private static void printHelp() {
		realOut.println("jbang (JBangLite) " + Util.getJBangVersion());
		realOut.println();
		realOut.println("Builds and runs single-file Java programs that declare their needs with");
		realOut.println("//DEPS, //JAVA and //SOURCES comment directives.");
		realOut.println();
		realOut.println("Usage:");
		realOut.println("  jbang [<global options>] [run] [<options>] <script.java> [<args>...]");
		realOut.println("  jbang [<global options>] build [<options>] <script.java>");
		realOut.println("  jbang [<global options>] info classpath [--deps-only] <script.java>");
		realOut.println("  jbang [<global options>] info jar <script.java>");
		realOut.println("  jbang [<global options>] jdk default [<version>]");
		realOut.println("  jbang [<global options>] jdk install <version>");
		realOut.println("  jbang [<global options>] jdk list");
		realOut.println("  jbang version");
		realOut.println();
		realOut.println("Global options:");
		realOut.println("  --verbose        Print what is being done");
		realOut.println("  --quiet          Only print errors");
		realOut.println("  --fresh          Ignore caches and rebuild/re-resolve everything");
		realOut.println("  -o, --offline    Never access the network");
		realOut.println();
		realOut.println("Script options:");
		realOut.println("  -j, --java <v>   Use the given Java version (e.g. 17, 17+ or 25.0.3)");
		realOut.println("  -m, --main <c>   Main class to run");
		realOut.println("  --deps <gav,...> Additional dependencies");
		realOut.println("  -Dkey=value      System property for directive substitution and the script");
		realOut.println("  -R<option>       Additional JVM option when running");
	}
}
