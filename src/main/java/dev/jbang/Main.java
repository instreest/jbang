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
import java.util.concurrent.TimeUnit;
import dev.jbang.source.AppBuilder;
import dev.jbang.source.CmdGenerator;
import dev.jbang.source.Project;
import dev.jbang.util.CommandBuffer;
import dev.jbang.util.Util;

/**
 * JBangLite command line.
 *
 * <pre>
 * jbanglite [options] &lt;script.java&gt; [args...]
 * </pre>
 *
 * There are no subcommands: JBangLite does one thing, which is to build the
 * script and run it, and <code>--help</code> and <code>--version</code> are
 * the only options that do something else. Everything about the script - its
 * Java version, dependencies, main class - is what its <code>//</code>
 * directives say; the options only tune the run. They are read getopt style,
 * up to the script: every option is accepted anywhere before it,
 * <code>--</code> ends them, and everything after the script belongs to the
 * script.
 *
 * The script runs as a child process with this process's stdin, stdout and
 * stderr; its exit status becomes this process's exit status. The launcher
 * scripts (jbanglite, jbanglite.cmd) only find a JDK and exec the jar; there is
 * no protocol between them and the jar.
 */
public final class Main {

	/** Always the real stdout, even if something redirected System.out. */
	private static final PrintStream realOut = new PrintStream(new FileOutputStream(FileDescriptor.out), true);

	private Main() {
	}

	public static void main(String... args) {
		int exitCode;
		try {
			exitCode = run(new ArrayList<>(Arrays.asList(args)));
		} catch (ExitException e) {
			if (e.getStatus() != 0 && e.getMessage() != null) {
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
		ScriptOptions opts = ScriptOptions.parse(args);
		Util.verboseMsg("jbanglite version " + Util.getJBangVersion());
		Project prj = opts.project();
		Path jar = new AppBuilder(prj).build();
		List<String> cmd = new CmdGenerator(prj, jar)
			.arguments(opts.userArgs)
			.runtimeOptions(opts.runtimeOptions)
			.generate();
		Util.verboseMsg("run: " + CommandBuffer.of(cmd).asCommandLine());
		return execute(cmd);
	}

	/** The options, all of them; there is no command word to split them by. */
	private static final class ScriptOptions {
		final Map<String, String> properties = new LinkedHashMap<>();
		final List<String> runtimeOptions = new ArrayList<>();
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
				case "--runtime-option":
				case "-R":
					o.runtimeOptions.add(value != null ? value : next(args, i++, key));
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
				case "-h":
				case "--help":
					printHelp();
					throw new ExitException(ExitException.EXIT_OK);
				case "-V":
				case "--version":
					realOut.println(Util.getJBangVersion());
					throw new ExitException(ExitException.EXIT_OK);
				case "--":
					// the getopt convention: what follows is never an option
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
					} else if (a.startsWith("-")) {
						throw new ExitException(ExitException.EXIT_INVALID_INPUT, "Unknown option: " + a);
					} else {
						o.script = a;
					}
				}
			}
			if (o.script == null) {
				if (args.isEmpty()) {
					// no arguments at all: the help says it all
					printHelp();
					throw new ExitException(ExitException.EXIT_INVALID_INPUT);
				}
				throw new ExitException(ExitException.EXIT_INVALID_INPUT, "Missing required parameter: '<script.java>'");
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
			if (Files.isDirectory(file)) {
				throw new ExitException(ExitException.EXIT_INVALID_INPUT,
						"Script is a directory, not a .java file: '" + script + "'");
			}
			if (!Files.isRegularFile(file)) {
				throw new ExitException(ExitException.EXIT_INVALID_INPUT,
						"Script could not be found or read: '" + script + "'");
			}
			if (!file.toString().endsWith(".java")) {
				throw new ExitException(ExitException.EXIT_INVALID_INPUT,
						"Only .java source files are supported by JBangLite: '" + script + "'");
			}
			return new Project(file, properties);
		}
	}

	/**
	 * Runs the command as a child process sharing this process's standard
	 * streams and returns its exit status. A signal that ends this process
	 * (SIGINT from the terminal, a SIGTERM) also ends the child, so a script
	 * never outlives its launcher.
	 */
	static int execute(List<String> cmd) throws IOException {
		ProcessBuilder pb = CommandBuffer.of(cmd).applyWindowsMaxProcessLimit().asProcessBuilder().inheritIO();
		Process process = pb.start();
		Thread stopChild = new Thread(() -> {
			if (!process.isAlive()) {
				return;
			}
			process.destroy();
			try {
				if (!process.waitFor(5, TimeUnit.SECONDS)) {
					process.destroyForcibly();
				}
			} catch (InterruptedException e) {
				process.destroyForcibly();
			}
		});
		Runtime.getRuntime().addShutdownHook(stopChild);
		try {
			int status = process.waitFor();
			try {
				Runtime.getRuntime().removeShutdownHook(stopChild);
			} catch (IllegalStateException e) {
				// the JVM is already shutting down (Ctrl+C reached both of us and
				// the child died first): the hook runs anyway and finds nothing
				// to do, so there is nothing to report
			}
			return status;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR, "Interrupted while waiting for the script");
		}
	}

	private static void printHelp() {
		realOut.println("jbanglite " + Util.getJBangVersion());
		realOut.println();
		realOut.println("Builds and runs a single-file Java program. What it needs - its Java version,");
		realOut.println("dependencies, sources - is declared in the program with //JAVA, //DEPS and");
		realOut.println("//SOURCES directives, and JBangLite fetches all of it.");
		realOut.println();
		realOut.println("Usage:");
		realOut.println("  jbanglite [<options>] <script.java> [<args>...]");
		realOut.println();
		realOut.println("Options may appear anywhere before the script, '--' ends them, and");
		realOut.println("everything after the script is passed to it.");
		realOut.println();
		realOut.println("Options:");
		realOut.println("  -h, --help           Print this help and exit");
		realOut.println("  -V, --version        Print the version and exit");
		realOut.println("  --update [<ref>]     Update this JBangLite installation and exit");
		realOut.println("  --verbose            Print what is being done");
		realOut.println("  --quiet              Only print errors");
		realOut.println("  --fresh              Ignore caches and rebuild/re-resolve everything");
		realOut.println("  -o, --offline        Never access the network");
		realOut.println("  -Dkey=value          System property for directive substitution and the script");
		realOut.println("  -R<option>           Additional JVM option when running");
		realOut.println();
		realOut.println("--version and --update are answered by the launcher script, which needs");
		realOut.println("neither this jar nor a JDK for them.");
	}
}
