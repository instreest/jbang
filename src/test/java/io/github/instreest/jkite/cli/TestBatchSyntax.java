package io.github.instreest.jkite.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * A batch mistake that only Windows can report, caught here instead.
 *
 * cmd.exe parses %~ as batch-parameter substitution everywhere, comments
 * included - a "rem" line is read and acted on like any other. A valid one
 * ends in the argument it refers to, as %~dp0 does. One that does not, which
 * is what "%~s" in a sentence about 8.3 names is, stops the script with
 *
 *   The following usage of the path operator in batch-parameter
 *   substitution is invalid: %~s only
 *
 * and everything after it in that run is lost. That is what happened: a
 * comment explaining the short-path fix took out fourteen Windows tests,
 * and the only machine that could say so was a CI runner four minutes away.
 * The cost is not the mistake, it is the round trip - so it is checked here,
 * where the answer takes no time and does not need Windows.
 */
class TestBatchSyntax {

	/**
	 * A single %~ is a batch parameter and has to end in the argument it
	 * refers to - a digit, or the $VAR:digit form. A doubled one, %%~, is a
	 * for-variable and ends in that variable's letter, which is why "%%~sI"
	 * is right and "%~s" is not. Only the single form is looked at.
	 */
	private static final Pattern VALID = Pattern.compile("%~[fdpnxsatz]*(\\d|\\$[A-Za-z_]+:\\d)");
	private static final Pattern ANY = Pattern.compile("(?<!%)%~");

	@Test
	void everyBatchParameterSubstitutionNamesWhatItSubstitutes() throws IOException {
		List<String> bad = new ArrayList<>();
		for (Path file : batchFiles()) {
			List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++) {
				for (String found : suspect(lines.get(i))) {
					bad.add(file + ":" + (i + 1) + "  " + found + "   in: " + lines.get(i).trim());
				}
			}
		}

		assertTrue(bad.isEmpty(),
				"cmd.exe reads these as batch-parameter substitution and stops the script, even "
						+ "in a comment:\n" + String.join("\n", bad));
	}

	/** Every %~ on the line that the valid pattern does not account for. */
	private static List<String> suspect(String line) {
		List<String> out = new ArrayList<>();
		Matcher all = ANY.matcher(line);
		Matcher valid = VALID.matcher(line);
		while (all.find()) {
			if (!(valid.find(all.start()) && valid.start() == all.start())) {
				out.add(line.substring(all.start(), Math.min(line.length(), all.start() + 8)));
			}
		}
		return out;
	}

	/** Both copies: dist/ is what a project actually runs. */
	private static List<Path> batchFiles() throws IOException {
		List<Path> files = new ArrayList<>();
		for (String dir : new String[] { "src/main/scripts", "dist" }) {
			try (Stream<Path> found = Files.list(Paths.get(dir))) {
				found.filter(p -> p.getFileName().toString().endsWith(".cmd")).forEach(files::add);
			}
		}
		assertTrue(files.size() >= 7, "the batch files moved; this is checking nothing: " + files);
		return files;
	}
}
