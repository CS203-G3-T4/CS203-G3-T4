package sg.edu.smu.cs203.market.weather;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DailyWeatherTest {
    private final Instant now = Instant.parse("2026-09-23T08:00:00Z");
    private final DailyWeatherClient client = mock(DailyWeatherClient.class);
    private final DailyWeatherRepository repository = mock(DailyWeatherRepository.class);
    private final Clock clock = Clock.fixed(now, ZoneId.of("Asia/Singapore"));
    private final DailyWeatherService service = new DailyWeatherService(client, repository, clock,
            Duration.ofHours(6), new BigDecimal("33"));

    private JsonNode envelope() throws Exception {
        try (var input = getClass().getResourceAsStream("/weather/daily-forecast.json")) {
            return new ObjectMapper().readTree(input);
        }
    }

    private DailyWeatherForecast forecast() throws Exception {
        return DailyWeatherForecast.from(envelope().path("data").path("records").get(0), now);
    }

    @Test
    void clientReadsDocumentedRecordsEnvelopeAndCamelCaseFields() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://weather.test/daily"))
                .andRespond(withSuccess(envelope().toString(), MediaType.APPLICATION_JSON));
        var records = new DailyWeatherClient(builder.build(), "https://weather.test/daily").fetch();
        var forecast = DailyWeatherForecast.from(records.get(0), now);
        assertThat(forecast.temperatureHighC()).isEqualByComparingTo("34");
        assertThat(forecast.temperatureLowC()).isEqualByComparingTo("25");
        assertThat(forecast.forecast()).isEqualTo("Thundery Showers");
        assertThat(forecast.updatedAt()).isEqualTo(Instant.parse("2026-09-23T04:30:50Z"));
        server.verify();
    }

    @Test
    void clientRejectsTwoHourEnvelope() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://weather.test/daily"))
                .andRespond(withSuccess("{\"code\":0,\"data\":{\"items\":[{}]}}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> new DailyWeatherClient(builder.build(), "https://weather.test/daily").fetch())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validationRejectsMissingTemperatureBeforeWriting() throws Exception {
        var record = envelope().path("data").path("records").get(0).deepCopy();
        ((tools.jackson.databind.node.ObjectNode) record.path("general")).remove("temperature");
        assertThatThrownBy(() -> DailyWeatherForecast.from(record, now)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void freshForecastTriggersConfiguredHeatHint() throws Exception {
        when(repository.findByDate(LocalDate.of(2026, 9, 23))).thenReturn(Optional.of(forecast()));
        var response = service.today();
        assertThat(response.available()).isTrue();
        assertThat(response.stale()).isFalse();
        assertThat(response.heatHint()).isTrue();
    }

    @Test
    void outageKeepsSavedForecastButSuppressesHintAndRecovers() throws Exception {
        when(repository.findByDate(LocalDate.of(2026, 9, 23))).thenReturn(Optional.of(forecast()));
        when(client.fetch()).thenThrow(new IllegalStateException("upstream down"))
                .thenReturn(envelope().path("data").path("records"));
        service.poll();
        verifyNoInteractions(repository);
        assertThat(service.today().forecast()).isEqualTo(forecast());
        assertThat(service.today().stale()).isTrue();
        assertThat(service.today().heatHint()).isFalse();
        service.poll();
        assertThat(service.today().stale()).isFalse();
    }

    @Test
    void emptyDatabaseReturnsUnavailableWithoutThrowing() {
        when(repository.findByDate(any())).thenReturn(Optional.empty());
        assertThat(service.today().available()).isFalse();
        assertThat(service.today().forecast()).isNull();
        assertThat(service.today().heatHint()).isFalse();
    }

    @Test
    void todayUsesSingaporeDateAcrossUtcMidnightBoundary() {
        Clock earlyMorning = Clock.fixed(Instant.parse("2026-09-23T17:00:00Z"), ZoneId.of("Asia/Singapore"));
        when(repository.findByDate(any())).thenReturn(Optional.empty());
        new DailyWeatherService(client, repository, earlyMorning, Duration.ofHours(6), BigDecimal.valueOf(33)).today();
        verify(repository).findByDate(LocalDate.of(2026, 9, 24));
    }

    @Test
    void expiredForecastCannotTriggerHint() throws Exception {
        when(repository.findByDate(any())).thenReturn(Optional.of(forecast()));
        Clock later = Clock.fixed(Instant.parse("2026-09-24T04:00:00Z"), ZoneId.of("Asia/Singapore"));
        var response = new DailyWeatherService(client, repository, later, Duration.ofHours(6), BigDecimal.valueOf(33)).today();
        assertThat(response.stale()).isTrue();
        assertThat(response.heatHint()).isFalse();
    }
}
