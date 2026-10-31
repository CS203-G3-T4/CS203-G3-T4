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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import sg.edu.smu.cs203.common.DemoClock;
import sg.edu.smu.cs203.market.price.ReplayPriceCatalog;

/**
 * A stand-in forecast for local runs and the midterm demo (CSDT4-19 allows a fake this sprint).
 *
 * It repeats the real half-hourly USEP of one replay day (by default F5's main day, 26 Sep 2026)
 * for the next 24 hours: the price forecast for 13:00 today is that day's 13:00 price. It is
 * always labelled DEMO_FORECAST so the page never presents it as a prediction.
 *
 * CSDT4-38: when spike thresholds are available, each half-hour is marked as a spike by F3's
 * rule, judged against the 28 days before the replay day (what was normal then), so the demo can
 * show "avoid this time" warnings. Without enough saved history, nothing is marked.
 */
public class DemoPriceForecastSource implements PriceForecastSource {

    static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    static final int HALF_HOURS_PER_DAY = 48;
    private static final long HALF_HOUR_SECONDS = 1800;

    private final Map<LocalTime, BigDecimal> curve;
    private final Map<LocalDate, Map<LocalTime, BigDecimal>> curves;
    private final String model;
    private final Clock clock;
    private final LocalDate replayDay;
    private final SpikeThresholds spikes;
    private final Map<LocalDate, Map<LocalTime, BigDecimal>> thresholds = new ConcurrentHashMap<>();

    public DemoPriceForecastSource(Map<LocalTime, BigDecimal> curve, String model, Clock clock) {
        this(curve, model, clock, null, null);
    }

    /** replayDay and spikes may be null; then no half-hour is marked as a spike. */
    public DemoPriceForecastSource(Map<LocalTime, BigDecimal> curve, String model, Clock clock,
                                   LocalDate replayDay, SpikeThresholds spikes) {
        if (curve.size() != HALF_HOURS_PER_DAY) {
            throw new IllegalArgumentException(
                    "The demo forecast needs 48 half-hour prices but has " + curve.size());
        }
        this.curve = Map.copyOf(curve);
        this.curves = replayDay == null ? Map.of() : Map.of(replayDay, this.curve);
        this.model = model;
        this.clock = clock;
        this.replayDay = replayDay;
        this.spikes = spikes;
    }

    private DemoPriceForecastSource(String model, Clock clock, LocalDate replayDay,
                                    SpikeThresholds spikes,
                                    Map<LocalDate, Map<LocalTime, BigDecimal>> curves) {
        this.curves = curves.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Map.copyOf(entry.getValue())));
        this.replayDay = replayDay;
        this.curve = Map.copyOf(curves.getOrDefault(replayDay, Map.of()));
        this.model = model;
        this.clock = clock;
        this.spikes = spikes;
        this.curves.forEach((date, prices) -> {
            if (prices.size() != HALF_HOURS_PER_DAY) {
                throw new IllegalArgumentException("Replay day " + date + " must have 48 half-hour prices");
            }
        });
    }

    public static DemoPriceForecastSource fromCatalog(ReplayPriceCatalog catalog, LocalDate defaultDay,
                                                       Clock clock, SpikeThresholds spikes) {
        Map<LocalDate, Map<LocalTime, BigDecimal>> curves = new TreeMap<>();
        catalog.days().forEach((date, rows) -> {
            Map<LocalTime, BigDecimal> prices = new TreeMap<>();
            rows.forEach(row -> prices.put(row.intervalStart().atZone(SINGAPORE).toLocalTime(), row.usep()));
            curves.put(date, prices);
        });
        if (!curves.containsKey(defaultDay)) {
            throw new IllegalArgumentException("Default replay day is not in the replay price catalog: " + defaultDay);
        }
        return new DemoPriceForecastSource("Replay price catalog", clock, defaultDay, spikes, curves);
    }

    /** Loads a replay-day CSV from the classpath, e.g. data/replay/2026-09-26_data(main_day).csv. */
    public static DemoPriceForecastSource fromClasspath(String path, Clock clock) {
        return fromClasspath(path, clock, null);
    }

    /** As above, marking spikes with the given thresholds (null: no spikes). */
    public static DemoPriceForecastSource fromClasspath(String path, Clock clock, SpikeThresholds spikes) {
        InputStream stream = DemoPriceForecastSource.class.getResourceAsStream("/" + path);
        if (stream == null) {
            throw new IllegalStateException("Demo forecast file not found on the classpath: " + path);
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            Map<LocalDate, Map<LocalTime, BigDecimal>> days = parseDays(reader);
            if (days.size() != 1) {
                throw new IllegalArgumentException("The demo forecast file must hold exactly one day, not "
                        + days.size());
            }
            Map.Entry<LocalDate, Map<LocalTime, BigDecimal>> day = days.entrySet().iterator().next();
            return new DemoPriceForecastSource(day.getValue(), "Replay day " + fileName, clock,
                    day.getKey(), spikes);
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not read the demo forecast file " + path, exception);
        }
    }

    /**
     * Reads the replay CSV (first_collected_sgt, usep_sgd_per_mwh, ...) into time of day -> price.
     * The replay files start with a UTF-8 byte-order mark, which is skipped here.
     */
    static Map<LocalTime, BigDecimal> parse(Reader source) throws IOException {
        Map<LocalTime, BigDecimal> curve = new TreeMap<>();
        parseDays(source).values().forEach(curve::putAll);
        return curve;
    }

    /** As parse, grouped by the Singapore date of each row. */
    static Map<LocalDate, Map<LocalTime, BigDecimal>> parseDays(Reader source) throws IOException {
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
        Map<LocalDate, Map<LocalTime, BigDecimal>> days = new TreeMap<>();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            String[] cells = line.split(",");
            ZonedDateTime at = OffsetDateTime.parse(cells[timeColumn].trim()).atZoneSameInstant(SINGAPORE);
            days.computeIfAbsent(at.toLocalDate(), d -> new TreeMap<>())
                    .put(at.toLocalTime(), new BigDecimal(cells[priceColumn].trim()));
        }
        return days;
    }

    @Override
    public Lookup forecast() {
        Instant asOf = clock.instant();
        boolean runningReplay = clock instanceof DemoClock demoClock && demoClock.isRunning();
        LocalDate forecastDay = runningReplay ? asOf.atZone(SINGAPORE).toLocalDate() : replayDay;
        Map<LocalTime, BigDecimal> forecastCurve = forecastDay == null
                ? curve : curves.getOrDefault(forecastDay, curve);
        if (runningReplay && !curves.containsKey(forecastDay)) {
            return Lookup.unavailable("No replay forecast is available for " + forecastDay);
        }
        // Forecasts start at the next half-hour boundary, like F3's 24 targets.
        long currentPeriod = Math.floorDiv(asOf.getEpochSecond(), HALF_HOUR_SECONDS) * HALF_HOUR_SECONDS;
        Map<LocalTime, BigDecimal> spikeThresholds = spikeThresholds(forecastDay, forecastCurve);
        List<PriceForecast.HalfHourPrice> points = new ArrayList<>();
        for (int i = 1; i <= HALF_HOURS_PER_DAY; i++) {
            Instant start = Instant.ofEpochSecond(currentPeriod + i * HALF_HOUR_SECONDS);
            BigDecimal price = forecastCurve.get(start.atZone(SINGAPORE).toLocalTime());
            if (price == null) {
                return Lookup.unavailable("The demo forecast has no price for "
                        + start.atZone(SINGAPORE).toLocalTime());
            }
            BigDecimal threshold = spikeThresholds.get(start.atZone(SINGAPORE).toLocalTime());
            boolean spike = threshold != null && price.compareTo(threshold) > 0;
            points.add(new PriceForecast.HalfHourPrice(start, price, spike));
        }
        return Lookup.of(new PriceForecast(PriceForecast.DEMO,
                runningReplay ? "Replay day " + forecastDay : model, asOf, points, forecastDay));
    }

    /**
     * Thresholds for the replay day's half-hours, judged on history before that day started (as
     * if F3 had forecast that day at midnight). Kept once found; retried while history is missing.
     */
    private Map<LocalTime, BigDecimal> spikeThresholds(LocalDate day, Map<LocalTime, BigDecimal> dayCurve) {
        if (spikes == null || day == null) {
            return Map.of();
        }
        Map<LocalTime, BigDecimal> known = thresholds.get(day);
        if (known != null) {
            return known;
        }
        Instant dayStart = day.atStartOfDay(SINGAPORE).toInstant();
        List<Instant> targets = new ArrayList<>();
        for (LocalTime time : dayCurve.keySet()) {
            targets.add(ZonedDateTime.of(day, time, SINGAPORE).toInstant());
        }
        Map<LocalTime, BigDecimal> found = new TreeMap<>();
        spikes.thresholds(targets, dayStart).forEach((target, threshold) ->
                found.put(target.atZone(SINGAPORE).toLocalTime(), threshold));
        if (!found.isEmpty()) {
            thresholds.put(day, Map.copyOf(found));
        }
        return found;
    }
}
