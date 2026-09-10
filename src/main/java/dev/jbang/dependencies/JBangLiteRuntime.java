package dev.jbang.dependencies;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.ChecksumExtractor;

import eu.maveniverse.maven.mima.context.Lookup;
import eu.maveniverse.maven.mima.runtime.shared.PreBoot;
import eu.maveniverse.maven.mima.runtime.standalonestatic.MemoizingRepositorySystemSupplierLookup;
import eu.maveniverse.maven.mima.runtime.standalonestatic.StandaloneStaticRuntime;

/**
 * MIMA's standalone runtime with the HTTP transport swapped for
 * {@link JdkHttpTransporterFactory}. maven-resolver-transport-http is not in
 * jbanglite.jar (see build.gradle), so the two suppliers that would create its
 * classes are overridden; {@link ChecksumExtractor} is only named in a
 * signature here and is never loaded.
 */
public final class JBangLiteRuntime extends StandaloneStaticRuntime {

	public JBangLiteRuntime() {
		super("jbanglite", 20);
	}

	@Override
	protected Lookup createRepositorySystemLookup(PreBoot preBoot) {
		return new JdkHttpLookup();
	}

	private static final class JdkHttpLookup extends MemoizingRepositorySystemSupplierLookup {
		@Override
		protected Map<String, ChecksumExtractor> getChecksumExtractors() {
			// the transport reads them from the response headers itself, see
			// JdkHttpTransporterFactory.extractChecksums; this SPI is tied to
			// Apache HttpClient's response type
			return Collections.emptyMap();
		}

		@Override
		protected Map<String, TransporterFactory> getTransporterFactories(
				Map<String, ChecksumExtractor> checksumExtractors) {
			Map<String, TransporterFactory> factories = new HashMap<>();
			factories.put("file", new FileTransporterFactory());
			factories.put("http", new JdkHttpTransporterFactory());
			return memoize(TransporterFactory.class, factories);
		}
	}
}
