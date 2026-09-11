package dev.jbang.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.client.WireMock;

/**
 * Functional tests for the installer (dist/install.sh): a project gets dist/ as
 * it is, jbanglite.jar included, and the launcher runs the jar next to it.
 */
class TestWrapperInstall extends AbstractScriptTest {

	private static final Path DIST = Paths.get("dist").toAbsolutePath();
	private static final List<String> FILES = Arrays.asList("jbanglite", "jbanglite-bootstrap-jdk", "jbanglite.cmd",
			"jbanglite-bootstrap-jdk.cmd", "jbanglite.jar", "install.sh", "install.cmd", "README.md", "LICENSE");

	private Path project;
	private byte[] jar;

	@BeforeEach
	void serveRepository() throws Exception {
		requireBash();
		project = Files.createDirectories(tempDir.resolve("project"));
		Path fakeJar = tempDir.resolve("fake.jar");
		createFakeJar(fakeJar);
		jar = Files.readAllBytes(fakeJar);
		serveDist(jar);
	}

	@Test
	void installsDistAsItIs() throws Exception {
		RunResult result = install(project);
		assertEquals(0, result.exitCode, result.stderr);

		Path wrapper = project.resolve("jbanglitew");
		for (String name : FILES) {
			assertTrue(Files.isRegularFile(wrapper.resolve(name)), name + " was not installed");
		}
		assertTrue(Files.isExecutable(wrapper.resolve("jbanglite")));
		assertTrue(Files.isExecutable(wrapper.resolve("jbanglite-bootstrap-jdk")));
		assertArrayEquals(jar, Files.readAllBytes(wrapper.resolve("jbanglite.jar")));
		assertEquals(FILES.size(), Files.list(wrapper).count(), "nothing but dist/ is installed");
	}

	@Test
	void launcherRunsTheJarNextToIt() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jbanglitew");

		RunResult result = runLauncher(wrapper, "exit", "3");
		assertEquals(3, result.exitCode, result.stderr);
		assertTrue(result.stdout.contains("some output"), result.stdout);
		assertTrue(result.stderr.contains("some error output"), result.stderr);
	}

	@Test
	void launcherRefusesToRunWithoutTheJar() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jbanglitew");
		Files.delete(wrapper.resolve("jbanglite.jar"));

		RunResult result = runLauncher(wrapper, "exit", "0");
		assertNotEquals(0, result.exitCode);
		assertTrue(result.stderr.contains("jbanglite.jar not found"), result.stderr);
		assertTrue(result.stderr.contains("install.sh"), result.stderr);
	}

	@Test
	void rerunningTheInstallerUpdatesInPlace() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jbanglitew");

		// a newer revision was published ...
		byte[] newerJar = "a newer jar\n".getBytes(StandardCharsets.UTF_8);
		wm.resetAll();
		serveDist(newerJar);

		// ... and running the installed copy updates the directory it lives in
		RunResult result = runProcess(Arrays.asList("bash", wrapper.resolve("install.sh").toString()), env());
		assertEquals(0, result.exitCode, result.stderr);
		assertTrue(result.stderr.contains(wrapper.toString()), result.stderr);
		assertArrayEquals(newerJar, Files.readAllBytes(wrapper.resolve("jbanglite.jar")));
	}

	@Test
	void aFailedDownloadLeavesAnInstallationAlone() throws Exception {
		assertEquals(0, install(project).exitCode);
		Path wrapper = project.resolve("jbanglitew");
		byte[] before = Files.readAllBytes(wrapper.resolve("jbanglite"));
		wm.resetAll();
		wm.stubFor(WireMock.get(WireMock.anyUrl()).willReturn(WireMock.aResponse().withStatus(404)));

		RunResult result = runProcess(Arrays.asList("bash", wrapper.resolve("install.sh").toString()), env());
		assertTrue(result.exitCode != 0, result.stderr);
		assertArrayEquals(before, Files.readAllBytes(wrapper.resolve("jbanglite")));
		assertArrayEquals(jar, Files.readAllBytes(wrapper.resolve("jbanglite.jar")));
	}

	/**
	 * dist/ is what a project installs, so the launchers copied there must be
	 * the ones in src/main/scripts (misc/update-dist.sh refreshes them).
	 */
	@Test
	void distHoldsTheCurrentLaunchers() throws Exception {
		for (String name : Arrays.asList("jbanglite", "jbanglite-bootstrap-jdk", "jbanglite.cmd",
				"jbanglite-bootstrap-jdk.cmd")) {
			assertArrayEquals(Files.readAllBytes(BASH_SCRIPT.resolveSibling(name)),
					Files.readAllBytes(DIST.resolve(name)),
					"dist/" + name + " is out of date, run misc/update-dist.sh");
		}
		assertArrayEquals(Files.readAllBytes(Paths.get("LICENSE")), Files.readAllBytes(DIST.resolve("LICENSE")),
				"dist/LICENSE is out of date, run misc/update-dist.sh");
		assertTrue(Files.isRegularFile(DIST.resolve("jbanglite.jar")), "dist/jbanglite.jar is missing");
	}

	/** Serves dist/ as the repository would, with the given jar in it. */
	private void serveDist(byte[] jar) throws Exception {
		for (String name : FILES) {
			byte[] body = name.equals("jbanglite.jar") ? jar : Files.readAllBytes(DIST.resolve(name));
			wm.stubFor(WireMock.get(WireMock.urlEqualTo("/instreest/jbang/main/dist/" + name))
				.willReturn(WireMock.aResponse().withStatus(200).withBody(body)));
		}
	}

	private RunResult install(Path where) throws Exception {
		return runProcess(Arrays.asList("bash", DIST.resolve("install.sh").toString(),
				where.resolve("jbanglitew").toString()), env());
	}

	private RunResult runLauncher(Path wrapper, String... args) throws Exception {
		Map<String, String> env = env();
		return runProcess(bashCmd(wrapper.resolve("jbanglite"), args), env);
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
