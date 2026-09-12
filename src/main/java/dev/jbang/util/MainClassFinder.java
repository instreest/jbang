package dev.jbang.util;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds classes declaring a given method by reading the class files directly
 * (constant pool plus method table), so no bytecode library is needed. This
 * covers what JBang uses jandex for: locating the main class and the
 * <code>premain</code> or <code>agentmain</code> of a Java agent.
 */
public final class MainClassFinder {
	private static final int ACC_STATIC = 0x0008;

	public static final String DESC_MAIN = "([Ljava/lang/String;)V";
	public static final String DESC_NO_ARGS = "()V";

	/** A method as found in a class file. */
	public static final class Method {
		public final String name;
		public final String descriptor;
		public final boolean isStatic;

		Method(String name, String descriptor, boolean isStatic) {
			this.name = name;
			this.descriptor = descriptor;
			this.isStatic = isStatic;
		}
	}

	private MainClassFinder() {
	}

	/**
	 * Fully qualified names of all classes under dir with a main method, both
	 * the classic <code>public static void main(String[])</code> and the
	 * instance <code>void main()</code> of JEP 512.
	 */
	public static List<String> findMainClasses(Path dir) throws IOException {
		return scan(dir, m -> ("main".equals(m.name)
				&& (DESC_MAIN.equals(m.descriptor) || DESC_NO_ARGS.equals(m.descriptor))));
	}

	/**
	 * Fully qualified names of the classes under dir declaring the given agent
	 * method, with or without the Instrumentation parameter.
	 */

	private static List<String> scan(Path dir, java.util.function.Predicate<Method> wanted) throws IOException {
		try (Stream<Path> paths = Files.walk(dir)) {
			List<Path> files = paths
				.filter(Files::isRegularFile)
				.filter(f -> f.getFileName().toString().endsWith(".class"))
				.filter(f -> !f.getFileName().toString().contains("$"))
				.sorted()
				.collect(Collectors.toList());
			List<String> found = new ArrayList<>();
			for (Path f : files) {
				try (InputStream is = Files.newInputStream(f)) {
					String name = classNameIfMatches(is, wanted);
					if (name != null) {
						found.add(name);
					}
				}
			}
			return found;
		}
	}

	/**
	 * Returns the class name if the class file declares a method the predicate
	 * accepts, otherwise null.
	 */
	static String classNameIfMatches(InputStream is, java.util.function.Predicate<Method> wanted)
			throws IOException {
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
		boolean matches = false;
		for (int i = 0; i < methodCount; i++) {
			int access = in.readUnsignedShort();
			String name = utf8[in.readUnsignedShort()];
			String desc = utf8[in.readUnsignedShort()];
			skipAttributes(in);
			if (!matches && wanted.test(new Method(name, desc, (access & ACC_STATIC) != 0))) {
				matches = true;
			}
		}
		return matches ? utf8[classNameIdx[thisClass]].replace('/', '.') : null;
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

	/** Unused, kept so the predicate type stays readable. */
	interface MethodMatcher extends BiPredicate<String, Method> {
	}
}
