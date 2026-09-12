package dev.jbang.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.Properties;

import org.junit.jupiter.api.Test;

/** The ${...} placeholders a directive may use. */
class TestPlaceholders {

	private final Properties props = new Properties();

	TestPlaceholders() {
		props.setProperty("version", "1.2.3");
		props.setProperty("group", "org.example");
		props.setProperty("empty", "");
	}

	private String replace(String text) {
		return Placeholders.replace(text, props);
	}

	@Test
	void textWithoutAPlaceholderIsReturnedAsItIs() {
		assertEquals("org.example:thing:1.0", replace("org.example:thing:1.0"));
		assertEquals("", replace(""));
		assertNull(replace(null));
	}

	@Test
	void aPropertyIsSubstituted() {
		assertEquals("org.example:thing:1.2.3", replace("${group}:thing:${version}"));
		assertEquals("", replace("${empty}"));
	}

	@Test
	void theFirstNameThatIsSetWins() {
		assertEquals("1.2.3", replace("${absent,version}"));
		assertEquals("1.2.3", replace("${version,group}"));
	}

	@Test
	void theFallbackIsUsedWhenNothingIsSet() {
		assertEquals("9.9", replace("${absent:9.9}"));
		assertEquals("", replace("${absent:}"));
		assertEquals("1.2.3", replace("${version:9.9}"));
		assertEquals("9.9", replace("${absent,alsoAbsent:9.9}"));
	}

	@Test
	void separatorsHaveTheirOwnNames() {
		assertEquals(File.separator, replace("${/}"));
		assertEquals(File.pathSeparator, replace("${:}"));
		assertEquals("a" + File.separator + "b", replace("a${/}b"));
	}

	@Test
	void anEnvironmentVariableNeedsTheEnvPrefix() {
		String name = System.getenv().keySet().stream().findFirst().orElse(null);
		if (name != null) {
			assertEquals(System.getenv(name), replace("${env." + name + "}"));
		}
		assertEquals("none", replace("${env.JBANGLITE_SURELY_NOT_SET:none}"));
	}

	@Test
	void aDoubledDollarIsALiteralOne() {
		assertEquals("$", replace("$$"));
		assertEquals("${version}", replace("$${version}"));
	}

	@Test
	void aLoneDollarIsKept() {
		assertEquals("100$", replace("100$"));
		assertEquals("a$b", replace("a$b"));
	}

	@Test
	void aPlaceholderNothingResolvesIsAnError() {
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> replace("${absent}"));
		assertTrue(e.getMessage().contains("absent"), e.getMessage());
		assertThrows(IllegalStateException.class, () -> replace("${version"));
	}
}
