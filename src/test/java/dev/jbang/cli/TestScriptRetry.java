package dev.jbang.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

/**
 * Functional tests for download retry support in the launcher scripts. The
 * only download a launcher makes is the bootstrap JDK, so the launcher is run
 * without any usable Java and JBANG_JVM_INDEX_BASEURL points it at a WireMock
 * server that simulates transient failures of the JVM index metadata.
 *
 * See https://github.com/jbangdev/jbang/issues/2459
 */
class TestScriptRetry extends AbstractScriptTest {

	private static final String METADATA_PATH = "/io/get-coursier/jvm/indices/index-linux-amd64/maven-metadata.xml";
	private static final byte[] METADATA = "<metadata><versioning><release>0.0.1</release></versioning></metadata>\n"
		.getBytes(StandardCharsets.UTF_8);

	/**
	 * Configures WireMock to fail {@code failCount} times with a 500 error, then
	 * return 200 with the given body.
	 */
	private void stubFlakyEndpoint(String path, int failCount, byte[] body) {
		String scenarioName = "flaky";
		for (int i = 0; i < failCount; i++) {
			String currentState = (i == 0) ? Scenario.STARTED : "attempt-" + i;
			String nextState = "attempt-" + (i + 1);
			wm.stubFor(WireMock.get(WireMock.urlEqualTo(path))
				.inScenario(scenarioName)
				.whenScenarioStateIs(currentState)
				.willReturn(WireMock.aResponse().withStatus(500).withBody("Server Error"))
				.willSetStateTo(nextState));
		}
		String finalState = failCount == 0 ? Scenario.STARTED : "attempt-" + failCount;
		wm.stubFor(WireMock.get(WireMock.urlEqualTo(path))
			.inScenario(scenarioName)
			.whenScenarioStateIs(finalState)
			.willReturn(WireMock.aResponse().withStatus(200).withBody(body)));
	}

	private Map<String, String> bashEnv(int retryCount) {
		Map<String, String> env = baseBashEnv("retry-" + retryCount);
		env.put("JBANG_JVM_INDEX_BASEURL", wm.baseUrl());
		env.put("JBANG_DOWNLOAD_RETRY", String.valueOf(retryCount));
		env.put("JBANG_DOWNLOAD_RETRY_DELAY", "0");
		// neither JAVA_HOME nor the PATH offers a usable Java, so the launcher
		// has to download one; JAVA_HOME points at a JDK that is too old so the
		// launcher's rejection of it is exercised as well
		env.put("JAVA_HOME", tooOldJdk());
		env.put("PATH", pathWithoutJava());
		env.put("no_proxy", "localhost,127.0.0.1");
		env.put("NO_PROXY", "localhost,127.0.0.1");
		return env;
	}

	/** A directory that looks like a Java 8 JDK, which the launcher must reject. */
	private String tooOldJdk() {
		try {
			Path jdk = Files.createDirectories(tempDir.resolve("oldjdk/bin")).getParent();
			Files.write(jdk.resolve("bin/java"), new byte[0]);
			Files.write(jdk.resolve("release"), "JAVA_VERSION=\"1.8.0_292\"\n".getBytes(StandardCharsets.UTF_8));
			return jdk.toString();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Nested
	class BashDownloadRetry {

		@BeforeEach
		void checkBash() {
			requireBash();
			requireLinuxAmd64();
		}

		@Test
		void downloadSucceedsAfterTransientFailures() throws Exception {
			stubFlakyEndpoint(METADATA_PATH, 3, METADATA);

			RunResult result = runProcess(bashCmd(bashLauncherWithJar(), "--version"), bashEnv(5));

			// the metadata was read after the retries; the index itself is not
			// served, which is where the launcher gives up
			assertTrue(result.stderr.contains("Retry in"), result.stderr);
			assertFalse(result.stderr.contains("Could not read the JVM index"), result.stderr);
			assertTrue(result.stderr.contains("Could not download the JVM index"), result.stderr);
		}

		@Test
		void downloadFailsWhenRetriesExhausted() throws Exception {
			stubFlakyEndpoint(METADATA_PATH, 10, METADATA);

			RunResult result = runProcess(bashCmd(bashLauncherWithJar(), "--version"), bashEnv(2));

			assertNotEquals(0, result.exitCode, "script should have failed");
			assertTrue(result.stderr.contains("Download 2/3 failed"), result.stderr);
			assertTrue(result.stderr.contains("Could not read the JVM index"), result.stderr);
		}

		@Test
		void downloadFailsWithZeroRetries() throws Exception {
			stubFlakyEndpoint(METADATA_PATH, 1, METADATA);

			RunResult result = runProcess(bashCmd(bashLauncherWithJar(), "--version"), bashEnv(0));

			assertNotEquals(0, result.exitCode, "script should have failed");
			assertFalse(result.stderr.contains("Retry in"), result.stderr);
			assertTrue(result.stderr.contains("Could not read the JVM index"), result.stderr);
		}
	}

}
