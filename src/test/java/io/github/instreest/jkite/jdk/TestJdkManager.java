package io.github.instreest.jkite.jdk;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.github.instreest.jkite.Settings;

/**
 * The JVM index says where a JDK comes from and nothing in the project pins
 * what it says, so the one thing that keeps a bad index from choosing the
 * download is the host check.
 */
class TestJdkManager {

	private static JdkIndex.Entry entry(String url) {
		return new JdkIndex.Entry(Settings.JDK_DISTRO, "25.0.3", "tar.gz", url);
	}

	@Test
	void theDistributionsOwnHostIsAccepted() throws IOException {
		new JdkManager().requireExpectedHost(
				entry("https://" + Settings.JDK_DOWNLOAD_HOST + "/adoptium/temurin25-binaries/releases/x.tar.gz"));
	}

	@Test
	void anyOtherHostIsRefused() {
		IOException e = assertThrows(IOException.class,
				() -> new JdkManager().requireExpectedHost(entry("https://evil.example/jdk.tar.gz")));
		assertTrue(e.getMessage().contains("evil.example"), e.getMessage());
		assertTrue(e.getMessage().contains(Settings.JDK_DOWNLOAD_HOST), e.getMessage());
	}

	@Test
	void aLookalikeHostIsRefused() {
		assertThrows(IOException.class, () -> new JdkManager()
			.requireExpectedHost(entry("https://github.com.evil.example/adoptium/jdk.tar.gz")));
	}

	@Test
	void aUrlWithNoHostIsRefused() {
		assertThrows(IOException.class,
				() -> new JdkManager().requireExpectedHost(entry("file:///tmp/jdk.tar.gz")));
	}
}
