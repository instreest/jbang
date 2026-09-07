package dev.jbang;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Minimal HTTP(S) downloader that follows redirects (also across protocols). */
final class Downloader {
	private static final int MAX_REDIRECTS = 10;

	private Downloader() {
	}

	/** Downloads the URL into the given file, returning the final URL's file name. */
	static String download(String url, Path target) throws IOException {
		String current = url;
		for (int i = 0; i < MAX_REDIRECTS; i++) {
			HttpURLConnection conn = (HttpURLConnection) new URL(current).openConnection();
			conn.setInstanceFollowRedirects(false);
			conn.setConnectTimeout(30_000);
			conn.setReadTimeout(120_000);
			conn.setRequestProperty("User-Agent", "JBang/" + Util.getJBangVersion());
			int status = conn.getResponseCode();
			if (status >= 300 && status < 400) {
				String location = conn.getHeaderField("Location");
				if (location == null) {
					throw new IOException("Redirect without Location from " + current);
				}
				current = new URL(new URL(current), location).toString();
				conn.disconnect();
				continue;
			}
			if (status < 200 || status >= 300) {
				throw new IOException("HTTP " + status + " when downloading " + current);
			}
			Files.createDirectories(target.toAbsolutePath().getParent());
			try (InputStream is = conn.getInputStream()) {
				Files.copy(is, target, StandardCopyOption.REPLACE_EXISTING);
			}
			String path = new URL(current).getPath();
			return path.substring(path.lastIndexOf('/') + 1);
		}
		throw new IOException("Too many redirects for " + url);
	}
}
