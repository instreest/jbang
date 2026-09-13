package dev.jbang.util;

import java.io.Console;
import java.util.Locale;

import dev.jbang.ExitException;
import dev.jbang.Settings;
import dev.jbang.spi.DownloadGate;

/**
 * The {@link DownloadGate} JBangLite uses: it says what is about to be
 * downloaded and, when there is a terminal to ask on, waits for an answer.
 *
 * JBANGLITE_CONFIRM_DOWNLOADS decides:
 * <dl>
 * <dt>auto (the default)</dt>
 * <dd>ask when there is a terminal, otherwise say what is happening and go
 * ahead. A build that runs unattended is never left waiting for an answer
 * nobody is there to give.</dd>
 * <dt>always</dt>
 * <dd>ask, and refuse the download when there is no terminal. For a project
 * that means to bring everything it needs with it.</dd>
 * <dt>never</dt>
 * <dd>never ask, like <code>--yes</code> and JBANGLITE_ASSUME_YES.</dd>
 * </dl>
 *
 * "A terminal" means {@link System#console()}, which is null as soon as stdin
 * or stdout is redirected. That is what we want: a script read from stdin
 * (<code>jbanglite -</code>) or a run whose output is piped has no free stdin
 * to read an answer from, and must not eat the script's input looking for one.
 *
 * The question and everything around it go to the console (never to stdout), so
 * a pipeline built on a script's output is unaffected. Answering with Enter
 * accepts: the gate is there to say what is about to happen, not to make every
 * first run fail.
 */
public final class PromptingDownloadGate implements DownloadGate {

	/** Asks a question on the terminal and returns the answer, or null on EOF. */
	public interface Prompter {
		String ask(String message, String question);
	}

	private final String mode;
	private final boolean assumeYes;
	private final Prompter prompter;

	public PromptingDownloadGate() {
		this(Settings.getConfirmDownloads(), Settings.isAssumeYes(), consolePrompter());
	}

	/**
	 * @param prompter the terminal to ask on, or null when there is none
	 */
	PromptingDownloadGate(String mode, boolean assumeYes, Prompter prompter) {
		this.mode = mode;
		this.assumeYes = assumeYes;
		this.prompter = prompter;
	}

	@Override
	public void check(Request request) {
		if (assumeYes || "never".equals(mode)) {
			return;
		}
		boolean always = "always".equals(mode);
		if (!always && !"auto".equals(mode)) {
			Util.warnMsg("Ignoring invalid " + Settings.ENV_CONFIRM_DOWNLOADS + ": " + mode);
		}
		String message = describe(request);
		if (prompter == null) {
			if (always) {
				throw new ExitException(ExitException.EXIT_GENERIC_ERROR,
						message + System.lineSeparator()
								+ "Refusing to download without confirmation, and there is no terminal to ask on ("
								+ Settings.ENV_CONFIRM_DOWNLOADS + "=always). Pass --yes or set "
								+ Settings.ENV_ASSUME_YES + "=1 to allow it.");
			}
			// nobody to ask: say what is happening and carry on
			Util.infoMsg(message);
			return;
		}
		String answer = prompter.ask(message, "Continue? [Y/n] ");
		if (answer != null && !accepted(answer)) {
			throw new ExitException(ExitException.EXIT_GENERIC_ERROR, "Download declined");
		}
	}

	private static Prompter consolePrompter() {
		Console console = System.console();
		if (console == null) {
			return null;
		}
		return (message, question) -> {
			console.printf("%s%n", message);
			return console.readLine("%s", question);
		};
	}

	private static boolean accepted(String answer) {
		String a = answer.trim().toLowerCase(Locale.ROOT);
		return a.isEmpty() || a.equals("y") || a.equals("yes");
	}

	private static String describe(Request request) {
		StringBuilder sb = new StringBuilder(request.summary());
		for (String item : request.items()) {
			sb.append(System.lineSeparator()).append("   ").append(item);
		}
		return sb.toString();
	}
}
