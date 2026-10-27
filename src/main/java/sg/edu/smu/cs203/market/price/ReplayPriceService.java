package sg.edu.smu.cs203.market.price;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class ReplayPriceService {

    private static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private final ReplayPriceCatalog catalog;
    private final ReplayPriceRepository repository;

    public ReplayPriceService(ReplayPriceCatalog catalog, ReplayPriceRepository repository) {
        this.catalog = catalog;
        this.repository = repository;
    }

    public void loadDay(LocalDate day) {
        repository.replaceDay(day, catalog.day(day));
    }

    public List<ReplayPriceCatalog.Row> catalogDay(LocalDate day) {
        return catalog.day(day);
    }

    public Optional<MarketPrice> latest(Instant asOf) {
        return repository.findLatest(asOf);
    }

    public List<MarketPrice> history(Instant from, Instant to, Instant asOf) {
        return repository.findRange(from, to, asOf);
    }

    public List<MarketPrice> today(Instant asOf) {
        Instant start = asOf.atZone(SINGAPORE).toLocalDate().atStartOfDay(SINGAPORE).toInstant();
        return repository.findRange(start, asOf.plusNanos(1), asOf);
    }
}
