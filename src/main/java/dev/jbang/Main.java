package dev.jbang;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * the only options that do something else. Options are read, getopt style, up
 * to the script: every option is accepted anywhere before it, <code>--</code>
 * ends them, and everything after the script belongs to the script. The script
 * is a <code>.java</code> file, or <code>-</code> for stdin; a path that is
 * not a regular file but can be read (a process substitution, a pipe) is read
 * like stdin.
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
			.assertions(opts.enableAssertions)
			.systemAssertions(opts.enableSystemAssertions)
			.classDataSharing(opts.cds)
			.generate();
		Util.verboseMsg("run: " + CommandBuffer.of(cmd).asCommandLine());
		return execute(cmd);
	}

	/** The options, all of them; there is no command word to split them by. */
	private static final class ScriptOptions {
		String javaVersion;
		String mainClass;
		String moduleName;
		final List<String> deps = new ArrayList<>();
		final List<String> repos = new ArrayList<>();
		final Map<String, String> properties = new LinkedHashMap<>();
		final List<String> runtimeOptions = new ArrayList<>();
		final List<String> compileOptions = new ArrayList<>();
		boolean enablePreview;
		boolean enableAssertions;
		boolean enableSystemAssertions;
		boolean cds;
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
				case "--repos":
					o.repos.addAll(Arrays.asList((value != null ? value : next(args, i++, key)).split(",")));
					break;
				case "--module":
					o.moduleName = value != null ? value : "";
					break;
				case "--compile-option":
				case "-C":
					o.compileOptions.add(value != null ? value : next(args, i++, key));
					break;
				case "--enable-preview":
					o.enablePreview = true;
					break;
				case "--enable-assertions":
				case "-ea":
					o.enableAssertions = true;
					break;
				case "--enable-system-assertions":
				case "-esa":
					o.enableSystemAssertions = true;
					break;
				case "--cds":
					o.cds = true;
					break;
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
					if (a.startsWith("-C") && a.length() > 2) {
						o.compileOptions.add(a.substring(2));
					} else if (a.startsWith("-D") && a.length() > 2) {
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

		Project project() throws IOException {
			Path file;
			Path baseDir = null;
			if (script.equals("-")) {
				file = StdinScript.store(System.in);
				baseDir = Util.getCwd();
			} else {
				file = Paths.get(script);
				if (!Files.isRegularFile(file)) {
					if (Files.isDirectory(file)) {
						throw new ExitException(ExitException.EXIT_INVALID_INPUT,
								"Script is a directory, not a .java file: '" + script + "'");
					}
					if (!Files.isReadable(file)) {
						throw new ExitException(ExitException.EXIT_INVALID_INPUT,
								"Script could not be found or read: '" + script + "'");
					}
					// a process substitution or a pipe: read it like stdin
					try (InputStream in = Files.newInputStream(file)) {
						file = StdinScript.store(in);
					}
					baseDir = Util.getCwd();
				} else if (!file.toString().endsWith(".java")) {
					throw new ExitException(ExitException.EXIT_INVALID_INPUT,
							"Only .java source files are supported by JBangLite: '" + script + "'");
				}
			}
			Project prj = new Project(file, baseDir, properties, deps, repos, compileOptions, runtimeOptions,
					javaVersion, mainClass, moduleName);
			if (enablePreview) {
				prj.setEnablePreview(true);
			}
			return prj;
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

	/**
	 * A script read from stdin is kept as a file in the cache, because javac
	 * wants a file and its name has to match the public class in it. The name
	 * is taken from the source, the directory from a hash of it, so the same
	 * input builds into the same place and is reused.
	 */
	static final class StdinScript {
		private static final Pattern PUBLIC_TYPE = Pattern
			.compile("(?m)^\\s*public\\s+(?:(?:final|abstract|static|sealed|non-sealed)\\s+)*"
					+ "(?:class|interface|enum|record)\\s+(\\w+)");
		private static final Pattern ANY_TYPE = Pattern
			.compile("(?m)^\\s*(?:(?:final|abstract|static|sealed|non-sealed)\\s+)*"
					+ "(?:class|interface|enum|record)\\s+(\\w+)");

		private StdinScript() {
		}

		static Path store(InputStream in) throws IOException {
			// read by hand: readAllBytes on a pipe or a process substitution
			// asks for the stream's position and fails with "Illegal seek"
			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			byte[] chunk = new byte[8192];
			int n;
			while ((n = in.read(chunk)) > 0) {
				buf.write(chunk, 0, n);
			}
			byte[] bytes = buf.toByteArray();
			String source = new String(bytes, StandardCharsets.UTF_8);
			String name = typeName(source);
			Path dir = Settings.getCacheDir(Settings.CacheClass.stdin).resolve(Util.sha256(bytes));
			Path file = dir.resolve(name + ".java");
			if (!Files.exists(file) || !Arrays.equals(Files.readAllBytes(file), bytes)) {
				Files.createDirectories(dir);
				Path tmp = Files.createTempFile(dir, name, ".tmp");
				Files.write(tmp, bytes);
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
			return file;
		}

		static String typeName(String source) {
			Matcher m = PUBLIC_TYPE.matcher(source);
			if (m.find()) {
				return m.group(1);
			}
			m = ANY_TYPE.matcher(source);
			return m.find() ? m.group(1) : "Script";
		}
	}

	private static void printHelp() {
		realOut.println("jbanglite " + Util.getJBangVersion());
		realOut.println();
		realOut.println("Builds and runs a single-file Java program that declares its needs with");
		realOut.println("//DEPS, //JAVA and //SOURCES comment directives.");
		realOut.println();
		realOut.println("Usage:");
		realOut.println("  jbanglite [<options>] <script.java> [<args>...]");
		realOut.println();
		realOut.println("Options may appear anywhere before the script, '--' ends them, and");
		realOut.println("everything after the script is passed to it. The script is a .java");
		realOut.println("file, or '-' to read it from stdin.");
		realOut.println();
		realOut.println("Options:");
		realOut.println("  -h, --help           Print this help and exit");
		realOut.println("  -V, --version        Print the version and exit");
		realOut.println("  --verbose            Print what is being done");
		realOut.println("  --quiet              Only print errors");
		realOut.println("  --fresh              Ignore caches and rebuild/re-resolve everything");
		realOut.println("  -o, --offline        Never access the network");
		realOut.println("  -j, --java <v>       Use the given Java version (e.g. 17 or 17+)");
		realOut.println("  -m, --main <c>       Main class to run");
		realOut.println("  --module[=<name>]    Run as a module, optionally with the given name");
		realOut.println("  --deps <gav,...>     Additional dependencies");
		realOut.println("  --repos <repo,...>   Additional Maven repositories");
		realOut.println("  -C<option>           Additional compiler option");
		realOut.println("  -R<option>           Additional JVM option when running");
		realOut.println("  -Dkey=value          System property for directive substitution and the script");
		realOut.println("  --enable-preview     Activate Java preview features");
		realOut.println("  -ea, -esa            Enable (system) assertions");
		realOut.println("  --cds                Use class data sharing");
	}
}
