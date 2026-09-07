package dev.jbang;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Parses the <code>//</code>-directives supported by JBangLite from the
 * contents of a Java source file:
 * <ul>
 * <li><code>//DEPS group:artifact:version[:classifier][@type] ...</code></li>
 * <li><code>//JAVA version[+]</code></li>
 * <li><code>//SOURCES file-or-glob ...</code></li>
 * </ul>
 * Values may reference properties using <code>${name}</code>.
 */
public final class Directives {
	public static final String DEPS = "DEPS";
	public static final String JAVA = "JAVA";
	public static final String SOURCES = "SOURCES";

	private static final Pattern DIRECTIVE = Pattern
		.compile("(?<key>[A-Z_]+)(?:\\s+(?<value>.*?))?(?:\\s\\/\\/\\s.*)?");

	// Values are separated by spaces, semicolons, commas or tabs (quotes allowed)
	private static final Pattern Q2TL_SSCT = Pattern.compile("[^\\s;,\"']+|\"([^\"]*)\"|'([^']*)'");

	private static final Pattern REQUESTED_VERSION = Pattern.compile("\\d+[+]?");

	private final List<Directive> directives;

	public static final class Directive {
		public final String name;
		public final String value;

		Directive(String name, String value) {
			this.name = name;
			this.value = value;
		}

		@Override
		public String toString() {
			return value != null ? "//" + name + " " + value : "//" + name;
		}
	}

	public Directives(String contents, Function<String, String> propertiesReplacer) {
		directives = Util.stringLines(contents)
			.filter(l -> l.startsWith("//"))
			.map(l -> toDirective(l.substring(2), propertiesReplacer))
			.filter(Objects::nonNull)
			.collect(Collectors.toList());
	}

	private static Directive toDirective(String line, Function<String, String> propertiesReplacer) {
		Matcher matcher = DIRECTIVE.matcher(line);
		if (matcher.matches()) {
			String value = matcher.group("value");
			if (value != null) {
				if (propertiesReplacer != null) {
					value = propertiesReplacer.apply(value);
				}
				value = value.trim();
			}
			return new Directive(matcher.group("key"), value);
		}
		return null;
	}

	public Stream<Directive> getAll() {
		return directives.stream();
	}

	private Stream<String> values(String name) {
		Stream<String> vals = directives.stream()
			.filter(d -> d.name.equals(name))
			.map(d -> d.value)
			.filter(Objects::nonNull);
		String envOptions = System.getenv("JBANG_APP_" + name);
		if (envOptions != null) {
			vals = Stream.concat(vals, Stream.of(envOptions));
		}
		return vals;
	}

	/** All entries of //DEPS lines that look like Maven coordinates. */
	public List<String> dependencies() {
		return values(DEPS)
			.flatMap(v -> quotedStringToList(v).stream())
			.filter(MavenCoordinate::looksLikeACoordinate)
			.collect(Collectors.toList());
	}

	/** All entries of //SOURCES lines (files or glob patterns). */
	public List<String> sources() {
		return values(SOURCES)
			.flatMap(v -> quotedStringToList(v).stream())
			.collect(Collectors.toCollection(ArrayList::new));
	}

	/**
	 * The requested Java version (highest of all //JAVA lines) as a string like
	 * "17" or "17+", or null if not specified.
	 */
	public String javaVersion() {
		Optional<String> version = values(JAVA)
			.map(String::trim)
			.filter(v -> {
				if (!isRequestedVersion(v)) {
					throw new ExitException(ExitException.EXIT_INVALID_INPUT,
							"Invalid //JAVA version '" + v + "', should be a number optionally followed by a plus sign");
				}
				return true;
			})
			.max(new RequestedVersionComparator());
		return version.orElse(null);
	}

	public static boolean isRequestedVersion(String rv) {
		return rv != null && REQUESTED_VERSION.matcher(rv).matches();
	}

	public static boolean isOpenVersion(String rv) {
		return rv.endsWith("+");
	}

	public static int minRequestedVersion(String rv) {
		return Integer.parseInt(isOpenVersion(rv) ? rv.substring(0, rv.length() - 1) : rv);
	}

	public static boolean satisfiesRequestedVersion(String rv, int actual) {
		if (rv == null) {
			return true;
		}
		int req = minRequestedVersion(rv);
		return isOpenVersion(rv) ? actual >= req : actual == req;
	}

	static List<String> quotedStringToList(String text) {
		if (text == null) {
			return Collections.emptyList();
		}
		List<String> matchList = new ArrayList<>();
		Matcher m = Q2TL_SSCT.matcher(text);
		while (m.find()) {
			if (m.group(1) != null) {
				matchList.add(m.group(1));
			} else if (m.group(2) != null) {
				matchList.add(m.group(2));
			} else {
				matchList.add(m.group());
			}
		}
		return matchList;
	}

	/** Orders requested versions: higher number first, exact before open. */
	public static final class RequestedVersionComparator implements java.util.Comparator<String> {
		@Override
		public int compare(String v1, String v2) {
			int n1 = minRequestedVersion(v1);
			int n2 = minRequestedVersion(v2);
			if (n1 != n2) {
				return Integer.compare(n1, n2);
			}
			boolean o1 = isOpenVersion(v1);
			boolean o2 = isOpenVersion(v2);
			if (o1 == o2) {
				return 0;
			}
			// exact versions are considered "higher" than open ones
			return o1 ? -1 : 1;
		}
	}
}
