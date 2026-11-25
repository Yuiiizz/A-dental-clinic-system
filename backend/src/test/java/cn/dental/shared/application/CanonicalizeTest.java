package cn.dental.shared.application;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CanonicalizeTest {
    private static String c(Map<String, ?> values) {
        return IdempotencyService.canonicalize(values);
    }

    @Test
    void fieldOrderIsIndependentOfInsertionOrder() {
        assertEquals("{\"a\":\"1\",\"b\":\"2\"}", c(Map.of("b", "2", "a", "1")));
        assertEquals(c(Map.of("b", "2", "a", "1")), c(Map.of("a", "1", "b", "2")));
    }

    @Test
    void nestedMapKeysAreSorted() {
        assertEquals("{\"outer\":{\"a\":\"2\",\"z\":\"1\"}}", c(Map.of("outer", Map.of("z", "1", "a", "2"))));
    }

    @Test
    void arrayOrderIsPreserved() {
        assertEquals("{\"arr\":[\"x\",\"y\"]}", c(Map.of("arr", List.of("x", "y"))));
        assertNotEquals(c(Map.of("arr", List.of("x", "y"))), c(Map.of("arr", List.of("y", "x"))));
        assertEquals("{\"arr\":[[\"a\",\"b\"],\"c\"]}", c(Map.of("arr", List.of(List.of("a", "b"), "c"))));
    }

    @Test
    void nullValueIsDistinctFromMissing() {
        Map<String, Object> withNull = new HashMap<>();
        withNull.put("a", null);
        assertEquals("{\"a\":null}", c(withNull));
        assertNotEquals(c(withNull), c(Map.of()));
        assertEquals("{}", c(Map.of()));
    }

    @Test
    void stringAndNumberAreDistinct() {
        assertEquals("{\"v\":\"1\"}", c(Map.of("v", "1")));
        assertEquals("{\"v\":1}", c(Map.of("v", 1)));
        assertNotEquals(c(Map.of("v", "1")), c(Map.of("v", 1)));
    }

    @Test
    void supportedNumbersCanonicalize() {
        assertEquals("{\"v\":42}", c(Map.of("v", 42)));
        assertEquals("{\"v\":42}", c(Map.of("v", 42L)));
        assertEquals("{\"v\":123}", c(Map.of("v", new BigInteger("123"))));
        assertEquals("{\"v\":1.5}", c(Map.of("v", new BigDecimal("1.50"))));
        assertEquals("{\"v\":0}", c(Map.of("v", new BigDecimal("0.00"))));
        assertEquals("{\"v\":10}", c(Map.of("v", new BigDecimal("10.00"))));
        assertEquals("{\"v\":0.1}", c(Map.of("v", new BigDecimal("0.1"))));
        assertEquals("{\"v\":true}", c(Map.of("v", true)));
    }

    @Test
    void nonStringKeysAreRejected() {
        Map<Object, Object> m = new HashMap<>();
        m.put(1, "x");
        assertThrows(IllegalArgumentException.class, () -> IdempotencyService.canonicalize((Map) m));

        Map<Object, Object> inner = new HashMap<>();
        inner.put(1, "x");
        Map<String, Object> outer = Map.of("inner", inner);
        assertThrows(IllegalArgumentException.class, () -> IdempotencyService.canonicalize(outer));
    }

    @Test
    void unsupportedAndNonFiniteNumbersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> c(Map.of("v", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> c(Map.of("v", Double.POSITIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class, () -> c(Map.of("v", 1.5d)));
        assertThrows(IllegalArgumentException.class, () -> c(Map.of("v", 1.5f)));
    }

    @Test
    void nullInputIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> IdempotencyService.canonicalize(null));
    }
}
