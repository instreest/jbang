package dev.jbang.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader, enough to parse the JDK index. Objects become
 * {@code Map<String, Object>}, arrays {@code List<Object>}, strings
 * {@code String}, numbers {@code Double} and the literals
 * {@code Boolean}/{@code null}.
 */
public final class Json {
	private final String src;
	private int pos;

	private Json(String src) {
		this.src = src;
	}

	public static Object parse(String text) {
		Json json = new Json(text);
		json.skipWhitespace();
		Object value = json.readValue();
		json.skipWhitespace();
		if (json.pos < json.src.length()) {
			throw json.error("Trailing content");
		}
		return value;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> parseObject(String text) {
		Object value = parse(text);
		if (!(value instanceof Map)) {
			throw new IllegalArgumentException("Expected a JSON object");
		}
		return (Map<String, Object>) value;
	}

	private Object readValue() {
		if (pos >= src.length()) {
			throw error("Unexpected end of input");
		}
		char c = src.charAt(pos);
		switch (c) {
		case '{':
			return readObject();
		case '[':
			return readArray();
		case '"':
			return readString();
		case 't':
			expect("true");
			return Boolean.TRUE;
		case 'f':
			expect("false");
			return Boolean.FALSE;
		case 'n':
			expect("null");
			return null;
		default:
			return readNumber();
		}
	}

	private Map<String, Object> readObject() {
		Map<String, Object> map = new LinkedHashMap<>();
		pos++; // {
		skipWhitespace();
		if (peek() == '}') {
			pos++;
			return map;
		}
		while (true) {
			skipWhitespace();
			if (peek() != '"') {
				throw error("Expected a member name");
			}
			String name = readString();
			skipWhitespace();
			if (peek() != ':') {
				throw error("Expected ':'");
			}
			pos++;
			skipWhitespace();
			map.put(name, readValue());
			skipWhitespace();
			char c = peek();
			pos++;
			if (c == '}') {
				return map;
			}
			if (c != ',') {
				throw error("Expected ',' or '}'");
			}
		}
	}

	private List<Object> readArray() {
		List<Object> list = new ArrayList<>();
		pos++; // [
		skipWhitespace();
		if (peek() == ']') {
			pos++;
			return list;
		}
		while (true) {
			skipWhitespace();
			list.add(readValue());
			skipWhitespace();
			char c = peek();
			pos++;
			if (c == ']') {
				return list;
			}
			if (c != ',') {
				throw error("Expected ',' or ']'");
			}
		}
	}

	private String readString() {
		pos++; // opening quote
		StringBuilder sb = new StringBuilder();
		while (true) {
			if (pos >= src.length()) {
				throw error("Unterminated string");
			}
			char c = src.charAt(pos++);
			if (c == '"') {
				return sb.toString();
			}
			if (c != '\\') {
				sb.append(c);
				continue;
			}
			char esc = src.charAt(pos++);
			switch (esc) {
			case '"':
			case '\\':
			case '/':
				sb.append(esc);
				break;
			case 'b':
				sb.append('\b');
				break;
			case 'f':
				sb.append('\f');
				break;
			case 'n':
				sb.append('\n');
				break;
			case 'r':
				sb.append('\r');
				break;
			case 't':
				sb.append('\t');
				break;
			case 'u':
				sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
				pos += 4;
				break;
			default:
				throw error("Invalid escape '\\" + esc + "'");
			}
		}
	}

	private Double readNumber() {
		int start = pos;
		while (pos < src.length() && "+-.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
			pos++;
		}
		try {
			return Double.valueOf(src.substring(start, pos));
		} catch (NumberFormatException e) {
			throw error("Invalid number");
		}
	}

	private void expect(String literal) {
		if (!src.startsWith(literal, pos)) {
			throw error("Expected '" + literal + "'");
		}
		pos += literal.length();
	}

	private char peek() {
		if (pos >= src.length()) {
			throw error("Unexpected end of input");
		}
		return src.charAt(pos);
	}

	private void skipWhitespace() {
		while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
			pos++;
		}
	}

	private IllegalArgumentException error(String message) {
		return new IllegalArgumentException(message + " at offset " + pos);
	}
}
