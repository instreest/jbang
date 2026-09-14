package io.github.instreest.jkite.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lock that keeps two jkite runs from writing the same cache entry at
 * once. What it protects against is another process; a filesystem that cannot
 * lock is not a reason to stop.
 */
class TestCacheLock {

	@TempDir
	Path dir;

	@Test
	void aLockIsTakenAndItsFileIsCreated() {
		Path file = dir.resolve("deep/down/thing.lock");

		try (CacheLock lock = CacheLock.acquireAt(file, null)) {
			assertTrue(lock.isHeld(), "the lock was not taken");
			assertTrue(Files.isRegularFile(file), "the lock file was not created");
		}
	}

	@Test
	void theLockIsFreeAgainAfterItIsClosed() {
		Path file = dir.resolve("thing.lock");

		try (CacheLock first = CacheLock.acquireAt(file, null)) {
			assertTrue(first.isHeld());
		}

		try (CacheLock second = CacheLock.acquireAt(file, null)) {
			assertTrue(second.isHeld(), "the lock was not released");
		}
	}

	/**
	 * The lock is between processes, which is what shares a cache directory. A
	 * second one inside the same JVM cannot be taken, and rather than fail the
	 * run it goes on unlocked - the same answer a filesystem that cannot lock
	 * gets.
	 */
	@Test
	void aSecondLockInTheSameRunGoesOnWithoutOne() {
		Path file = dir.resolve("thing.lock");

		try (CacheLock held = CacheLock.acquireAt(file, null);
				CacheLock second = CacheLock.acquireAt(file, null)) {
			assertTrue(held.isHeld());
			assertFalse(second.isHeld(), "two locks on one file were reported as both held");
		}
	}

	/** A lock that cannot be taken at all is not an error, only unlocked work. */
	@Test
	void aPlaceThatCannotHoldALockIsNotAnError() throws IOException {
		// a directory cannot be made underneath a file, on any of the systems
		// this runs on
		Path file = dir.resolve("not-a-directory");
		Files.write(file, "x".getBytes(StandardCharsets.UTF_8));

		try (CacheLock lock = CacheLock.acquireAt(file.resolve("thing.lock"), null)) {
			assertFalse(lock.isHeld(), "a lock was reported where none could be taken");
		}
	}

	@Test
	void closingTwiceIsHarmless() {
		CacheLock lock = CacheLock.acquireAt(dir.resolve("thing.lock"), null);
		lock.close();
		lock.close();
	}
}
