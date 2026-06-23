package com.recsys.infrastructure.cache;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

class LlmResponseCacheTest {

    private static final byte[] BODY_A = body("{\"prompt\":\"hello\"}");
    private static final byte[] BODY_B = body("{\"prompt\":\"world\"}");
    private static final byte[] RESPONSE = body("{\"result\":\"ok\"}");
    private static final Map<String, List<String>> HEADERS =
            Map.of("content-type", List.of("application/json"));

    @Test
    void disabledCacheNeverHits() {
        LlmResponseCache cache = LlmResponseCache.disabled();
        cache.put(BODY_A, 200, HEADERS, RESPONSE);
        assertNull(cache.get(BODY_A));
    }

    @Test
    void hitOnSameRequestBody() {
        LlmResponseCache cache = new LlmResponseCache(100, 60_000L);
        cache.put(BODY_A, 200, HEADERS, RESPONSE);

        LlmResponseCache.Entry hit = cache.get(BODY_A);
        assertNotNull(hit);
        assertEquals(200, hit.status());
        assertArrayEquals(RESPONSE, hit.body());
    }

    @Test
    void missOnDifferentRequestBody() {
        LlmResponseCache cache = new LlmResponseCache(100, 60_000L);
        cache.put(BODY_A, 200, HEADERS, RESPONSE);

        assertNull(cache.get(BODY_B));
    }

    @Test
    void expiredEntryIsEvicted() throws InterruptedException {
        // TTL of 50 ms so the test doesn't take long
        LlmResponseCache cache = new LlmResponseCache(100, 50L);
        cache.put(BODY_A, 200, HEADERS, RESPONSE);

        Thread.sleep(100);
        assertNull(cache.get(BODY_A), "entry should have expired");
    }

    @Test
    void lruEvictionWhenFull() {
        LlmResponseCache cache = new LlmResponseCache(2, 60_000L);
        byte[] req1 = body("{\"p\":\"1\"}");
        byte[] req2 = body("{\"p\":\"2\"}");
        byte[] req3 = body("{\"p\":\"3\"}");

        cache.put(req1, 200, HEADERS, RESPONSE);
        cache.put(req2, 200, HEADERS, RESPONSE);
        // Access req1 to make it recently used
        cache.get(req1);
        // req3 insertion should evict req2 (least recently used)
        cache.put(req3, 200, HEADERS, RESPONSE);

        assertNotNull(cache.get(req1), "req1 should survive LRU eviction");
        assertNull(cache.get(req2), "req2 should have been evicted");
        assertNotNull(cache.get(req3), "req3 should be present");
    }

    @Test
    void storedBodyIsCopied() {
        LlmResponseCache cache = new LlmResponseCache(10, 60_000L);
        byte[] mutableBody = RESPONSE.clone();
        cache.put(BODY_A, 200, HEADERS, mutableBody);
        mutableBody[0] = 0; // mutate original

        LlmResponseCache.Entry hit = cache.get(BODY_A);
        assertNotNull(hit);
        assertNotSame(mutableBody, hit.body());
        assertEquals(RESPONSE[0], hit.body()[0], "cached body should not reflect post-put mutation");
    }

    private static byte[] body(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
