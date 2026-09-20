package io.github.instreest.jkite.util;

import java.io.IOException;
import java.nio.file.Path;

import dev.jbang.util.Util;

/**
 * The file a shell would open, rather than the one string folding arrives at.
 *
 * toAbsolutePath().normalize() folds ".." textually: it looks at "/a/b/../c",
 * sees that ".." cancels the component before it, and answers "/a/c" without
 * asking the file system anything. That is right only while no component is a
 * link.
 *
 * If "b" is a symbolic link to "/elsewhere/d", then opening "/a/b/../c" goes
 * through the link first and lands in "/elsewhere/c". So a shell, and every
 * other tool, reads "/elsewhere/c" - and jkite, having folded the string,
 * would compile and run "/a/c". A different file, from the same path, with
 * nothing said about it. That was measured, not supposed.
 *
 * toRealPath() asks the file system instead: it resolves each link as it
 * walks, and applies ".." to what the link actually reached. Same procedure
 * as the shell, so the same answer.
 *
 * It needs the file to exist, which folding did not, so a path that is not
 * there falls back to folding and lets whoever asked report the missing file
 * in their own words. That keeps this from turning "no such script" into a
 * stack trace.
 */
public final class RealPath {

	private RealPath() {
	}

	public static Path of(Path path) {
		try {
			return path.toRealPath();
		} catch (IOException e) {
			// not there, or not readable, or a file system that will not say -
			// the caller has a better message for that than this does
			Util.verboseMsg("Could not resolve " + path + " to a real path: " + e);
			return path.toAbsolutePath().normalize();
		}
	}
}
