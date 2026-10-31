package org.recompile.castle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Minimal JSON reader/writer for the room server's small messages. */
final class Json
{
	private final String s;
	private int i;

	private Json(String s) { this.s = s; }

	static Object parse(String text)
	{
		Json j = new Json(text);
		Object v = j.value();
		j.ws();
		if (j.i != j.s.length()) { throw new IllegalArgumentException("trailing data"); }
		return v;
	}

	@SuppressWarnings("unchecked")
	static Map<String, Object> obj(String text)
	{
		Object v = parse(text);
		if (!(v instanceof Map)) { throw new IllegalArgumentException("not an object"); }
		return (Map<String, Object>) v;
	}

	static String str(Map<String, Object> m, String k) { Object v = m.get(k); return v instanceof String ? (String) v : null; }

	static int num(Map<String, Object> m, String k, int def) { Object v = m.get(k); return v instanceof Number ? ((Number) v).intValue() : def; }

	static boolean bool(Map<String, Object> m, String k) { return Boolean.TRUE.equals(m.get(k)); }

	@SuppressWarnings("unchecked")
	static Map<String, Object> sub(Map<String, Object> m, String k) { Object v = m.get(k); return v instanceof Map ? (Map<String, Object>) v : null; }

	/** Builds an object from key, value pairs. Values: String, Number, Boolean, null. */
	static String of(Object... kv)
	{
		StringBuilder b = new StringBuilder("{");
		for (int k = 0; k < kv.length; k += 2)
		{
			if (k > 0) { b.append(','); }
			quote(b, (String) kv[k]);
			b.append(':');
			Object v = kv[k + 1];
			if (v == null) { b.append("null"); }
			else if (v instanceof String) { quote(b, (String) v); }
			else { b.append(v); }
		}
		return b.append('}').toString();
	}

	private static void quote(StringBuilder b, String v)
	{
		b.append('"');
		for (int k = 0; k < v.length(); k++)
		{
			char c = v.charAt(k);
			switch (c)
			{
				case '"': b.append("\\\""); break;
				case '\\': b.append("\\\\"); break;
				case '\n': b.append("\\n"); break;
				case '\r': b.append("\\r"); break;
				case '\t': b.append("\\t"); break;
				default:
					if (c < 0x20) { b.append(String.format("\\u%04x", (int) c)); }
					else { b.append(c); }
			}
		}
		b.append('"');
	}

	// ------------------------------------------------------------ parser

	private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) { i++; } }

	private Object value()
	{
		ws();
		if (i >= s.length()) { throw new IllegalArgumentException("unexpected end"); }
		char c = s.charAt(i);
		if (c == '{') { return object(); }
		if (c == '[') { return array(); }
		if (c == '"') { return string(); }
		if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
		if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
		if (s.startsWith("null", i)) { i += 4; return null; }
		return number();
	}

	private Map<String, Object> object()
	{
		Map<String, Object> m = new LinkedHashMap<>();
		i++;
		ws();
		if (peek() == '}') { i++; return m; }
		for (;;)
		{
			ws();
			String k = string();
			ws();
			expect(':');
			m.put(k, value());
			ws();
			if (peek() == ',') { i++; continue; }
			expect('}');
			return m;
		}
	}

	private List<Object> array()
	{
		List<Object> a = new ArrayList<>();
		i++;
		ws();
		if (peek() == ']') { i++; return a; }
		for (;;)
		{
			a.add(value());
			ws();
			if (peek() == ',') { i++; continue; }
			expect(']');
			return a;
		}
	}

	private String string()
	{
		expect('"');
		StringBuilder b = new StringBuilder();
		while (i < s.length())
		{
			char c = s.charAt(i++);
			if (c == '"') { return b.toString(); }
			if (c != '\\') { b.append(c); continue; }
			char e = s.charAt(i++);
			switch (e)
			{
				case 'n': b.append('\n'); break;
				case 'r': b.append('\r'); break;
				case 't': b.append('\t'); break;
				case 'b': b.append('\b'); break;
				case 'f': b.append('\f'); break;
				case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
				default: b.append(e);
			}
		}
		throw new IllegalArgumentException("unterminated string");
	}

	private Number number()
	{
		int start = i;
		while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) { i++; }
		String t = s.substring(start, i);
		if (t.isEmpty()) { throw new IllegalArgumentException("bad value at " + start); }
		if (t.contains(".") || t.contains("e") || t.contains("E")) { return Double.parseDouble(t); }
		long l = Long.parseLong(t);
		return (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) ? (Number) (int) l : (Number) l;
	}

	private char peek() { return i < s.length() ? s.charAt(i) : '\0'; }

	private void expect(char c)
	{
		if (peek() != c) { throw new IllegalArgumentException("expected '" + c + "' at " + i); }
		i++;
	}
}
