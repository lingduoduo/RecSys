package com.recsys.application.gateway;

import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CognitoJwtVerifierJwksTest {

    private static PublicKey rsaKey() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair().getPublic();
    }

    @Test
    void servesStaleKeyWhenFetchFailsAfterExpiry() throws Exception {
        PublicKey k = rsaKey();
        AtomicLong now = new AtomicLong(0L);
        AtomicBoolean down = new AtomicBoolean(false);
        AtomicInteger fetches = new AtomicInteger(0);
        CognitoJwtVerifier.KeyFetcher fetcher = () -> {
            fetches.incrementAndGet();
            if (down.get()) throw new CognitoJwtVerifier.JwtAuthException(503, "JWKS down");
            return Map.of("kid-1", k);
        };
        CognitoJwtVerifier.HttpJwkProvider p =
                new CognitoJwtVerifier.HttpJwkProvider(fetcher, now::get);

        assertSame(k, p.key("kid-1"));                       // fetch #1 populates cache
        assertEquals(1, fetches.get());

        now.addAndGet(Duration.ofMinutes(6).toMillis());     // past the 5-min TTL
        down.set(true);                                      // JWKS outage begins

        assertSame(k, p.key("kid-1"));                       // SERVE STALE: still returns cached key
        assertEquals(2, fetches.get());                      // it attempted one (failed) refetch
    }

    @Test
    void rejectsUnknownKidWhenFetchFailsAndNothingCached() {
        AtomicLong now = new AtomicLong(0L);
        CognitoJwtVerifier.KeyFetcher fetcher = () -> {
            throw new CognitoJwtVerifier.JwtAuthException(503, "JWKS down");
        };
        CognitoJwtVerifier.HttpJwkProvider p =
                new CognitoJwtVerifier.HttpJwkProvider(fetcher, now::get);

        assertThrows(CognitoJwtVerifier.JwtAuthException.class, () -> p.key("kid-x"));
    }

    @Test
    void backoffLimitsRefetchDuringOutage() throws Exception {
        PublicKey k = rsaKey();
        AtomicLong now = new AtomicLong(0L);
        AtomicBoolean down = new AtomicBoolean(false);
        AtomicInteger fetches = new AtomicInteger(0);
        CognitoJwtVerifier.KeyFetcher fetcher = () -> {
            fetches.incrementAndGet();
            if (down.get()) throw new CognitoJwtVerifier.JwtAuthException(503, "down");
            return Map.of("kid-1", k);
        };
        CognitoJwtVerifier.HttpJwkProvider p =
                new CognitoJwtVerifier.HttpJwkProvider(fetcher, now::get);

        assertSame(k, p.key("kid-1"));                       // fetch #1
        now.addAndGet(Duration.ofMinutes(6).toMillis());
        down.set(true);
        assertSame(k, p.key("kid-1"));                       // fetch #2 (fails) -> stale + 30s backoff
        assertEquals(2, fetches.get());

        now.addAndGet(Duration.ofSeconds(10).toMillis());    // within the 30s backoff window
        assertSame(k, p.key("kid-1"));                       // served stale WITHOUT refetch
        assertEquals(2, fetches.get());

        now.addAndGet(Duration.ofSeconds(25).toMillis());    // now past the 30s backoff
        assertSame(k, p.key("kid-1"));                       // refetch attempted again
        assertEquals(3, fetches.get());
    }

    @Test
    void servesFromCacheWithinTtlWithoutRefetch() throws Exception {
        PublicKey k = rsaKey();
        AtomicLong now = new AtomicLong(0L);
        AtomicInteger fetches = new AtomicInteger(0);
        CognitoJwtVerifier.KeyFetcher fetcher = () -> {
            fetches.incrementAndGet();
            return Map.of("kid-1", k);
        };
        CognitoJwtVerifier.HttpJwkProvider p =
                new CognitoJwtVerifier.HttpJwkProvider(fetcher, now::get);

        assertSame(k, p.key("kid-1"));                       // fetch #1
        now.addAndGet(Duration.ofMinutes(1).toMillis());     // within the 5-min TTL
        assertSame(k, p.key("kid-1"));                       // served from cache
        assertEquals(1, fetches.get());                      // no refetch
    }
}
