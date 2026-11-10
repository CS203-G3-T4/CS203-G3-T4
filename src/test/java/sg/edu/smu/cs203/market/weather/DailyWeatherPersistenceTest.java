package sg.edu.smu.cs203.market.weather;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import sg.edu.smu.cs203.market.EnergyMarketServiceApplication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/** NEA's real issue timing: a new issue at 17:40 is valid from 18:00 and covers the hours after midnight. */
@EnabledIfEnvironmentVariable(named = "WATTLY_TEST_DB_URL", matches = ".+")
@SpringBootTest(classes = {EnergyMarketServiceApplication.class, DailyWeatherPersistenceTest.Time.class},
        properties = {"market.feed.enabled=false", "weather.daily.enabled=false"})
class DailyWeatherPersistenceTest {
    static final String SCHEMA = "daily_weather_" + UUID.randomUUID().toString().replace("-", "");
    static final Instant MORNING_FETCH = sgt("2026-11-10T06:05:00");
    static final Instant EVENING_FETCH = sgt("2026-11-10T17:45:00");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", () -> System.getenv("WATTLY_TEST_DB_URL"));
        p.add("spring.datasource.username", () -> System.getenv().getOrDefault("WATTLY_TEST_DB_USER", "wattly_test"));
        p.add("spring.datasource.password", () -> System.getenv().getOrDefault("WATTLY_TEST_DB_PASSWORD", ""));
        p.add("spring.flyway.default-schema", () -> SCHEMA);
        p.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
    }

    @TestConfiguration
    static class Time {
        @Bean @Primary Clock testClock() { return Clock.fixed(EVENING_FETCH, ZoneId.of("Asia/Singapore")); }
    }

    @Autowired DailyWeatherRepository repository;

    @Test
    void choosesTheIssueCoveringEachInstantNotTheOneDatedToday() throws Exception {
        repository.save(records(issue("05:40:00", "2026-11-10T06:00:00", "2026-11-10T18:00:00", "Partly Cloudy")),
                MORNING_FETCH);
        repository.save(records(issue("17:40:59", "2026-11-10T18:00:00", "2026-11-11T18:00:00", "Thundery Showers")),
                EVENING_FETCH);

        // 17:50: the newer issue is saved but starts at 18:00, so the morning issue still applies.
        var beforeSix = repository.findCovering(sgt("2026-11-10T17:50:00")).orElseThrow();
        assertThat(beforeSix.forecast()).isEqualTo("Partly Cloudy");
        assertThat(beforeSix.fetchedAt()).isEqualTo(EVENING_FETCH);

        assertThat(repository.findCovering(sgt("2026-11-10T18:30:00")).orElseThrow().forecast())
                .isEqualTo("Thundery Showers");

        // 01:00 the next day: nothing is dated 11 Nov, but the evening issue covers until 18:00.
        var afterMidnight = repository.findCovering(sgt("2026-11-11T01:00:00")).orElseThrow();
        assertThat(afterMidnight.forecast()).isEqualTo("Thundery Showers");
        assertThat(repository.findByDate(java.time.LocalDate.of(2026, 11, 11))).isEmpty();

        // An issue cannot be shown before it was fetched (also keeps demo replays from seeing the future).
        assertThat(repository.findCovering(sgt("2026-11-10T06:02:00"))).isEmpty();
    }

    private static JsonNode records(String record) throws Exception {
        return new ObjectMapper().readTree("[" + record + "]");
    }

    private static String issue(String updated, String start, String end, String text) {
        return """
                {"date":"2026-11-10","timestamp":"2026-11-10T%s+08:00","updatedTimestamp":"2026-11-10T%s+08:00",
                 "general":{"validPeriod":{"start":"%s+08:00","end":"%s+08:00"},
                            "temperature":{"high":33,"low":24},"forecast":{"code":"TL","text":"%s"}},
                 "periods":[]}
                """.formatted(updated, updated, start, end, text);
    }

    private static Instant sgt(String local) {
        return OffsetDateTime.parse(local + "+08:00").toInstant();
    }
}
