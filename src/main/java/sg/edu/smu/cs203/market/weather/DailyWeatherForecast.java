package sg.edu.smu.cs203.market.weather;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import tools.jackson.databind.JsonNode;

public record DailyWeatherForecast(LocalDate date, Instant issuedAt, Instant updatedAt,
        Instant validStart, Instant validEnd, BigDecimal temperatureHighC,
        BigDecimal temperatureLowC, String forecast, Instant fetchedAt) {

    public static DailyWeatherForecast from(JsonNode record, Instant fetchedAt) {
        JsonNode general = record.path("general");
        JsonNode temperature = general.path("temperature");
        JsonNode text = general.path("forecast").path("text");
        if (!temperature.path("high").isNumber() || !temperature.path("low").isNumber()
                || !text.isString() || text.asText().isBlank()) {
            throw new IllegalArgumentException("NEA daily forecast is missing temperature or text");
        }
        Instant start = instant(general.path("validPeriod"), "start");
        Instant end = instant(general.path("validPeriod"), "end");
        BigDecimal high = temperature.path("high").decimalValue();
        BigDecimal low = temperature.path("low").decimalValue();
        if (!end.isAfter(start) || high.compareTo(low) < 0) {
            throw new IllegalArgumentException("NEA daily forecast has an invalid period or temperature range");
        }
        return new DailyWeatherForecast(LocalDate.parse(record.path("date").asText()),
                instant(record, "timestamp"), instant(record, "updatedTimestamp"),
                start, end, high, low, text.asText(), fetchedAt);
    }

    private static Instant instant(JsonNode node, String field) {
        return OffsetDateTime.parse(node.path(field).asText()).toInstant();
    }
}
