package dev.jbang;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple on-disk cache of resolved class paths so that scripts start without
 * touching the Maven resolver. Format (one entry per line):
 *
 * <pre>
 * [key]
 * coordinate&lt;TAB&gt;file&lt;TAB&gt;timestamp
 * </pre>
 */
final class DependencyCache {
	private static Map<String, List<ArtifactInfo>> cache;

	private DependencyCache() {
	}

	private static Map<String, List<ArtifactInfo>> load() {
		if (cache == null) {
			cache = new LinkedHashMap<>();
			Path file = Settings.getDependencyCacheFile();
			if (Files.isRegularFile(file)) {
				try (BufferedReader rdr = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
					String line;
					List<ArtifactInfo> current = null;
					while ((line = rdr.readLine()) != null) {
						if (line.isEmpty()) {
							continue;
						}
						if (line.startsWith("[") && line.endsWith("]")) {
							current = new ArrayList<>();
							cache.put(line.substring(1, line.length() - 1), current);
						} else if (current != null) {
							String[] parts = line.split("\t");
							if (parts.length == 3) {
								MavenCoordinate coord = parts[0].isEmpty() ? null
										: MavenCoordinate.fromString(parts[0]);
								current.add(new ArtifactInfo(coord, Paths.get(parts[1]), Long.parseLong(parts[2])));
							}
						}
					}
				} catch (IOException | RuntimeException e) {
					Util.warnMsg("Ignoring unreadable dependency cache " + file + ": " + e.getMessage());
					cache.clear();
				}
			}
		}
		return cache;
	}

	static List<ArtifactInfo> find(String key) {
		List<ArtifactInfo> cached = load().get(key);
		if (cached != null) {
			if (cached.stream().allMatch(ArtifactInfo::isUpToDate)) {
				return cached;
			}
			Util.warnMsg("Detected missing or out-of-date dependencies in cache.");
			if (Util.isVerbose()) {
				cached.stream().filter(ai -> !ai.isUpToDate())
					.forEach(ai -> Util.verboseMsg("   Artifact missing or out of date: " + ai.getFile()));
			}
		}
		return null;
	}

	static void store(String key, List<ArtifactInfo> artifacts) {
		Map<String, List<ArtifactInfo>> c = load();
		c.put(key, artifacts);
		Path file = Settings.getDependencyCacheFile();
		try {
			Path tmp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
			try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				for (Map.Entry<String, List<ArtifactInfo>> e : c.entrySet()) {
					out.write("[" + e.getKey() + "]\n");
					for (ArtifactInfo ai : e.getValue()) {
						String coord = ai.getCoordinate() != null ? ai.getCoordinate().toMavenString() : "";
						out.write(coord + "\t" + ai.getFile() + "\t" + ai.getTimestamp() + "\n");
					}
					out.write("\n");
				}
			}
			try {
				Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException e) {
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			Util.errorMsg("Issue writing to dependency cache", e);
		}
	}
}
