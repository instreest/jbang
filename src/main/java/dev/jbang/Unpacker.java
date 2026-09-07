package dev.jbang;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Unpacks JDK archives (.zip and .tar.gz) using only the JDK's own APIs. The
 * single root folder of the archive is stripped and on macOS the
 * <code>Contents/Home</code> folder is selected. Mirrors the behaviour of
 * jbang-devkitman's UnpackUtils (MIT, see THIRD-PARTY.md) without
 * commons-compress.
 */
final class Unpacker {
	private Unpacker() {
	}

	static void unpackJdk(Path archive, Path outputDir) throws IOException {
		String name = archive.getFileName().toString().toLowerCase(Locale.ENGLISH);
		Path selectFolder = Util.isMac() ? Paths.get("Contents/Home") : null;
		if (name.endsWith(".zip")) {
			unzip(archive, outputDir, selectFolder);
		} else if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
			untargz(archive, outputDir, selectFolder);
		} else {
			throw new IOException("Unsupported archive format: " + archive);
		}
	}

	private static Path targetPath(String entryName, Path outputDir, Path selectFolder) throws IOException {
		Path entry = Paths.get(entryName).normalize();
		if (entry.getNameCount() <= 1) {
			return null; // root folder itself
		}
		entry = entry.subpath(1, entry.getNameCount());
		if (selectFolder != null) {
			if (!entry.startsWith(selectFolder) || entry.equals(selectFolder)) {
				return null;
			}
			entry = entry.subpath(selectFolder.getNameCount(), entry.getNameCount());
		}
		Path out = outputDir.resolve(entry).normalize();
		if (!out.startsWith(outputDir)) {
			throw new IOException("Entry is outside of the target dir: " + entryName);
		}
		return out;
	}

	private static void unzip(Path zip, Path outputDir, Path selectFolder) throws IOException {
		try (ZipFile zipFile = new ZipFile(zip.toFile())) {
			Enumeration<? extends ZipEntry> entries = zipFile.entries();
			while (entries.hasMoreElements()) {
				ZipEntry ze = entries.nextElement();
				Path out = targetPath(ze.getName(), outputDir, selectFolder);
				if (out == null) {
					continue;
				}
				if (ze.isDirectory()) {
					Files.createDirectories(out);
				} else {
					Files.createDirectories(out.getParent());
					try (InputStream is = zipFile.getInputStream(ze)) {
						Files.copy(is, out, StandardCopyOption.REPLACE_EXISTING);
					}
					// java.util.zip does not expose unix permissions; make bin/* executable
					if (!Util.isWindows() && out.getParent().getFileName().toString().equals("bin")) {
						out.toFile().setExecutable(true, false);
					}
				}
			}
		}
	}

	private static void untargz(Path targz, Path outputDir, Path selectFolder) throws IOException {
		try (InputStream in = new GZIPInputStream(Files.newInputStream(targz))) {
			byte[] header = new byte[512];
			String longName = null;
			while (readFully(in, header)) {
				if (isEmpty(header)) {
					continue;
				}
				String name = readString(header, 0, 100);
				int mode = (int) readOctal(header, 100, 8);
				long size = readOctal(header, 124, 12);
				char type = (char) header[156];
				String linkName = readString(header, 157, 100);
				String magic = readString(header, 257, 6);
				if (magic.startsWith("ustar")) {
					String prefix = readString(header, 345, 155);
					if (!prefix.isEmpty()) {
						name = prefix + "/" + name;
					}
				}
				if (longName != null) {
					name = longName;
					longName = null;
				}
				long padded = (size + 511) / 512 * 512;
				if (type == 'L') { // GNU long name
					byte[] data = new byte[(int) size];
					readFully(in, data);
					longName = new String(data, StandardCharsets.UTF_8).trim();
					skip(in, padded - size);
					continue;
				}
				Path out = targetPath(name, outputDir, selectFolder);
				if (out == null || (type != '0' && type != '\0' && type != '5' && type != '2' && type != '1')) {
					skip(in, padded);
					continue;
				}
				if (type == '5') {
					Files.createDirectories(out);
					skip(in, padded);
				} else if (type == '2' || type == '1') {
					Files.createDirectories(out.getParent());
					if (!Files.exists(out)) {
						try {
							Files.createSymbolicLink(out, Paths.get(linkName));
						} catch (IOException | UnsupportedOperationException e) {
							Util.verboseMsg("Could not create link " + out + " -> " + linkName + ": " + e);
						}
					}
					skip(in, padded);
				} else {
					Files.createDirectories(out.getParent());
					try (java.io.OutputStream os = Files.newOutputStream(out)) {
						copy(in, os, size);
					}
					skip(in, padded - size);
					if (mode != 0 && !Util.isWindows()) {
						try {
							Files.setPosixFilePermissions(out, toPosix(mode));
						} catch (UnsupportedOperationException e) {
							// ignore on non-POSIX file systems
						}
					}
				}
			}
		}
	}

	private static boolean readFully(InputStream in, byte[] buf) throws IOException {
		int off = 0;
		while (off < buf.length) {
			int n = in.read(buf, off, buf.length - off);
			if (n < 0) {
				return off > 0 && off == buf.length;
			}
			off += n;
		}
		return true;
	}

	private static void copy(InputStream in, java.io.OutputStream os, long size) throws IOException {
		byte[] buf = new byte[65536];
		long remaining = size;
		while (remaining > 0) {
			int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
			if (n < 0) {
				throw new IOException("Unexpected end of archive");
			}
			os.write(buf, 0, n);
			remaining -= n;
		}
	}

	private static void skip(InputStream in, long n) throws IOException {
		while (n > 0) {
			long s = in.skip(n);
			if (s <= 0) {
				if (in.read() < 0) {
					return;
				}
				s = 1;
			}
			n -= s;
		}
	}

	private static boolean isEmpty(byte[] header) {
		for (byte b : header) {
			if (b != 0) {
				return false;
			}
		}
		return true;
	}

	private static String readString(byte[] buf, int off, int len) {
		int end = off;
		while (end < off + len && buf[end] != 0) {
			end++;
		}
		return new String(buf, off, end - off, StandardCharsets.UTF_8);
	}

	private static long readOctal(byte[] buf, int off, int len) {
		String s = readString(buf, off, len).trim();
		return s.isEmpty() ? 0 : Long.parseLong(s, 8);
	}

	private static Set<PosixFilePermission> toPosix(int mode) {
		Set<PosixFilePermission> perms = EnumSet.noneOf(PosixFilePermission.class);
		PosixFilePermission[] all = { PosixFilePermission.OTHERS_EXECUTE, PosixFilePermission.OTHERS_WRITE,
				PosixFilePermission.OTHERS_READ, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.GROUP_WRITE,
				PosixFilePermission.GROUP_READ, PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.OWNER_WRITE,
				PosixFilePermission.OWNER_READ };
		for (int i = 0; i < all.length; i++) {
			if ((mode & (1 << i)) != 0) {
				perms.add(all[i]);
			}
		}
		return perms;
	}
}
