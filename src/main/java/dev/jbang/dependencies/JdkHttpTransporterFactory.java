package dev.jbang.dependencies;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.aether.ConfigurationProperties;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.AuthenticationContext;
import org.eclipse.aether.repository.Proxy;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.spi.connector.transport.AbstractTransporter;
import org.eclipse.aether.spi.connector.transport.GetTask;
import org.eclipse.aether.spi.connector.transport.PeekTask;
import org.eclipse.aether.spi.connector.transport.PutTask;
import org.eclipse.aether.spi.connector.transport.Transporter;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transfer.NoTransporterException;
import org.eclipse.aether.util.ConfigUtils;

/**
 * Maven Resolver transport for http(s) repositories on the JDK's own
 * {@link HttpClient}. It replaces maven-resolver-transport-http, which brings
 * Apache HttpClient, Gson and the public suffix list along - a third of
 * jbanglite.jar - for what JBangLite needs: GET and HEAD.
 * <p>
 * Credentials and proxies come from the repository as Maven Resolver sees it,
 * so ~/.m2/settings.xml applies as before; without a proxy of its own the
 * JDK's default proxy selector (the https.proxy* system properties) is used.
 * Uploads are not supported: JBangLite never deploys.
 */
public final class JdkHttpTransporterFactory implements TransporterFactory {

	@Override
	public Transporter newInstance(RepositorySystemSession session, RemoteRepository repository)
			throws NoTransporterException {
		Objects.requireNonNull(session, "session cannot be null");
		Objects.requireNonNull(repository, "repository cannot be null");
		String protocol = repository.getProtocol();
		if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
			throw new NoTransporterException(repository);
		}
		return new JdkHttpTransporter(session, repository);
	}

	@Override
	public float getPriority() {
		return 5.0f;
	}

	/** A response with a status that is not a success (2xx). */
	static final class HttpStatusException extends IOException {
		private static final long serialVersionUID = 1L;
		final int status;

		HttpStatusException(int status, URI uri) {
			super("HTTP " + status + " for " + uri);
			this.status = status;
		}
	}

	static final class JdkHttpTransporter extends AbstractTransporter {
		private final HttpClient client;
		private final URI baseUri;
		private final String userAgent;
		private final String authorization;
		private final Map<String, String> headers;
		private final Duration requestTimeout;

		@SuppressWarnings("unchecked")
		JdkHttpTransporter(RepositorySystemSession session, RemoteRepository repository) {
			String repoId = repository.getId();
			String url = repository.getUrl();
			this.baseUri = URI.create(url.endsWith("/") ? url : url + "/");
			this.userAgent = ConfigUtils.getString(session, ConfigurationProperties.DEFAULT_USER_AGENT,
					ConfigurationProperties.USER_AGENT);
			this.headers = (Map<String, String>) ConfigUtils.getMap(session, null,
					ConfigurationProperties.HTTP_HEADERS + "." + repoId, ConfigurationProperties.HTTP_HEADERS);
			this.requestTimeout = Duration.ofMillis(ConfigUtils.getInteger(session,
					ConfigurationProperties.DEFAULT_REQUEST_TIMEOUT,
					ConfigurationProperties.REQUEST_TIMEOUT + "." + repoId, ConfigurationProperties.REQUEST_TIMEOUT));
			Duration connectTimeout = Duration.ofMillis(ConfigUtils.getInteger(session,
					ConfigurationProperties.DEFAULT_CONNECT_TIMEOUT,
					ConfigurationProperties.CONNECT_TIMEOUT + "." + repoId, ConfigurationProperties.CONNECT_TIMEOUT));

			// repository credentials are sent preemptively, as Basic auth; the
			// resolver's transport does so too when asked, and the servers
			// scripts talk to (Central, JitPack, a Nexus) all accept it
			this.authorization = basicAuth(AuthenticationContext.forRepository(session, repository));

			HttpClient.Builder builder = HttpClient.newBuilder()
				.followRedirects(HttpClient.Redirect.NORMAL)
				.connectTimeout(connectTimeout);
			Proxy proxy = repository.getProxy();
			if (proxy != null) {
				builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.getHost(), proxy.getPort())));
				AuthenticationContext proxyAuth = AuthenticationContext.forProxy(session, repository);
				if (proxyAuth != null) {
					String user = proxyAuth.get(AuthenticationContext.USERNAME);
					String password = proxyAuth.get(AuthenticationContext.PASSWORD);
					AuthenticationContext.close(proxyAuth);
					if (user != null) {
						builder.authenticator(new Authenticator() {
							@Override
							protected PasswordAuthentication getPasswordAuthentication() {
								if (getRequestorType() == RequestorType.PROXY) {
									return new PasswordAuthentication(user,
											password == null ? new char[0] : password.toCharArray());
								}
								return null;
							}
						});
					}
				}
			}
			this.client = builder.build();
		}

		private static String basicAuth(AuthenticationContext auth) {
			if (auth == null) {
				return null;
			}
			try {
				String user = auth.get(AuthenticationContext.USERNAME);
				String password = auth.get(AuthenticationContext.PASSWORD);
				if (user == null) {
					return null;
				}
				String pair = user + ":" + (password == null ? "" : password);
				return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
			} finally {
				AuthenticationContext.close(auth);
			}
		}

		private HttpRequest.Builder request(URI location) {
			HttpRequest.Builder request = HttpRequest.newBuilder(baseUri.resolve(location))
				.timeout(requestTimeout)
				.header("User-Agent", userAgent);
			if (authorization != null) {
				request.header("Authorization", authorization);
			}
			if (headers != null) {
				headers.forEach(request::header);
			}
			return request;
		}

		@Override
		public int classify(Throwable error) {
			if (error instanceof HttpStatusException && ((HttpStatusException) error).status == 404) {
				return ERROR_NOT_FOUND;
			}
			return ERROR_OTHER;
		}

		@Override
		protected void implPeek(PeekTask task) throws Exception {
			HttpRequest request = request(task.getLocation())
				.method("HEAD", HttpRequest.BodyPublishers.noBody())
				.build();
			HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
			checkStatus(response, request.uri());
		}

		@Override
		protected void implGet(GetTask task) throws Exception {
			HttpRequest.Builder builder = request(task.getLocation()).GET();
			long resumeOffset = task.getResumeOffset();
			if (resumeOffset > 0) {
				builder.header("Range", "bytes=" + resumeOffset + "-");
			}
			HttpRequest request = builder.build();
			HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream body = response.body()) {
				checkStatus(response, request.uri());
				extractChecksums(response.headers(), task);
				// a partial response continues the file; anything else starts over
				boolean resume = resumeOffset > 0 && response.statusCode() == 206;
				long length = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
				if (resume && length >= 0) {
					length += resumeOffset;
				}
				utilGet(task, body, true, length, resume);
			}
		}

		/**
		 * Checksums some repositories send in the response headers, so that the
		 * resolver can skip fetching the .sha1/.md5 file next to the artifact.
		 * This is what maven-resolver-transport-http's XChecksumChecksumExtractor
		 * (x-checksum-* as sent by Maven Central and Nexus 3, x-goog-meta-* by
		 * Google Cloud Storage) and Nexus2ChecksumExtractor (an ETag of the form
		 * "{SHA1{<hex>}}") did.
		 */
		static void extractChecksums(HttpHeaders headers, GetTask task) {
			boolean found = false;
			for (String[] header : X_CHECKSUM_HEADERS) {
				Optional<String> value = headers.firstValue(header[0]);
				if (value.isPresent() && !value.get().trim().isEmpty()) {
					task.setChecksum(header[1], value.get().trim());
					found = true;
				}
			}
			if (found) {
				return;
			}
			Optional<String> etag = headers.firstValue("ETag");
			if (etag.isPresent()) {
				String value = etag.get();
				int start = value.indexOf("SHA1{");
				int end = value.indexOf("}", start);
				if (start >= 0 && end > start) {
					task.setChecksum("SHA-1", value.substring(start + "SHA1{".length(), end));
				}
			}
		}

		/** header name and the resolver's name for the algorithm */
		private static final String[][] X_CHECKSUM_HEADERS = {
				{ "x-checksum-sha1", "SHA-1" },
				{ "x-checksum-md5", "MD5" },
				{ "x-goog-meta-checksum-sha1", "SHA-1" },
				{ "x-goog-meta-checksum-md5", "MD5" },
		};

		@Override
		protected void implPut(PutTask task) {
			throw new UnsupportedOperationException("JBangLite does not upload to repositories");
		}

		@Override
		protected void implClose() {
			// HttpClient has nothing to close on Java 11
		}

		private static void checkStatus(HttpResponse<?> response, URI uri) throws HttpStatusException {
			int status = response.statusCode();
			if (status < 200 || status >= 300) {
				throw new HttpStatusException(status, uri);
			}
		}
	}
}
