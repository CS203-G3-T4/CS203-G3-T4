package sg.edu.smu.cs203.recommendation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A stand-in forecast for local runs and the midterm demo (CSDT4-19 allows a fake this sprint).
 *
 * It repeats the real half-hourly USEP of one replay day (by default F5's main day, 26 Sep 2026)
 * for the next 24 hours: the price forecast for 13:00 today is that day's 13:00 price. It is
 * always labelled DEMO_FORECAST so the page never presents it as a prediction.
 */
public class DemoPriceForecastSource implements PriceForecastSource {

    static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    static final int HALF_HOURS_PER_DAY = 48;
    private static final long HALF_HOUR_SECONDS = 1800;

    private final Map<LocalTime, BigDecimal> curve;
    private final String model;
    private final Clock clock;

    public DemoPriceForecastSource(Map<LocalTime, BigDecimal> curve, String model, Clock clock) {
        if (curve.size() != HALF_HOURS_PER_DAY) {
            throw new IllegalArgumentException(
                    "The demo forecast needs 48 half-hour prices but has " + curve.size());
        }
        this.curve = Map.copyOf(curve);
        this.model = model;
        this.clock = clock;
    }

    /** Loads a replay-day CSV from the classpath, e.g. data/replay/2026-09-26_data(main_day).csv. */
    public static DemoPriceForecastSource fromClasspath(String path, Clock clock) {
        InputStream stream = DemoPriceForecastSource.class.getResourceAsStream("/" + path);
        if (stream == null) {
            throw new IllegalStateException("Demo forecast file not found on the classpath: " + path);
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            return new DemoPriceForecastSource(parse(reader), "Replay day " + fileName, clock);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read the demo forecast file " + path, exception);
        }
    }

    /**
     * Reads the replay CSV (first_collected_sgt, usep_sgd_per_mwh, ...) into time of day -> price.
     * The replay files start with a UTF-8 byte-order mark, which is skipped here.
     */
    static Map<LocalTime, BigDecimal> parse(Reader source) throws IOException {
        BufferedReader reader = new BufferedReader(source);
        String header = reader.readLine();
        if (header == null) {
            throw new IllegalArgumentException("The demo forecast file is empty");
        }
        header = header.replace("\uFEFF", "").trim();
        List<String> columns = List.of(header.split(","));
        int timeColumn = columns.indexOf("first_collected_sgt");
        int priceColumn = columns.indexOf("usep_sgd_per_mwh");
        if (timeColumn < 0 || priceColumn < 0) {
            throw new IllegalArgumentException(
                    "The demo forecast file needs first_collected_sgt and usep_sgd_per_mwh columns");
        }
        Map<LocalTime, BigDecimal> curve = new TreeMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            String[] cells = line.split(",");
            LocalTime time = OffsetDateTime.parse(cells[timeColumn].trim())
                    .atZoneSameInstant(SINGAPORE).toLocalTime();
            curve.put(time, new BigDecimal(cells[priceColumn].trim()));
        }
        return curve;
    }

    @Override
    public Lookup forecast() {
        Instant asOf = clock.instant();
        // Forecasts start at the next half-hour boundary, like F3's 24 targets.
        long currentPeriod = Math.floorDiv(asOf.getEpochSecond(), HALF_HOUR_SECONDS) * HALF_HOUR_SECONDS;
        List<PriceForecast.HalfHourPrice> points = new ArrayList<>();
        for (int i = 1; i <= HALF_HOURS_PER_DAY; i++) {
            Instant start = Instant.ofEpochSecond(currentPeriod + i * HALF_HOUR_SECONDS);
            BigDecimal price = curve.get(start.atZone(SINGAPORE).toLocalTime());
            if (price == null) {
                return Lookup.unavailable("The demo forecast has no price for "
                        + start.atZone(SINGAPORE).toLocalTime());
            }
            points.add(new PriceForecast.HalfHourPrice(start, price));
        }
        return Lookup.of(new PriceForecast(PriceForecast.DEMO, model, asOf, points));
    }
}
