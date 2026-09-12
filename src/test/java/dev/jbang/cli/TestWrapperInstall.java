package dev.jbang.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * Functional tests for the installer (dist/install.sh) and the jar bootstrap
 * it installs: a project gets the launcher scripts and jbanglite.properties,
 * and the jar is downloaded once per machine into the cache rather than
 * committed, unless the project vendors one next to the launcher.
 */
class TestWrapperInstall extends AbstractScriptTest {

	private static final Path DIST = Paths.get("dist").toAbsolutePath();
	private static final List<String> FILES = Arrays.asList("jbanglite", "jbanglite.cmd",
			"jbanglite-bootstrap-jdk", "jbanglite-bootstrap-jdk.cmd",
			"jbanglite-bootstrap-jar", "jbanglite-bootstrap-jar.cmd", "jbanglite.properties",
			"install.sh", "install.cmd", "README.md", "LICENSE");
	private static final String JAR_PATH = "/releases/download/v9.9.9/jbanglite.jar";

	private Path project;
	private byte[] jar;

	@BeforeEach
	void serveRepository() throws Exception {
		requireBash();
		project = Files.createDirectories(tempDir.resolve("project"));
		Path fakeJar = tempDir.resolve("fake.jar");
		createFakeJar(fakeJar);
		jar = Files.readAllBytes(fakeJar);
		serveDist(jar, sha256(jar), "9.9.9");
	}

	@Test
	void installsTheScriptsAndThePropertiesButNoJar() throws Exception {
		RunResult result = install(project);
		assertEquals(0, result.exitCode, result.stderr);

		Path wrapper = project.resolve("jbanglite");
		for (String name : FILES) {
			assertTrue(Files.isRegularFile(wrapper.resolve(name)), name + " was not installed");
		}
		assertTrue(Files.isExecutable(wrapper.resolve("jbanglite")));
		assertTrue(Files.isExecutable(wrapper.resolve("jbanglite-bootstrap-jdk")));
		assertTrue(Files.isExecutable(wrapper.resolve("jbanglite-bootstrap-jar")));
		assertFalse(Files.exists(wrapper.resolve("jbanglite.jar")), "the jar must not be installed");
		assertEquals(FILES.size(), Files.list(wrapper).count(), "nothing but dist/ is installed");
	}

	@Test
	void theLauncherDownloadsTheJarAndRunsIt() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jbanglite");

		RunResult result = runLauncher(wrapper, "exit", "3");
		assertEquals(3, result.exitCode, result.stderr);
		assertTrue(result.stdout.contains("some output"), result.stdout);
		assertTrue(result.stderr.contains("some error output"), result.stderr);
		assertTrue(result.stderr.contains("Downloading JBangLite 9.9.9"), result.stderr);
		wm.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));
	}

	@Test
	void theJarIsCachedSoASecondRunDownloadsNothing() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jbanglite");
		Map<String, String> env = env();

		assertEquals(0, runProcess(bashCmd(wrapper.resolve("jbanglite"), "exit", "0"), env).exitCode);
		RunResult second = runProcess(bashCmd(wrapper.resolve("jbanglite"), "exit", "0"), env);

		assertEquals(0, second.exitCode, second.stderr);
		assertFalse(second.stderr.contains("Downloading JBangLite"), second.stderr);
		wm.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));
	}

	@Test
	void aJarNextToTheLauncherIsUsedAndNothingIsDownloaded() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jbanglite");
		createFakeJar(wrapper.resolve("jbanglite.jar"));

		RunResult result = runLauncher(wrapper, "exit", "4");

		assertEquals(4, result.exitCode, result.stderr);
		assertFalse(result.stderr.contains("Downloading JBangLite"), result.stderr);
		wm.verify(0, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));
	}

	@Test
	void aJarThatFailsItsChecksumIsRefused() throws Exception {
		wm.resetAll();
		serveDist(jar, sha256("something else".getBytes(StandardCharsets.UTF_8)), "9.9.9");
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jbanglite");

		RunResult result = runLauncher(wrapper, "exit", "0");

		assertNotEquals(0, result.exitCode);
		assertTrue(result.stderr.contains("SHA-256 mismatch"), result.stderr);
		assertTrue(result.stderr.contains("distributionSha256Sum"), result.stderr);
	}

	@Test
	void theDistributionUrlCanBePointedAtAMirror() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jbanglite");
		wm.stubFor(WireMock.get(WireMock.urlEqualTo("/mirror/jbanglite.jar"))
			.willReturn(WireMock.aResponse().withStatus(200).withBody(jar)));
		Map<String, String> env = env();
		env.put("JBANGLITE_DIST_URL", wm.baseUrl() + "/mirror/jbanglite.jar");

		RunResult result = runProcess(bashCmd(wrapper.resolve("jbanglite"), "exit", "0"), env);

		assertEquals(0, result.exitCode, result.stderr);
		wm.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/mirror/jbanglite.jar")));
		wm.verify(0, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));
	}

	@Test
	void rerunningTheInstallerUpdatesInPlace() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jbanglite");

		// a newer revision was published ...
		wm.resetAll();
		serveDist(jar, "0000000000000000000000000000000000000000000000000000000000000000", "9.9.9");

		// ... and running the installed copy updates the directory it lives in
		RunResult result = runProcess(Arrays.asList("bash", wrapper.resolve("install.sh").toString()), env());
		assertEquals(0, result.exitCode, result.stderr);
		assertTrue(result.stderr.contains(wrapper.toString()), result.stderr);
		assertTrue(new String(Files.readAllBytes(wrapper.resolve("jbanglite.properties")), StandardCharsets.UTF_8)
			.contains("0000000000000000000000000000000000000000000000000000000000000000"));
	}

	@Test
	void aFailedDownloadLeavesAnInstallationAlone() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jbanglite");
		byte[] before = Files.readAllBytes(wrapper.resolve("jbanglite.properties"));
		wm.resetAll();
		wm.stubFor(WireMock.get(WireMock.anyUrl()).willReturn(WireMock.aResponse().withStatus(404)));

		RunResult result = runProcess(Arrays.asList("bash", wrapper.resolve("install.sh").toString()), env());
		assertTrue(result.exitCode != 0, result.stderr);
		assertArrayEquals(before, Files.readAllBytes(wrapper.resolve("jbanglite.properties")));
	}

	@Test
	void versionReportsThePinAndTheInstalledJarWithoutDownloading() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jbanglite");

		RunResult before = runProcess(bashCmd(wrapper.resolve("jbanglite"), "--version"), env());
		assertEquals(0, before.exitCode, before.stderr);
		assertTrue(before.stdout.contains("jbanglite 9.9.9"), before.stdout);
		assertTrue(before.stdout.contains("jar not installed yet"), before.stdout);
		wm.verify(0, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));

		assertEquals(0, runProcess(bashCmd(wrapper.resolve("jbanglite"), "exit", "0"), env()).exitCode);

		RunResult after = runProcess(bashCmd(wrapper.resolve("jbanglite"), "--version"), env());
		assertEquals(0, after.exitCode, after.stderr);
		assertTrue(after.stdout.contains("jbanglite 9.9.9"), after.stdout);
		assertTrue(after.stdout.contains("jbanglite/9.9.9/jbanglite.jar"), after.stdout);
		wm.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));
	}

	@Test
	void versionSaysWhenAVendoredJarOverridesThePin() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jbanglite");
		createFakeJar(wrapper.resolve("jbanglite.jar"));

		RunResult result = runProcess(bashCmd(wrapper.resolve("jbanglite"), "--version"), env());

		assertEquals(0, result.exitCode, result.stderr);
		assertTrue(result.stdout.contains("jbanglite 9.9.9"), result.stdout);
		assertTrue(result.stdout.contains("vendored"), result.stdout);
		wm.verify(0, WireMock.getRequestedFor(WireMock.urlEqualTo(JAR_PATH)));
	}

	@Test
	void updateReinstallsTheDirectoryInPlace() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jbanglite");
		// a newer JBangLite was released ...
		wm.resetAll();
		serveDist(jar, sha256(jar), "9.9.10");

		// ... and --update brings this installation to it, without a JDK or a jar
		RunResult result = runProcess(bashCmd(wrapper.resolve("jbanglite"), "--update"), env());

		assertEquals(0, result.exitCode, result.stderr);
		assertTrue(new String(Files.readAllBytes(wrapper.resolve("jbanglite.properties")), StandardCharsets.UTF_8)
			.contains("distributionVersion=9.9.10"), "the pin was not updated");
	}

	@Test
	void updateWarnsThatAVendoredJarStillWins() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jbanglite");
		createFakeJar(wrapper.resolve("jbanglite.jar"));

		RunResult result = runProcess(bashCmd(wrapper.resolve("jbanglite"), "--update"), env());

		assertEquals(0, result.exitCode, result.stderr);
		assertTrue(result.stderr.contains("still runs"), result.stderr);
		assertTrue(Files.exists(wrapper.resolve("jbanglite.jar")), "the vendored jar must not be deleted");
	}

	/**
	 * dist/ is what a project installs, so the scripts copied there must be the
	 * ones in src/main/scripts (misc/update-dist.sh refreshes them). The jar is
	 * a release asset and is deliberately absent.
	 */
	@Test
	void distHoldsTheCurrentScriptsAndNoJar() throws Exception {
		for (String name : Arrays.asList("jbanglite", "jbanglite.cmd", "jbanglite-bootstrap-jdk",
				"jbanglite-bootstrap-jdk.cmd", "jbanglite-bootstrap-jar", "jbanglite-bootstrap-jar.cmd")) {
			assertArrayEquals(Files.readAllBytes(BASH_SCRIPT.resolveSibling(name)),
					Files.readAllBytes(DIST.resolve(name)),
					"dist/" + name + " is out of date, run misc/update-dist.sh");
		}
		assertArrayEquals(Files.readAllBytes(Paths.get("LICENSE")), Files.readAllBytes(DIST.resolve("LICENSE")),
				"dist/LICENSE is out of date, run misc/update-dist.sh");
		assertTrue(Files.isRegularFile(DIST.resolve("jbanglite.properties")), "dist/jbanglite.properties is missing");
		assertFalse(Files.exists(DIST.resolve("jbanglite.jar")),
				"dist/jbanglite.jar must not be committed, it is a release asset");
	}

	/**
	 * Serves dist/ as the repository would, with a jbanglite.properties that
	 * points the bootstrap script at this server instead of at GitHub.
	 */
	private void serveDist(byte[] jar, String sha256, String version) throws Exception {
		for (String name : FILES) {
			byte[] body = name.equals("jbanglite.properties")
					? ("distributionVersion=" + version + "\n"
							+ "distributionUrl=" + wm.baseUrl() + JAR_PATH + "\n"
							+ "distributionSha256Sum=" + sha256 + "\n").getBytes(StandardCharsets.UTF_8)
					: Files.readAllBytes(DIST.resolve(name));
			wm.stubFor(WireMock.get(WireMock.urlEqualTo("/instreest/jbanglite/main/dist/" + name))
				.willReturn(WireMock.aResponse().withStatus(200).withBody(body)));
		}
		wm.stubFor(WireMock.get(WireMock.urlEqualTo(JAR_PATH))
			.willReturn(WireMock.aResponse().withStatus(200).withBody(jar)));
	}

	private static String sha256(byte[] bytes) throws Exception {
		StringBuilder hex = new StringBuilder();
		for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
			hex.append(String.format("%02x", b));
		}
		return hex.toString();
	}

	private RunResult install(Path where) throws Exception {
		return runProcess(Arrays.asList("bash", DIST.resolve("install.sh").toString(),
				where.resolve("jbanglite").toString()), env());
	}

	private RunResult runLauncher(Path wrapper, String... args) throws Exception {
		return runProcess(bashCmd(wrapper.resolve("jbanglite"), args), env());
	}

	private Map<String, String> env() {
		Map<String, String> env = baseBashEnv("wrapper");
		env.put("JBANGLITE_RAW_BASEURL", wm.baseUrl());
		env.put("JAVA_HOME", System.getProperty("java.home"));
		env.put("no_proxy", "localhost,127.0.0.1");
		env.put("NO_PROXY", "localhost,127.0.0.1");
		return env;
	}
}
