package dev.jbang;

import java.nio.file.Files;
import java.nio.file.Path;

/** A resolved artifact: its coordinate, local file and the file's timestamp. */
public final class ArtifactInfo {
	private final MavenCoordinate coordinate;
	private final Path file;
	private final long timestamp;

	public ArtifactInfo(MavenCoordinate coordinate, Path file) {
		this(coordinate, file, Files.exists(file) ? file.toFile().lastModified() : 0);
	}

	public ArtifactInfo(MavenCoordinate coordinate, Path file, long timestamp) {
		this.coordinate = coordinate;
		this.file = file;
		this.timestamp = timestamp;
	}

	public MavenCoordinate getCoordinate() {
		return coordinate;
	}

	public Path getFile() {
		return file;
	}

	public long getTimestamp() {
		return timestamp;
	}

	/**
	 * True if the file still exists with the timestamp we recorded. Some JDKs
	 * round timestamps to seconds, so a difference in the millisecond part is
	 * tolerated.
	 */
	public boolean isUpToDate() {
		long ts = file.toFile().lastModified();
		return Files.isReadable(file) && (timestamp == ts || (ts % 1000 == 0 && timestamp / 1000 == ts / 1000));
	}

	@Override
	public String toString() {
		return (coordinate == null ? "<null>" : coordinate.toMavenString()) + "=" + file.toAbsolutePath();
	}
}
