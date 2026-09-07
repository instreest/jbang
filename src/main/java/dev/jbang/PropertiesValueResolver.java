package dev.jbang;

import java.io.File;
import java.util.Properties;

/**
 * Replaces references of the form ${[env.]name[,name2...][:default]} with
 * values from the given properties or environment variables.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public final class PropertiesValueResolver {

	private static final int INITIAL = 0;
	private static final int GOT_DOLLAR = 1;
	private static final int GOT_OPEN_BRACE = 2;
	private static final int RESOLVED = 3;
	private static final int DEFAULT = 4;

	private PropertiesValueResolver() {
	}

	public static String replaceProperties(final String value, final Properties properties) {
		final StringBuilder builder = new StringBuilder();
		final int len = value.length();
		int state = INITIAL;
		int start = -1;
		int nameStart = -1;
		String resolvedValue = null;
		for (int i = 0; i < len; i = value.offsetByCodePoints(i, 1)) {
			final int ch = value.codePointAt(i);
			switch (state) {
			case INITIAL:
				if (ch == '$') {
					state = GOT_DOLLAR;
				} else {
					builder.appendCodePoint(ch);
				}
				continue;
			case GOT_DOLLAR:
				if (ch == '$') {
					builder.appendCodePoint(ch);
					state = INITIAL;
				} else if (ch == '{') {
					start = i + 1;
					nameStart = start;
					state = GOT_OPEN_BRACE;
				} else {
					builder.append('$').appendCodePoint(ch);
					state = INITIAL;
				}
				continue;
			case GOT_OPEN_BRACE:
				if (ch == ':' || ch == '}' || ch == ',') {
					final String name = value.substring(nameStart, i).trim();
					if ("/".equals(name)) {
						builder.append(File.separator);
						state = ch == '}' ? INITIAL : RESOLVED;
						continue;
					} else if (":".equals(name)) {
						builder.append(File.pathSeparator);
						state = ch == '}' ? INITIAL : RESOLVED;
						continue;
					}
					String val = properties.getProperty(name);
					if (val == null && name.startsWith("env.")) {
						val = System.getenv(name.substring(4));
					}
					if (val != null) {
						builder.append(val);
						resolvedValue = val;
						state = ch == '}' ? INITIAL : RESOLVED;
					} else if (ch == ',') {
						nameStart = i + 1;
					} else if (ch == ':') {
						start = i + 1;
						state = DEFAULT;
					} else {
						throw new IllegalStateException(
								"Failed to resolve expression: " + value.substring(start - 2, i + 1));
					}
				}
				continue;
			case RESOLVED:
				if (ch == '}') {
					state = INITIAL;
				}
				continue;
			case DEFAULT:
				if (ch == '}') {
					state = INITIAL;
					builder.append(value, start, i);
				}
				continue;
			default:
				throw new IllegalStateException("Unexpected char seen: " + ch);
			}
		}
		switch (state) {
		case GOT_DOLLAR:
			builder.append('$');
			break;
		case DEFAULT:
			builder.append(value.substring(start - 2));
			break;
		case GOT_OPEN_BRACE:
			if (resolvedValue == null) {
				throw new IllegalStateException("Incomplete expression: " + builder);
			}
			break;
		default:
			break;
		}
		return builder.toString();
	}
}
