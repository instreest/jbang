package dev.jbang;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds classes with a <code>main</code> method by reading the class files
 * directly (constant pool + method table), so no bytecode library is needed.
 * Both <code>public static void main(String[])</code> and the instance
 * <code>void main()</code> of JEP 445/512 are accepted.
 */
final class MainClassFinder {
	private static final int ACC_STATIC = 0x0008;

	private MainClassFinder() {
	}

	/** Fully qualified names of all classes with a main method under dir. */
	static List<String> findMainClasses(Path dir) throws IOException {
		try (Stream<Path> paths = Files.walk(dir)) {
			List<Path> files = paths
				.filter(Files::isRegularFile)
				.filter(f -> f.getFileName().toString().endsWith(".class"))
				.filter(f -> !f.getFileName().toString().contains("$"))
				.sorted()
				.collect(Collectors.toList());
			List<String> mains = new ArrayList<>();
			for (Path f : files) {
				try (InputStream is = Files.newInputStream(f)) {
					String name = mainClassName(is);
					if (name != null) {
						mains.add(name);
					}
				}
			}
			return mains;
		}
	}

	/** Returns the class name if the class file has a main method, else null. */
	static String mainClassName(InputStream is) throws IOException {
		DataInputStream in = new DataInputStream(is);
		if (in.readInt() != 0xCAFEBABE) {
			return null;
		}
		in.readUnsignedShort(); // minor
		in.readUnsignedShort(); // major
		int cpCount = in.readUnsignedShort();
		String[] utf8 = new String[cpCount];
		int[] classNameIdx = new int[cpCount];
		for (int i = 1; i < cpCount; i++) {
			int tag = in.readUnsignedByte();
			switch (tag) {
			case 1: // Utf8
				utf8[i] = in.readUTF();
				break;
			case 7: // Class
				classNameIdx[i] = in.readUnsignedShort();
				break;
			case 8: // String
			case 16: // MethodType
			case 19: // Module
			case 20: // Package
				in.readUnsignedShort();
				break;
			case 15: // MethodHandle
				in.readUnsignedByte();
				in.readUnsignedShort();
				break;
			case 3: // Integer
			case 4: // Float
			case 9: // Fieldref
			case 10: // Methodref
			case 11: // InterfaceMethodref
			case 12: // NameAndType
			case 17: // Dynamic
			case 18: // InvokeDynamic
				in.readInt();
				break;
			case 5: // Long
			case 6: // Double
				in.readLong();
				i++;
				break;
			default:
				throw new IOException("Unknown constant pool tag " + tag);
			}
		}
		in.readUnsignedShort(); // access flags
		int thisClass = in.readUnsignedShort();
		in.readUnsignedShort(); // super
		int ifCount = in.readUnsignedShort();
		for (int i = 0; i < ifCount; i++) {
			in.readUnsignedShort();
		}
		int fieldCount = in.readUnsignedShort();
		for (int i = 0; i < fieldCount; i++) {
			skipMember(in);
		}
		int methodCount = in.readUnsignedShort();
		boolean hasMain = false;
		for (int i = 0; i < methodCount; i++) {
			int access = in.readUnsignedShort();
			String name = utf8[in.readUnsignedShort()];
			String desc = utf8[in.readUnsignedShort()];
			skipAttributes(in);
			if ("main".equals(name)) {
				if ("([Ljava/lang/String;)V".equals(desc) && (access & ACC_STATIC) != 0) {
					hasMain = true;
				} else if ("()V".equals(desc) || "([Ljava/lang/String;)V".equals(desc)) {
					hasMain = true; // instance main (JEP 512)
				}
			}
		}
		if (!hasMain) {
			return null;
		}
		return utf8[classNameIdx[thisClass]].replace('/', '.');
	}

	private static void skipMember(DataInputStream in) throws IOException {
		in.readUnsignedShort(); // access
		in.readUnsignedShort(); // name
		in.readUnsignedShort(); // descriptor
		skipAttributes(in);
	}

	private static void skipAttributes(DataInputStream in) throws IOException {
		int count = in.readUnsignedShort();
		for (int i = 0; i < count; i++) {
			in.readUnsignedShort(); // name
			int len = in.readInt();
			in.skipBytes(len);
		}
	}
}
