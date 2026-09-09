package dev.jbang.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.client.WireMock;

/**
 * Functional tests verifying that JBANG_USE_NATIVE=true causes the startup
 * script to download a platform-specific bundle (e.g. jbang-linux-x64.tar)
 * instead of the generic jbang.tar.
 *
 * See https://github.com/jbangdev/jbang/pull/2534
 */
class TestScriptNativeDownload extends AbstractScriptTest {

	/**
	 * Returns the OS identifier used by the jbang bash script for the current
	 * platform.
	 */
	private static String detectOs() {
		String uname = System.getProperty("os.name", "").toLowerCase();
		if (uname.contains("linux")) {
			return "linux";
		} else if (uname.contains("mac") || uname.contains("darwin")) {
			return "mac";
		} else if (uname.contains("win")) {
			return "windows";
		}
		return "unknown";
	}

	/**
	 * Returns the arch identifier used by the jbang bash script for the current
	 * platform.
	 */
	private static String detectArch() {
		String arch = System.getProperty("os.arch", "").toLowerCase();
		if (arch.equals("aarch64") || arch.equals("arm64")) {
			return "aarch64";
		} else if (arch.equals("amd64") || arch.equals("x86_64")) {
			return "x64";
		}
		return arch;
	}

	private Map<String, String> bashEnv(boolean useNative) {
		Map<String, String> env = baseBashEnv("native-" + useNative);
		env.put("JBANG_DOWNLOAD_BASEURL", wm.baseUrl());
		env.put("JBANG_DOWNLOAD_RETRY", "0");
		env.put("JBANG_USE_NATIVE", useNative ? "true" : "false");
		env.remove("JBANG_DOWNLOAD_URL");
		env.remove("JBANG_DOWNLOAD_VERSION");
		return env;
	}

	private Map<String, String> bashEnvWithVersion(boolean useNative, String version) {
		Map<String, String> env = bashEnv(useNative);
		env.put("JBANG_DIR", tempSubDir("jbdir-native-" + useNative + "-" + version).toString());
		env.put("JBANG_CACHE_DIR", tempSubDir("cache-native-" + useNative + "-" + version).toString());
		env.put("JBANG_DOWNLOAD_VERSION", version);
		return env;
	}



	// -------------------------------------------------------------------------
	// Bash tests
	// -------------------------------------------------------------------------

	@Nested
	class Bash {

		@BeforeEach
		void checkBash() {
			requireBash();
		}

		@Test
		void latestDownloadUsesGenericBundleByDefault() throws Exception {
			byte[] tar = createJbangTar();
			wm.stubFor(WireMock.get(WireMock.urlEqualTo("/latest/download/jbang.tar"))
				.willReturn(WireMock.aResponse().withStatus(200).withBody(tar)));

			RunResult result = runProcess(bashCmd("version"), bashEnv(false));

			wm.verify(WireMock.getRequestedFor(WireMock.urlEqualTo("/latest/download/jbang.tar")));
			assertTrue(!result.stderr.contains("Error downloading JBang"),
					"download should have succeeded, stderr: " + result.stderr);
		}

		@Test
		void latestDownloadUsesPlatformBundleWhenNativeEnabled() throws Exception {
			String os = detectOs();
			String arch = detectArch();
			String expectedPath = "/latest/download/jbang-" + os + "-" + arch + ".tar";

			byte[] tar = createJbangTar();
			wm.stubFor(WireMock.get(WireMock.urlEqualTo(expectedPath))
				.willReturn(WireMock.aResponse().withStatus(200).withBody(tar)));

			RunResult result = runProcess(bashCmd("version"), bashEnv(true));

			wm.verify(WireMock.getRequestedFor(WireMock.urlEqualTo(expectedPath)));
			assertTrue(!result.stderr.contains("Error downloading JBang"),
					"download should have succeeded, stderr: " + result.stderr);
		}

		@Test
		void versionedDownloadUsesGenericBundleByDefault() throws Exception {
			byte[] tar = createJbangTar();
			wm.stubFor(WireMock.get(WireMock.urlEqualTo("/download/v0.120.0/jbang.tar"))
				.willReturn(WireMock.aResponse().withStatus(200).withBody(tar)));

			RunResult result = runProcess(bashCmd("version"), bashEnvWithVersion(false, "0.120.0"));

			wm.verify(WireMock.getRequestedFor(WireMock.urlEqualTo("/download/v0.120.0/jbang.tar")));
			assertTrue(!result.stderr.contains("Error downloading JBang"),
					"download should have succeeded, stderr: " + result.stderr);
		}

		@Test
		void versionedDownloadUsesPlatformBundleWhenNativeEnabled() throws Exception {
			String os = detectOs();
			String arch = detectArch();
			String expectedPath = "/download/v0.120.0/jbang-" + os + "-" + arch + ".tar";

			byte[] tar = createJbangTar();
			wm.stubFor(WireMock.get(WireMock.urlEqualTo(expectedPath))
				.willReturn(WireMock.aResponse().withStatus(200).withBody(tar)));

			RunResult result = runProcess(bashCmd("version"), bashEnvWithVersion(true, "0.120.0"));

			wm.verify(WireMock.getRequestedFor(WireMock.urlEqualTo(expectedPath)));
			assertTrue(!result.stderr.contains("Error downloading JBang"),
					"download should have succeeded, stderr: " + result.stderr);
		}

		@Test
		void namedTagDownloadUsesPlatformBundleWhenNativeEnabled() throws Exception {
			String os = detectOs();
			String arch = detectArch();
			String expectedPath = "/download/early-access/jbang-" + os + "-" + arch + ".tar";

			byte[] tar = createJbangTar();
			wm.stubFor(WireMock.get(WireMock.urlEqualTo(expectedPath))
				.willReturn(WireMock.aResponse().withStatus(200).withBody(tar)));

			RunResult result = runProcess(bashCmd("version"), bashEnvWithVersion(true, "early-access"));

			wm.verify(WireMock.getRequestedFor(WireMock.urlEqualTo(expectedPath)));
			assertTrue(!result.stderr.contains("Error downloading JBang"),
					"download should have succeeded, stderr: " + result.stderr);
		}

		@Test
		void downloadUrlOverrideIgnoresNativeFlag() throws Exception {
			byte[] tar = createJbangTar();
			wm.stubFor(WireMock.get(WireMock.urlEqualTo("/custom/my-jbang.tar"))
				.willReturn(WireMock.aResponse().withStatus(200).withBody(tar)));

			Map<String, String> env = bashEnv(true);
			env.put("JBANG_DOWNLOAD_URL", wm.url("/custom/my-jbang.tar"));

			RunResult result = runProcess(bashCmd("version"), env);

			// When JBANG_DOWNLOAD_URL is set explicitly, it should be used as-is
			// regardless of JBANG_USE_NATIVE
			wm.verify(WireMock.getRequestedFor(WireMock.urlEqualTo("/custom/my-jbang.tar")));
			assertTrue(!result.stderr.contains("Error downloading JBang"),
					"download should have succeeded, stderr: " + result.stderr);
		}
	}

}
