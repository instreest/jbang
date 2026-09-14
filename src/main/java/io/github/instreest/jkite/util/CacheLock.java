package io.github.instreest.jkite.util;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.instreest.jkite.Settings;
import dev.jbang.util.Util;

/**
 * An advisory lock on one thing in the cache, held while it is written.
 *
 * Several jkite runs share <code>$JKITE_DIR</code> - a build matrix, a
 * multi-module build, two terminals - and a cache entry that several of them
 * write at once is a cache entry one of them loses. Taking a lock first makes
 * them take turns instead.
 *
 * A lock that cannot be taken is not an error: not every filesystem supports
 * locking (some network mounts do not), and the work is still worth doing
 * without one. {@link #isHeld()} says which happened, so a caller for whom that
 * matters can say so or take another precaution.
 */
public final class CacheLock implements AutoCloseable {
	private final Path file;
	private final RandomAccessFile raf;
	private final FileLock lock;

	private CacheLock(Path file, RandomAccessFile raf, FileLock lock) {
		this.file = file;
		this.raf = raf;
		this.lock = lock;
	}

	/**
	 * Takes the lock named <code>name</code>, waiting for whoever holds it.
	 *
	 * @param waitMessage printed once when there is someone to wait for, or
	 *                    null to wait quietly
	 */
	public static CacheLock acquire(String name, String waitMessage) {
		Path file = Settings.getLockDir().resolve(name + ".lock");
		return acquireAt(file, waitMessage);
	}

	/** Takes the lock in the file itself, for a lock outside the lock directory. */
	public static CacheLock acquireAt(Path file, String waitMessage) {
		try {
			Files.createDirectories(file.toAbsolutePath().getParent());
			RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw");
			FileChannel channel = raf.getChannel();
			FileLock lock = channel.tryLock();
			if (lock == null) {
				if (waitMessage != null) {
					Util.infoMsg(waitMessage);
				}
				lock = channel.lock();
			}
			return new CacheLock(file, raf, lock);
		} catch (IOException | RuntimeException e) {
			Util.verboseMsg("Could not lock " + file + ", continuing without a lock: " + e);
			return new CacheLock(file, null, null);
		}
	}

	/** False when the lock could not be taken and the work goes on unprotected. */
	public boolean isHeld() {
		return lock != null;
	}

	@Override
	public void close() {
		try {
			if (lock != null) {
				lock.release();
			}
			if (raf != null) {
				raf.close();
			}
		} catch (IOException e) {
			Util.verboseMsg("Could not release the lock on " + file + ": " + e);
		}
	}
}
