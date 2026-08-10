package com.recsys.infrastructure.redis;

import io.lettuce.core.RedisURI;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Asserts that {@link StreamingRedisUri#from} authenticates on exactly the same terms as
 * {@link LettuceClientFactory#standaloneUri}, the method every service's Redis connection is
 * built from. Every expectation here is built by calling {@code standaloneUri} with the same
 * arguments — never a hand-written {@link RedisURI} — because the failure this guards against
 * is the job path and the service path drifting apart: a hand-written expectation would let
 * both sides drift together in the same edit and never notice.
 */
class StreamingRedisUriTest {

    private static final String HOST = "redis.internal";
    private static final int PORT = 6380;

    @Test
    void anonymousWhenBothBlank() {
        RedisURI expected = LettuceClientFactory.standaloneUri(HOST, PORT, "", "", false,
                LettuceClientFactory.DEFAULT_TIMEOUT_MS);
        RedisURI actual = StreamingRedisUri.from(HOST, PORT, "", "", false);
        assertEquals(expected, actual);
    }

    @Test
    void legacyAuthWhenOnlyPassword() {
        RedisURI expected = LettuceClientFactory.standaloneUri(HOST, PORT, "", "s3cret", false,
                LettuceClientFactory.DEFAULT_TIMEOUT_MS);
        RedisURI actual = StreamingRedisUri.from(HOST, PORT, "", "s3cret", false);
        assertEquals(expected, actual);
    }

    @Test
    void aclAuthWhenUsernameAndPassword() {
        RedisURI expected = LettuceClientFactory.standaloneUri(HOST, PORT, "streaming-job", "s3cret",
                false, LettuceClientFactory.DEFAULT_TIMEOUT_MS);
        RedisURI actual = StreamingRedisUri.from(HOST, PORT, "streaming-job", "s3cret", false);
        assertEquals(expected, actual);
    }

    @Test
    void usernameWithoutPasswordStillAcl() {
        RedisURI expected = LettuceClientFactory.standaloneUri(HOST, PORT, "streaming-job", "", false,
                LettuceClientFactory.DEFAULT_TIMEOUT_MS);
        RedisURI actual = StreamingRedisUri.from(HOST, PORT, "streaming-job", "", false);
        assertEquals(expected, actual);
    }

    @Test
    void tlsOnAndOff() {
        RedisURI expectedTlsOn = LettuceClientFactory.standaloneUri(HOST, PORT, "streaming-job",
                "s3cret", true, LettuceClientFactory.DEFAULT_TIMEOUT_MS);
        RedisURI actualTlsOn = StreamingRedisUri.from(HOST, PORT, "streaming-job", "s3cret", true);
        assertEquals(expectedTlsOn, actualTlsOn);

        RedisURI expectedTlsOff = LettuceClientFactory.standaloneUri(HOST, PORT, "streaming-job",
                "s3cret", false, LettuceClientFactory.DEFAULT_TIMEOUT_MS);
        RedisURI actualTlsOff = StreamingRedisUri.from(HOST, PORT, "streaming-job", "s3cret", false);
        assertEquals(expectedTlsOff, actualTlsOff);

        assertEquals(true, actualTlsOn.isSsl());
        assertEquals(false, actualTlsOff.isSsl());
    }

    @Test
    void blankAndNullAreTreatedAlike() {
        RedisURI blankUsernameBlankPassword = StreamingRedisUri.from(HOST, PORT, "", "", false);
        RedisURI nullUsernameNullPassword = StreamingRedisUri.from(HOST, PORT, null, null, false);
        assertEquals(blankUsernameBlankPassword, nullUsernameNullPassword);
        assertEquals(
                LettuceClientFactory.standaloneUri(HOST, PORT, "", "", false,
                        LettuceClientFactory.DEFAULT_TIMEOUT_MS),
                nullUsernameNullPassword);

        RedisURI blankUsernamePassword = StreamingRedisUri.from(HOST, PORT, "", "s3cret", false);
        RedisURI nullUsernamePassword = StreamingRedisUri.from(HOST, PORT, null, "s3cret", false);
        assertEquals(blankUsernamePassword, nullUsernamePassword);
        assertEquals(
                LettuceClientFactory.standaloneUri(HOST, PORT, "", "s3cret", false,
                        LettuceClientFactory.DEFAULT_TIMEOUT_MS),
                nullUsernamePassword);

        RedisURI usernameBlankPassword = StreamingRedisUri.from(HOST, PORT, "streaming-job", "", false);
        RedisURI usernameNullPassword = StreamingRedisUri.from(HOST, PORT, "streaming-job", null, false);
        assertEquals(usernameBlankPassword, usernameNullPassword);
        assertEquals(
                LettuceClientFactory.standaloneUri(HOST, PORT, "streaming-job", "", false,
                        LettuceClientFactory.DEFAULT_TIMEOUT_MS),
                usernameNullPassword);
    }
}
