package com.disruption.companion.adapters.fixture;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

public final class FixtureReader {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private FixtureReader() {

    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(String path) {
        try (InputStream in = FixtureReader.class.getResourceAsStream("/fixtures/" + path)) {
            if (in == null) {
                throw new NotFoundException(path);
            }
            return MAPPER.readValue(in, Map.class);
        } catch (IOException e) {
            throw new IllegalStateException("can't read " + path, e);
        }
    }

    static String str(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    static Integer integer(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : ((Number) v).intValue();
    }

    static Boolean bool(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : (Boolean) v;
    }

    static BigDecimal decimal(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : new BigDecimal(v.toString());
    }

    static Instant instant(Map<String, Object> m, String key) {
        String v = str(m, key);
        return v == null ? null : Instant.parse(v);
    }

    static LocalDate date(Map<String, Object> m, String key) {
        String v = str(m, key);
        return v == null ? null : LocalDate.parse(v);
    }

    static LocalTime time(Map<String, Object> m, String key) {
        String v = str(m, key);
        return v == null ? null : LocalTime.parse(v);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? Map.of() : (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? List.of() : (List<Map<String, Object>>) v;
    }

    @SuppressWarnings("unchecked")
    static List<String> strings(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? List.of() : (List<String>) v;
    }

    static final class NotFoundException extends RuntimeException {
        NotFoundException(String path) {
            super("fixture not found: " + path);
        }
    }
}
