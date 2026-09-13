package dev.jbang.spi;

import java.util.function.Function;

/**
 * Reads the <code>//</code>-directives of a source file.
 *
 * The one thing JBangLite takes from JBang is how these are written down, so
 * this is the interface across which JBang sits. The implementation in use is
 * {@code MirroredDirectiveParser}, on the copy of JBang's parser in
 * {@code dev.jbang.source.parser}; should JBang publish a usable library
 * artifact, a second implementation on top of it can be dropped in at
 * {@link Providers} without anything above this interface changing, and the
 * two can be held to the same tests.
 */
public interface DirectiveParser {

	/**
	 * Parses the contents of one source file.
	 *
	 * @param content          the whole file
	 * @param propertyReplacer expands <code>${property}</code> in a directive's
	 *                         value; applied by the parser as it reads them
	 */
	SourceDirectives parse(String content, Function<String, String> propertyReplacer);
}
