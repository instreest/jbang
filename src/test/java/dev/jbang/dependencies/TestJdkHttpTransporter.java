package dev.jbang.dependencies;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.headRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.transport.GetTask;
import org.eclipse.aether.spi.connector.transport.PeekTask;
import org.eclipse.aether.spi.connector.transport.Transporter;
import org.eclipse.aether.transfer.NoTransporterException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * The HTTP transport on the JDK's HttpClient, against a local server: what
 * Maven Resolver's basic connector relies on is that 404 classifies as "not
 * found", that checksums sent in headers reach the task, and that a resumed
 * download continues where it stopped.
 */
class TestJdkHttpTransporter {

	@TempDir
	Path tempDir;

	private WireMockServer wm;
	private Transporter transporter;

	@BeforeEach
	void start() throws NoTransporterException {
		wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
		wm.start();
		RemoteRepository repo = new RemoteRepository.Builder("test", "default", wm.baseUrl() + "/repo").build();
		transporter = new JdkHttpTransporterFactory().newInstance(new DefaultRepositorySystemSession(), repo);
	}

	@AfterEach
	void stop() {
		transporter.close();
		wm.stop();
	}

	@Test
	void onlyHttpRepositoriesAreAccepted() {
		RemoteRepository file = new RemoteRepository.Builder("f", "default", "file:///tmp/repo").build();
		assertThrows(NoTransporterException.class,
				() -> new JdkHttpTransporterFactory().newInstance(new DefaultRepositorySystemSession(), file));
	}

	@Test
	void getDownloadsAndTakesChecksumsFromHeaders() throws Exception {
		wm.stubFor(get(urlEqualTo("/repo/g/a/1/a-1.jar")).willReturn(aResponse()
			.withStatus(200)
			.withHeader("x-checksum-sha1", "0123456789abcdef0123456789abcdef01234567")
			.withHeader("x-checksum-md5", "0123456789abcdef0123456789abcdef")
			.withBody("jar content")));
		Path target = tempDir.resolve("a-1.jar");

		GetTask task = new GetTask(URI.create("g/a/1/a-1.jar")).setDataFile(target.toFile());
		transporter.get(task);

		assertEquals("jar content", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
		assertEquals("0123456789abcdef0123456789abcdef01234567", task.getChecksums().get("SHA-1"));
		assertEquals("0123456789abcdef0123456789abcdef", task.getChecksums().get("MD5"));
	}

	@Test
	void googleHeadersAreOnlyUsedWithoutCentralOnes() throws Exception {
		wm.stubFor(get(urlEqualTo("/repo/both.jar")).willReturn(aResponse()
			.withStatus(200)
			.withHeader("x-checksum-sha1", "1111111111111111111111111111111111111111")
			.withHeader("x-goog-meta-checksum-sha1", "2222222222222222222222222222222222222222")
			.withBody("x")));
		wm.stubFor(get(urlEqualTo("/repo/google.jar")).willReturn(aResponse()
			.withStatus(200)
			.withHeader("x-goog-meta-checksum-md5", "0123456789abcdef0123456789abcdef")
			.withBody("x")));

		GetTask both = new GetTask(URI.create("both.jar"));
		transporter.get(both);
		assertEquals("1111111111111111111111111111111111111111", both.getChecksums().get("SHA-1"));

		GetTask google = new GetTask(URI.create("google.jar"));
		transporter.get(google);
		assertEquals("0123456789abcdef0123456789abcdef", google.getChecksums().get("MD5"));
	}

	@Test
	void nexus2EtagIsReadWhenThereAreNoChecksumHeaders() throws Exception {
		wm.stubFor(get(urlEqualTo("/repo/a.pom")).willReturn(aResponse()
			.withStatus(200)
			.withHeader("ETag", "\"{SHA1{0123456789abcdef0123456789abcdef01234567}}\"")
			.withBody("<project/>")));

		GetTask task = new GetTask(URI.create("a.pom"));
		transporter.get(task);

		assertEquals("<project/>", task.getDataString());
		assertEquals("0123456789abcdef0123456789abcdef01234567", task.getChecksums().get("SHA-1"));
	}

	@Test
	void plainEtagIsNotMistakenForAChecksum() throws Exception {
		wm.stubFor(get(urlEqualTo("/repo/a.pom")).willReturn(aResponse()
			.withStatus(200)
			.withHeader("ETag", "\"544add6fbc8d4b100b07c3692d08099e\"")
			.withBody("<project/>")));

		GetTask task = new GetTask(URI.create("a.pom"));
		transporter.get(task);

		assertTrue(task.getChecksums().isEmpty(), task.getChecksums().toString());
	}

	@Test
	void notFoundIsClassifiedAsSuch() {
		wm.stubFor(get(urlEqualTo("/repo/missing.jar")).willReturn(aResponse().withStatus(404)));
		wm.stubFor(head(urlEqualTo("/repo/missing.jar")).willReturn(aResponse().withStatus(404)));
		wm.stubFor(get(urlEqualTo("/repo/broken.jar")).willReturn(aResponse().withStatus(500)));

		Exception notFound = assertThrows(Exception.class,
				() -> transporter.get(new GetTask(URI.create("missing.jar"))));
		assertEquals(Transporter.ERROR_NOT_FOUND, transporter.classify(notFound));
		Exception peekNotFound = assertThrows(Exception.class,
				() -> transporter.peek(new PeekTask(URI.create("missing.jar"))));
		assertEquals(Transporter.ERROR_NOT_FOUND, transporter.classify(peekNotFound));
		Exception other = assertThrows(Exception.class,
				() -> transporter.get(new GetTask(URI.create("broken.jar"))));
		assertEquals(Transporter.ERROR_OTHER, transporter.classify(other));
	}

	@Test
	void peekSucceedsForAnExistingFile() throws Exception {
		wm.stubFor(head(urlEqualTo("/repo/a.jar")).willReturn(aResponse().withStatus(200)));
		transporter.peek(new PeekTask(URI.create("a.jar")));
		wm.verify(1, headRequestedFor(urlEqualTo("/repo/a.jar")));
	}

	@Test
	void resumedDownloadContinuesWherePartialFileStopped() throws Exception {
		Path target = tempDir.resolve("big.jar");
		Files.write(target, "first ".getBytes(StandardCharsets.UTF_8));
		wm.stubFor(get(urlEqualTo("/repo/big.jar")).willReturn(aResponse()
			.withStatus(206)
			.withHeader("Content-Range", "bytes 6-11/12")
			.withBody("second")));

		GetTask task = new GetTask(URI.create("big.jar")).setDataFile(target.toFile(), true);
		transporter.get(task);

		assertEquals("first second", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
		wm.verify(getRequestedFor(urlEqualTo("/repo/big.jar")).withHeader("Range", equalTo("bytes=6-")));
	}

	@Test
	void fullResponseToARangeRequestStartsOver() throws Exception {
		Path target = tempDir.resolve("big.jar");
		Files.write(target, "stale".getBytes(StandardCharsets.UTF_8));
		wm.stubFor(get(urlEqualTo("/repo/big.jar")).willReturn(aResponse().withStatus(200).withBody("fresh")));

		GetTask task = new GetTask(URI.create("big.jar")).setDataFile(target.toFile(), true);
		transporter.get(task);

		assertArrayEquals("fresh".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(target));
	}

	@Test
	void userAgentAndConfiguredHeadersAreSent() throws Exception {
		DefaultRepositorySystemSession session = new DefaultRepositorySystemSession();
		session.setConfigProperty("aether.connector.userAgent", "JBangLite/test");
		session.setConfigProperty("aether.connector.http.headers.test", Collections.singletonMap("X-Extra", "yes"));
		RemoteRepository repo = new RemoteRepository.Builder("test", "default", wm.baseUrl() + "/repo").build();
		wm.stubFor(get(urlEqualTo("/repo/a.pom")).willReturn(aResponse().withStatus(200).withBody("x")));

		try (Transporter t = new JdkHttpTransporterFactory().newInstance(session, repo)) {
			t.get(new GetTask(URI.create("a.pom")));
		}

		wm.verify(getRequestedFor(urlEqualTo("/repo/a.pom"))
			.withHeader("User-Agent", equalTo("JBangLite/test"))
			.withHeader("X-Extra", equalTo("yes")));
	}
}
