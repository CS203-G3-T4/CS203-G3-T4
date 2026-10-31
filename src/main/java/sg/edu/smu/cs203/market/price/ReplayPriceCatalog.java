package sg.edu.smu.cs203.market.price;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class ReplayPriceCatalog {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");

    public record Row(LocalDate day, java.time.Instant intervalStart, BigDecimal usep,
                      BigDecimal demand) {
    }

    private final Map<LocalDate, List<Row>> days;

    public ReplayPriceCatalog(
            @Value("${demo.replay.files:data/replay/2026-09-25_data(backup_day).csv,"
                    + "data/replay/2026-09-26_data(main_day).csv}") String fileList) {
        Map<LocalDate, List<Row>> loaded = new TreeMap<>();
        for (String file : fileList.split(",")) {
            loadFile(file.trim(), loaded);
        }
        if (loaded.isEmpty()) {
            throw new IllegalStateException("No demo replay price files were configured");
        }
        loaded.forEach((day, rows) -> {
            if (rows.size() != 48) {
                throw new IllegalStateException("Replay day " + day + " must contain 48 half-hour prices");
            }
            java.time.Instant expected = day.atStartOfDay(SINGAPORE).toInstant();
            for (int i = 0; i < rows.size(); i++) {
                if (!rows.get(i).intervalStart().equals(expected.plusSeconds(1800L * i))) {
                    throw new IllegalStateException(
                            "Replay day " + day + " must contain one continuous row per half-hour");
                }
            }
        });
        this.days = Map.copyOf(loaded);
    }

    public List<Row> day(LocalDate date) {
        List<Row> rows = days.get(date);
        if (rows == null) {
            throw new IllegalArgumentException("No replay prices are available for " + date);
        }
        return rows;
    }

    public Map<LocalDate, List<Row>> days() {
        return days;
    }

    private static void loadFile(String file, Map<LocalDate, List<Row>> days) {
        if (file.isBlank()) {
            throw new IllegalArgumentException("Replay price file paths must not be blank");
        }
        ClassPathResource resource = new ClassPathResource(file);
        try (var input = resource.getInputStream();
             var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
                throw new IllegalArgumentException("Replay price file is empty: " + file);
            }
            String[] columns = header.replace("\uFEFF", "").trim().split(",");
            int timeColumn = indexOf(columns, "first_collected_sgt");
            int priceColumn = indexOf(columns, "usep_sgd_per_mwh");
            int demandColumn = indexOf(columns, "demand_forecast_mw");
            if (timeColumn < 0 || priceColumn < 0 || demandColumn < 0) {
                throw new IllegalArgumentException("Replay price file has an invalid header: " + file);
            }
            Map<LocalDate, List<Row>> fileDays = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cells = line.split(",");
                if (cells.length <= Math.max(timeColumn, Math.max(priceColumn, demandColumn))) {
                    throw new IllegalArgumentException("Replay price row has missing columns in " + file);
                }
                var intervalStart = OffsetDateTime.parse(cells[timeColumn].trim())
                        .atZoneSameInstant(SINGAPORE);
                LocalDate day = intervalStart.toLocalDate();
                Row row = new Row(day, intervalStart.toInstant(),
                        new BigDecimal(cells[priceColumn].trim()),
                        new BigDecimal(cells[demandColumn].trim()));
                fileDays.computeIfAbsent(day, ignored -> new ArrayList<>()).add(row);
            }
            fileDays.forEach((day, rows) -> {
                if (days.putIfAbsent(day, rows.stream()
                        .sorted(java.util.Comparator.comparing(Row::intervalStart)).toList()) != null) {
                    throw new IllegalArgumentException("Multiple replay files contain " + day);
                }
            });
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load replay price file: " + file, exception);
        }
    }

    private static int indexOf(String[] columns, String name) {
        for (int i = 0; i < columns.length; i++) {
            if (columns[i].trim().equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
