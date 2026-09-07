package dev.jbang;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns a list of arguments into a command line that is correctly quoted for
 * the shell that started JBang (bash, cmd or PowerShell), switching to an
 * argument file on Windows when the command line would become too long.
 */
public final class CommandBuffer {
	// 8192 character command line length limit imposed by CMD.EXE
	public static final int MAX_LENGTH_WINCLI = 8000;
	// Windows API has a limit of 32,768 characters for the lpCommandLine parameter
	public static final int MAX_LENGTH_WINPROCBUILDER = 32000;

	private static final Pattern cmdSafeChars = Pattern.compile("[a-zA-Z0-9.,_+=:;@()-\\\\]*");
	private static final Pattern pwrSafeChars = Pattern.compile("[a-zA-Z0-9_+=@\\\\-]*");
	private static final Pattern shellSafeChars = Pattern.compile("[a-zA-Z0-9._+=:@%/-]*");
	private static final Pattern cmdNeedQuotesChars = Pattern.compile("[\\Q&()[]{}^=;!'+,`~\\E]");

	private final List<String> arguments;
	private Util.Shell shell = Util.getShell();

	public static CommandBuffer of(Collection<String> arguments) {
		return new CommandBuffer(arguments);
	}

	private CommandBuffer(Collection<String> arguments) {
		this.arguments = new ArrayList<>(arguments);
	}

	public CommandBuffer shell(Util.Shell shell) {
		this.shell = shell;
		return this;
	}

	public ProcessBuilder asProcessBuilder() {
		List<String> args = arguments.stream().map(CommandBuffer::escapeProcessBuilderArgument)
			.collect(Collectors.toList());
		return new ProcessBuilder(args);
	}

	public String asCommandLine() {
		return arguments.stream().map(a -> escapeShellArgument(a, shell)).collect(Collectors.joining(" "));
	}

	public CommandBuffer usingArgsFile() throws IOException {
		if (arguments.size() < 2 || arguments.get(1).startsWith("@")) {
			return this;
		}
		final Path argsFile = Files.createTempFile("jbang", ".args");
		try (PrintWriter pw = new PrintWriter(argsFile.toFile())) {
			for (int i = 1; i < arguments.size(); ++i) {
				pw.println(escapeArgsFileArgument(arguments.get(i)));
			}
		}
		List<String> args = new ArrayList<>();
		args.add(arguments.get(0));
		args.add("@" + argsFile);
		return new CommandBuffer(args);
	}

	/** Use an args file when running a process on Windows with a long command. */
	public CommandBuffer applyWindowsMaxProcessLimit() throws IOException {
		String cmd = arguments.get(0).toLowerCase();
		if (cmd.endsWith(".bat") || cmd.endsWith(".cmd")) {
			return applyWindowsMaxCliLimit();
		}
		return applyWindowsMaxLengthLimit(MAX_LENGTH_WINPROCBUILDER);
	}

	/** Use an args file when the command line returned to the shell is too long. */
	public CommandBuffer applyWindowsMaxCliLimit() throws IOException {
		return applyWindowsMaxLengthLimit(MAX_LENGTH_WINCLI);
	}

	private CommandBuffer applyWindowsMaxLengthLimit(int maxLength) throws IOException {
		if (Util.isWindows() && asCommandLine().length() > maxLength) {
			return usingArgsFile();
		}
		return this;
	}

	public static String escapeShellArgument(String arg, Util.Shell shell) {
		switch (shell) {
		case bash:
			return escapeBashArgument(arg);
		case cmd:
			return escapeCmdArgument(arg);
		case powershell:
			return escapePowershellArgument(arg);
		default:
			return arg;
		}
	}

	private static String escapeBashArgument(String arg) {
		if (!shellSafeChars.matcher(arg).matches()) {
			arg = arg.replaceAll("(['])", "'\\\\''");
			arg = "'" + arg + "'";
		}
		return arg;
	}

	private static String escapeCmdArgument(String arg) {
		if (!cmdSafeChars.matcher(arg).matches()) {
			arg = arg.replaceAll("([()!^<>&|% ])", "^$1");
			arg = arg.replaceAll("([\"])", "\\\\^$1");
			arg = "^\"" + arg + "^\"";
		}
		return arg;
	}

	private static String escapePowershellArgument(String arg) {
		if (!pwrSafeChars.matcher(arg).matches()) {
			arg = arg.replaceAll("(['])", "''");
			arg = "'" + arg + "'";
		}
		return arg;
	}

	private static String escapeArgsFileArgument(String arg) {
		if (!shellSafeChars.matcher(arg).matches()) {
			arg = arg.replaceAll("([\"'\\\\])", "\\\\$1");
			arg = "\"" + arg + "\"";
		}
		return arg;
	}

	private static String escapeProcessBuilderArgument(String arg) {
		if (Util.isWindows() && cmdNeedQuotesChars.matcher(arg).find()) {
			arg = "\"" + arg + "\"";
		}
		return arg;
	}
}
