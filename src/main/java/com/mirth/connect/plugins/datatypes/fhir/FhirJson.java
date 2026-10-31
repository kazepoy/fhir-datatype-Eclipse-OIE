/*
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
 * If a copy of the MPL was not distributed with this file, You can obtain one at
 * https://mozilla.org/MPL/2.0/.
 */

package com.mirth.connect.plugins.datatypes.fhir;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader that keeps what FHIR needs and general-purpose parsers lose: the order of the
 * properties and the exact text of numbers (a decimal 1.50 must stay 1.50). Objects become
 * LinkedHashMaps, arrays Lists, strings Strings, booleans Booleans, numbers {@link Number}s and null
 * {@link #NULL}. No dependencies, so it also runs in the Administrator.
 */
public final class FhirJson {

    /** JSON null, so a null in an array (FHIR uses them to align primitive extensions) is kept. */
    public static final Object NULL = new Object() {
        @Override
        public String toString() {
            return "null";
        }
    };

    /** A JSON number, as written. */
    static final class Number {
        final String text;

        Number(String text) {
            this.text = text;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private final String s;
    private int pos;

    private FhirJson(String s) {
        this.s = s;
    }

    public static Object parse(String json) {
        FhirJson reader = new FhirJson(json);
        reader.skipWhitespace();
        Object value = reader.value();
        reader.skipWhitespace();
        if (reader.pos < json.length()) {
            throw reader.error("unexpected text after the end of the JSON");
        }
        return value;
    }

    /** Writes a parsed value back as JSON, indented with two spaces; numbers keep their exact text. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value, int depth) {
        if (value instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            String sep = "\n";
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sb.append(sep);
                indent(sb, depth + 1);
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                write(sb, e.getValue(), depth + 1);
                sep = ",\n";
            }
            sb.append('\n');
            indent(sb, depth);
            sb.append('}');
        } else if (value instanceof List) {
            List<?> list = (List<?>) value;
            if (list.isEmpty()) {
                sb.append("[]");
                return;
            }
            sb.append('[');
            String sep = "\n";
            for (Object item : list) {
                sb.append(sep);
                indent(sb, depth + 1);
                write(sb, item, depth + 1);
                sep = ",\n";
            }
            sb.append('\n');
            indent(sb, depth);
            sb.append(']');
        } else if (value instanceof String) {
            writeString(sb, (String) value);
        } else {
            // Boolean, Number (its text as written) or NULL
            sb.append(value);
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
    }

    private Object value() {
        if (pos >= s.length()) {
            throw error("unexpected end of the JSON");
        }
        char c = s.charAt(pos);
        switch (c) {
            case '{':
                return object();
            case '[':
                return array();
            case '"':
                return string();
            case 't':
                literal("true");
                return Boolean.TRUE;
            case 'f':
                literal("false");
                return Boolean.FALSE;
            case 'n':
                literal("null");
                return NULL;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) {
                    return number();
                }
                throw error("unexpected character '" + c + "'");
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("expected a property name");
            }
            String key = string();
            if (map.containsKey(key)) {
                throw error("duplicate property \"" + key + "\"");
            }
            skipWhitespace();
            expect(':');
            skipWhitespace();
            map.put(key, value());
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw error("expected ',' or '}'");
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        pos++;
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(value());
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("expected ',' or ']'");
            }
        }
    }

    private String string() {
        pos++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= s.length()) {
                throw error("unterminated string");
            }
            char c = s.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                char e = next();
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > s.length()) {
                            throw error("bad \\u escape");
                        }
                        try {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("bad \\u escape");
                        }
                        pos += 4;
                        break;
                    default:
                        throw error("bad escape \\" + e);
                }
            } else if (c < 0x20) {
                throw error("control character in a string");
            } else {
                sb.append(c);
            }
        }
    }

    private Number number() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        digits();
        if (pos < s.length() && s.charAt(pos) == '.') {
            pos++;
            digits();
        }
        if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
            pos++;
            if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                pos++;
            }
            digits();
        }
        return new Number(s.substring(start, pos));
    }

    private void digits() {
        int start = pos;
        while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
            pos++;
        }
        if (pos == start) {
            throw error("bad number");
        }
    }

    private void literal(String word) {
        if (!s.startsWith(word, pos)) {
            throw error("unexpected text");
        }
        pos += word.length();
    }

    private void skipWhitespace() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '﻿') {
                pos++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (pos >= s.length()) {
            throw error("unexpected end of the JSON");
        }
        return s.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) {
            pos--;
            throw error("expected '" + c + "'");
        }
    }

    private IllegalArgumentException error(String message) {
        int line = 1;
        int column = 1;
        for (int i = 0; i < Math.min(pos, s.length()); i++) {
            if (s.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new IllegalArgumentException("Invalid JSON at line " + line + ", column " + column + ": " + message);
    }
}
