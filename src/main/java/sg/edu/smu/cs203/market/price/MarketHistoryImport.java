package sg.edu.smu.cs203.market.price;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Local operator bridge, deliberately not an HTTP upload endpoint. */
@Service
public class MarketHistoryImport {
    private final MarketHistoryRepository history;
    private final MarketPriceRepository prices;
    private final ObjectMapper mapper;
    private final Clock clock;
    public MarketHistoryImport(MarketHistoryRepository history,MarketPriceRepository prices,ObjectMapper mapper,Clock clock) {
        this.history=history; this.prices=prices; this.mapper=mapper; this.clock=clock;
    }
    @Transactional(rollbackFor=IOException.class)
    public int importFile(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path)>100_000_000)
            throw new IllegalArgumentException("Canonical import must be a regular JSONL file below 100 MB");
        int count=0;
        try (var reader=Files.newBufferedReader(path)) {
            String line;
            while ((line=reader.readLine())!=null) {
                if (line.length()>128_000) throw new IllegalArgumentException("Oversized canonical row");
                JsonNode row=mapper.readTree(line);
                MarketRevision r=new MarketRevision(0,required(row,"source"),time(row,"periodStart"),
                        time(row,"sourceUpdatedAt"),time(row,"availableAt"),number(row,"usep"),number(row,"demand"),
                        number(row,"vcp"),required(row,"priceStatus"),required(row,"timeMapping"));
                String external=row.path("externalId").asText(required(row,"revisionId"));
                String origin=required(row,"provenance");
                if (history.save(r,origin,external,line)) count++;
                // Final-price labels have no publication vintages. They never replace live F1 inputs.
                if (r.source().equals(PriceNormalizer.SOURCE) && r.priceStatus().equals("PROVISIONAL")
                        && r.demand()!=null && r.availableAt()!=null && !r.availableAt().isAfter(clock.instant())) {
                    prices.upsert(new MarketPrice(r.source(),r.periodStart(),r.sourceUpdatedAt(),r.availableAt(),r.usep(),r.demand(),r.vcp()));
                }
            }
        }
        return count;
    }
    private static String required(JsonNode row,String key) {
        if (!row.path(key).isString() || row.path(key).asText().isBlank()) throw new IllegalArgumentException("Missing "+key);
        return row.path(key).asText();
    }
    private static Instant time(JsonNode row,String key) {
        return row.path(key).isMissingNode() || row.path(key).isNull() ? null : OffsetDateTime.parse(row.path(key).asText()).toInstant();
    }
    private static BigDecimal number(JsonNode row,String key) {
        JsonNode value=row.path(key);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isNumber() || !Double.isFinite(value.asDouble())) throw new IllegalArgumentException("Non-finite "+key);
        return value.decimalValue();
    }
}
