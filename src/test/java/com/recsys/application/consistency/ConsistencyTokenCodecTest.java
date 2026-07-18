package com.recsys.application.consistency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConsistencyTokenCodecTest {
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("82fb7ddf-9e77-4cd9-91e4-8a34f13cc738");

    @Test void roundTripsSignedTokenWithBoundSubject() {
        var codec = new ConsistencyTokenCodec(SECRET, Clock.fixed(NOW, ZoneOffset.UTC));
        var token = new ConsistencyToken(EVENT_ID, 42, NOW, NOW.plus(Duration.ofHours(24)));

        assertThat(codec.decodeAndVerify(codec.encode(token))).isEqualTo(token);
    }

    @Test void rejectsTampering() {
        var codec = new ConsistencyTokenCodec(SECRET, Clock.fixed(NOW, ZoneOffset.UTC));
        String encoded = codec.encode(new ConsistencyToken(EVENT_ID, 42, NOW, NOW.plus(Duration.ofHours(24))));
        int index = encoded.length() / 2;
        String tampered = encoded.substring(0, index) + (encoded.charAt(index) == 'A' ? 'B' : 'A')
                + encoded.substring(index + 1);

        assertThatThrownBy(() -> codec.decodeAndVerify(tampered))
                .isInstanceOf(InvalidConsistencyTokenException.class);
    }

    @Test void rejectsExpiredToken() {
        var issuer = new ConsistencyTokenCodec(SECRET, Clock.fixed(NOW, ZoneOffset.UTC));
        String encoded = issuer.encode(new ConsistencyToken(EVENT_ID, 42, NOW, NOW.plus(Duration.ofHours(24))));
        var expiredCodec = new ConsistencyTokenCodec(SECRET,
                Clock.fixed(NOW.plus(Duration.ofHours(24)).plusMillis(1), ZoneOffset.UTC));

        assertThatThrownBy(() -> expiredCodec.decodeAndVerify(encoded))
                .isInstanceOf(ExpiredConsistencyTokenException.class);
    }

    @Test void rejectsTokensWhoseLifetimeIsNotExactlyTwentyFourHours() {
        var codec = new ConsistencyTokenCodec(SECRET, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> codec.encode(new ConsistencyToken(
                EVENT_ID, 42, NOW, NOW.plus(Duration.ofHours(25)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsMissingOrShortStartupSecret() {
        assertThatThrownBy(() -> new ConsistencyTokenCodec(null, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ConsistencyTokenCodec("short", Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
