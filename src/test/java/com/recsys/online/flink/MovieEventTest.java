package com.recsys.online.flink;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class MovieEventTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void parsesLegacyIntegerFields() throws Exception {
        String json = """
                {"eventId":"evt-001","userId":42,"movieId":7,
                 "eventType":"click","eventTimeMillis":1718400000000}
                """;
        MovieEvent e = MAPPER.readValue(json, MovieEvent.class);
        assertThat(e.userId).isEqualTo(42);
        assertThat(e.movieId).isEqualTo(7);
        assertThat(e.eventId).isEqualTo("evt-001");
        assertThat(e.eventTimeMillis).isEqualTo(1718400000000L);
    }

    @Test
    void parsesUnifiedStringFields() throws Exception {
        String json = """
                {"event_id":"uuid-abc","user_id":"user_42","item_id":"movie_7",
                 "event_type":"click","timestamp_ms":1718400001000,
                 "session_id":"sess_x"}
                """;
        MovieEvent e = MAPPER.readValue(json, MovieEvent.class);
        assertThat(e.userId).isEqualTo(42);
        assertThat(e.movieId).isEqualTo(7);
        assertThat(e.eventId).isEqualTo("uuid-abc");
        assertThat(e.eventTimeMillis).isEqualTo(1718400001000L);
        assertThat(e.sessionId()).isEqualTo("sess_x");
        assertThat(e.isClick()).isTrue();
    }

    @Test
    void parsesNonNumericItemIdViaHash() throws Exception {
        String json = """
                {"event_id":"uuid-xyz","user_id":"alice","item_id":"scarface",
                 "event_type":"view","timestamp_ms":1718400002000}
                """;
        MovieEvent e = MAPPER.readValue(json, MovieEvent.class);
        assertThat(e.userId).isGreaterThanOrEqualTo(0);
        assertThat(e.movieId).isGreaterThanOrEqualTo(0);
    }

    @Test
    void sessionIdFallsBackToFeaturesMap() throws Exception {
        String json = """
                {"eventId":"e1","userId":1,"movieId":1,"eventType":"click",
                 "eventTimeMillis":1000,"features":{"session_id":"sess_legacy"}}
                """;
        MovieEvent e = MAPPER.readValue(json, MovieEvent.class);
        assertThat(e.sessionId()).isEqualTo("sess_legacy");
    }
}
