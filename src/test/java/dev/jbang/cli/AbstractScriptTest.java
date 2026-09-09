package dev.jbang.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Shared infrastructure for functional tests of JBang startup scripts. Provides
 * WireMock lifecycle, process execution helpers, archive creation utilities,
 * and a base environment map for the tests.
 */
abstract class AbstractScriptTest {

	protected static final Path BASH_SCRIPT = Paths.get("src/main/scripts/jbanglite").toAbsolutePath();
	protected static final Path CMD_SCRIPT = Paths.get("src/main/scripts/jbanglite.cmd").toAbsolutePath();

	protected WireMockServer wm;

	@TempDir
	protected Path tempDir;

	@BeforeEach
	void startWireMock() {
		wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		wm.start();
	}

	@AfterEach
	void stopWireMock() {
		if (wm != null) {
			wm.stop();
		}
	}

	/**
	 * Creates a unique subdirectory path under {@link #tempDir}. The directory is
	 * not created on disk — startup scripts will create it as needed.
	 */
	protected Path tempSubDir(String name) {
		return tempDir.resolve(name);
	}

	// -------------------------------------------------------------------------
	// Command availability checks
	// -------------------------------------------------------------------------

	protected static boolean isCommandAvailable(String command) {
		try {
			Process p = new ProcessBuilder(command, "--version")
				.redirectErrorStream(true)
				.start();
			p.getInputStream().transferTo(new ByteArrayOutputStream());
			return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
		} catch (Exception e) {
			return false;
		}
	}

	protected void requireBash() {
		assumeTrue(isCommandAvailable("bash"), "bash is not available");
	}

	/** For tests that need the launcher to look up a JVM index of a known platform. */
	protected void requireLinuxAmd64() {
		assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux")
				&& Arrays.asList("amd64", "x86_64").contains(System.getProperty("os.arch")),
				"not linux-amd64");
	}


	// -------------------------------------------------------------------------
	// Process execution
	// -------------------------------------------------------------------------

	protected static RunResult runProcess(List<String> cmd, Map<String, String> env) throws Exception {
		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.environment().putAll(env);
		pb.redirectErrorStream(false);
		Process process = pb.start();

		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		ByteArrayOutputStream stderr = new ByteArrayOutputStream();
		Thread t1 = new Thread(() -> {
			try {
				process.getInputStream().transferTo(stdout);
			} catch (Exception e) {
				/* ignore */ }
		});
		Thread t2 = new Thread(() -> {
			try {
				process.getErrorStream().transferTo(stderr);
			} catch (Exception e) {
				/* ignore */ }
		});
		t1.start();
		t2.start();

		boolean finished = process.waitFor(120, TimeUnit.SECONDS);
		if (!finished) {
			process.destroyForcibly();
		}
		t1.join(5000);
		t2.join(5000);
		assertTrue(finished, "script timed out");
		return new RunResult(process.exitValue(),
				stdout.toString(StandardCharsets.UTF_8),
				stderr.toString(StandardCharsets.UTF_8));
	}

	static class RunResult {
		final int exitCode;
		final String stdout;
		final String stderr;

		RunResult(int exitCode, String stdout, String stderr) {
			this.exitCode = exitCode;
			this.stdout = stdout;
			this.stderr = stderr;
		}
	}

	// -------------------------------------------------------------------------
	// A launcher with a stand-in jar next to it
	// -------------------------------------------------------------------------

	/**
	 * Copies the bash launcher into a directory of its own with a fake
	 * jbanglite.jar next to it, as installed into a project, and returns the
	 * launcher.
	 */
	protected Path bashLauncherWithJar() throws IOException {
		Path dir = Files.createDirectories(tempDir.resolve("bin"));
		Path launcher = dir.resolve("jbanglite");
		Files.copy(BASH_SCRIPT, launcher, StandardCopyOption.REPLACE_EXISTING);
		createFakeJar(dir.resolve("jbanglite.jar"));
		return launcher;
	}

	/** Writes a jar whose main class is {@link FakeJBang}. */
	protected static void createFakeJar(Path jar) throws IOException {
		Manifest manifest = new Manifest();
		manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
		manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, FakeJBang.class.getName());
		String classResource = FakeJBang.class.getName().replace('.', '/') + ".class";
		try (InputStream input = FakeJBang.class.getClassLoader().getResourceAsStream(classResource);
				JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
			if (input == null) {
				throw new IOException("Could not find test class: " + classResource);
			}
			output.putNextEntry(new JarEntry(classResource));
			input.transferTo(output);
			output.closeEntry();
		}
	}

	/**
	 * Stand-in for jbanglite.jar: records the environment the launcher set up
	 * in the file JBANG_TEST_ENV_FILE names and then either exits with the given
	 * code ("exit N") or asks the launcher to run a command ("exec CMD", exit
	 * code 255).
	 */
	public static class FakeJBang {
		public static void main(String[] args) throws IOException {
			Path envFile = Paths.get(System.getenv("JBANG_TEST_ENV_FILE"));
			Files.write(envFile, Arrays.asList(System.getenv("JBANG_RUNTIME_SHELL"),
					System.getenv("JBANG_LAUNCH_CMD")), StandardCharsets.UTF_8);
			if ("exec".equals(args[0])) {
				System.out.println(args[1]);
				System.exit(255);
			} else {
				System.out.println("some output");
				System.exit(Integer.parseInt(args[1]));
			}
		}
	}

	// -------------------------------------------------------------------------
	// Base environment maps
	// -------------------------------------------------------------------------

	/**
	 * Returns a base environment map for the tests with JBANG_DIR,
	 * JBANG_CACHE_DIR, and JBANG_NO_VERSION_CHECK set. JAVA_HOME is removed.
	 * Subclasses should add their specific env vars on top.
	 */
	protected Map<String, String> baseBashEnv(String suffix) {
		Path jbdir = tempSubDir("jbdir-" + suffix);
		Path tdir = tempSubDir("cache-" + suffix);
		Map<String, String> env = new HashMap<>(System.getenv());
		env.put("JBANG_DIR", jbdir.toString());
		env.put("JBANG_CACHE_DIR", tdir.toString());
		env.put("JBANG_NO_VERSION_CHECK", "true");
		env.remove("JAVA_HOME");
		return env;
	}


	// -------------------------------------------------------------------------
	// Command builders
	// -------------------------------------------------------------------------

	/**
	 * Builds a command list for running a bash launcher.
	 */
	protected List<String> bashCmd(Path launcher, String... args) {
		List<String> cmd = new ArrayList<>();
		cmd.add("bash");
		cmd.add(launcher.toString());
		for (String arg : args) {
			cmd.add(arg);
		}
		return cmd;
	}

}
