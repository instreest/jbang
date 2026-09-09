package dev.jbang.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.client.WireMock;

/**
 * Functional tests for the wrapper installer (dist/install.sh) and
 * for the jbang.jar download it sets up: a project only commits the launchers
 * and jbanglite.properties, and the launcher fetches the jar recorded there on
 * first use.
 */
class TestWrapperInstall extends AbstractScriptTest {

	private static final Path DIST = Paths.get("dist").toAbsolutePath();
	private static final byte[] JAR = "not really a jar\n".getBytes(StandardCharsets.UTF_8);

	private Path project;

	@BeforeEach
	void serveRepository() throws Exception {
		requireBash();
		project = Files.createDirectories(tempDir.resolve("project"));
		for (String name : Arrays.asList("jbang", "jbang.cmd", "install.sh", "install.cmd",
				"README.md", "gitignore", "LICENSE")) {
			stubFile("/instreest/jbang/main/dist/" + name, Files.readAllBytes(DIST.resolve(name)));
		}
		stubFile("/instreest/jbang/main/dist/jbang.jar", JAR);
		stubFile("/instreest/jbang/main/dist/jbang.jar.sha256",
				(sha256(JAR) + "  jbang.jar\n").getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void installsTheFilesToCommit() throws Exception {
		RunResult result = install(project);
		assertEquals(0, result.exitCode, result.stderr);

		Path wrapper = project.resolve("jbangw");
		for (String name : Arrays.asList("jbang", "jbang.cmd", "install.sh", "install.cmd",
				"README.md", ".gitignore", "LICENSE", "jbanglite.properties")) {
			assertTrue(Files.isRegularFile(wrapper.resolve(name)), name + " was not installed");
		}
		assertTrue(Files.isExecutable(wrapper.resolve("jbang")));
		assertTrue(Files.readAllLines(wrapper.resolve(".gitignore")).contains(".jbang/"));

		List<String> props = Files.readAllLines(wrapper.resolve("jbanglite.properties"));
		assertTrue(props.contains("repo=instreest/jbang"), props.toString());
		assertTrue(props.contains("ref=main"), props.toString());
		assertTrue(props.contains("jarSha256=" + sha256(JAR)), props.toString());
	}

	@Test
	void launcherDownloadsTheJarItPins() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jbangw");

		RunResult result = runWrapper(wrapper);
		assertTrue(result.stderr.contains("Downloading JBangLite"), result.stderr);
		assertArrayEquals(JAR, Files.readAllBytes(wrapper.resolve(".jbang/jbang.jar")));

		// the second run uses the cached jar
		result = runWrapper(wrapper);
		assertFalse(result.stderr.contains("Downloading JBangLite"), result.stderr);
	}

	@Test
	void launcherRefusesAJarWithTheWrongChecksum() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jbangw");
		Path props = wrapper.resolve("jbanglite.properties");
		Files.write(props, Files.readAllLines(props)
			.stream()
			.map(l -> l.startsWith("jarSha256=") ? "jarSha256=deadbeef" : l)
			.collect(java.util.stream.Collectors.toList()));

		RunResult result = runWrapper(wrapper);
		assertTrue(result.stderr.contains("SHA-256 mismatch"), result.stderr);
		assertTrue(Files.notExists(wrapper.resolve(".jbang/jbang.jar")), "the jar must not be kept");
	}

	@Test
	void rerunningTheInstallerUpdatesInPlace() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jbangw");
		runWrapper(wrapper);
		assertTrue(Files.exists(wrapper.resolve(".jbang/jbang.jar")));

		// running the installed copy updates the directory it lives in ...
		RunResult result = runProcess(Arrays.asList("bash", wrapper.resolve("install.sh").toString()), env());
		assertEquals(0, result.exitCode, result.stderr);
		assertTrue(result.stderr.contains(wrapper.toString()), result.stderr);
		// ... and drops the cached jar, so the next run fetches the pinned one
		assertTrue(Files.notExists(wrapper.resolve(".jbang")));
	}

	@Test
	void aFailedDownloadLeavesAnInstallationAlone() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jbangw");
		byte[] before = Files.readAllBytes(wrapper.resolve("jbang"));
		wm.resetAll();
		wm.stubFor(WireMock.get(WireMock.anyUrl()).willReturn(WireMock.aResponse().withStatus(404)));

		RunResult result = runProcess(Arrays.asList("bash", wrapper.resolve("install.sh").toString()), env());
		assertTrue(result.exitCode != 0, result.stderr);
		assertArrayEquals(before, Files.readAllBytes(wrapper.resolve("jbang")));
	}

	/**
	 * dist/ holds the files the wrapper installs, so the launchers copied there
	 * must be the ones in src/main/scripts (misc/update-dist.sh refreshes them).
	 */
	@Test
	void distHoldsTheCurrentLaunchers() throws Exception {
		for (String name : Arrays.asList("jbang", "jbang.cmd")) {
			assertArrayEquals(Files.readAllBytes(BASH_SCRIPT.resolveSibling(name)),
					Files.readAllBytes(DIST.resolve(name)),
					"dist/" + name + " is out of date, run misc/update-dist.sh");
		}
		assertArrayEquals(Files.readAllBytes(Paths.get("LICENSE")), Files.readAllBytes(DIST.resolve("LICENSE")),
				"dist/LICENSE is out of date, run misc/update-dist.sh");
	}

	private void stubFile(String url, byte[] body) {
		wm.stubFor(WireMock.get(WireMock.urlEqualTo(url))
			.willReturn(WireMock.aResponse().withStatus(200).withBody(body)));
	}

	private RunResult install(Path where) throws Exception {
		return runProcess(Arrays.asList("bash", DIST.resolve("install.sh").toString(),
				where.resolve("jbangw").toString()), env());
	}

	/**
	 * Runs the installed launcher. It downloads the jar and then fails to run it
	 * (it is not a real jar), which is fine: the download is what is tested.
	 */
	private RunResult runWrapper(Path wrapper) throws Exception {
		return runProcess(Arrays.asList("bash", wrapper.resolve("jbang").toString(), "version"), env());
	}

	private Map<String, String> env() {
		Map<String, String> env = baseBashEnv("wrapper");
		env.put("JBANGLITE_RAW_BASEURL", wm.baseUrl());
		env.put("JBANG_DOWNLOAD_RETRY", "0");
		env.put("JAVA_HOME", System.getProperty("java.home"));
		env.put("no_proxy", "localhost,127.0.0.1");
		env.put("NO_PROXY", "localhost,127.0.0.1");
		return env;
	}

	private static String sha256(byte[] content) throws IOException {
		try {
			StringBuilder sb = new StringBuilder();
			for (byte b : MessageDigest.getInstance("SHA-256").digest(content)) {
				sb.append(String.format("%02x", b));
			}
			return sb.toString();
		} catch (Exception e) {
			throw new IOException(e);
		}
	}
}
