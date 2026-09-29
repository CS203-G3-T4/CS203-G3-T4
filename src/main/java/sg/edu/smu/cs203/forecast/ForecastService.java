package sg.edu.smu.cs203.forecast;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import sg.edu.smu.cs203.market.price.*;
import static sg.edu.smu.cs203.forecast.ForecastTypes.*;

/** F4 must require actionable before recommending a shift; F5 supplies the shared Clock. */
@Service
public class ForecastService {
    private final ForecastRepository repository;
    private final MarketHistoryRepository history;
    private final IngestionRunRepository ingestion;
    private final Clock clock;
    private final Duration staleAfter;
    private final String mode;
    private final boolean mappingVerified;

    public ForecastService(ForecastRepository repository,MarketHistoryRepository history,IngestionRunRepository ingestion,
            Clock clock,@Value("${market.feed.stale-after}") Duration staleAfter,
            @Value("${forecast.mode:LIVE}") String mode,@Value("${forecast.period-mapping-evidence:}") String mappingEvidence) {
        if (!List.of("LIVE","REPLAY").contains(mode)) throw new IllegalArgumentException("Forecast mode must be LIVE or REPLAY");
        this.repository=repository; this.history=history; this.ingestion=ingestion; this.clock=clock;
        this.staleAfter=staleAfter; this.mode=mode; this.mappingVerified=!mappingEvidence.isBlank();
    }
    public String mode() { return mode; }
    public boolean mappingVerified() { return mappingVerified; }
    public boolean mappingVerified(List<MarketRevision> rows) {
        return mappingVerified && rows.stream().allMatch(r -> r.timeMapping().equals("EMC_PERIOD")
                || r.timeMapping().equals("UNVERIFIED_FLOOR")
                && BaselineForecastService.floor(r.sourceUpdatedAt()).equals(r.periodStart()));
    }
    public boolean stale(List<MarketRevision> rows,Instant asOf) {
        if (rows.isEmpty()) return true;
        Instant latest=rows.stream().map(MarketRevision::sourceUpdatedAt).max(Instant::compareTo).orElseThrow();
        if (latest.isAfter(asOf) || Duration.between(latest,asOf).compareTo(staleAfter)>0) return true;
        return mode.equals("LIVE") && ingestion.findLatest().map(r -> r.status()!=IngestionStatus.SUCCESS
                || r.finishedAt().isAfter(asOf) || Duration.between(r.finishedAt(),asOf).compareTo(staleAfter)>0).orElse(true);
    }
    public View latest() {
        Instant asOf=clock.instant();
        var saved=repository.latest(mode,asOf);
        if (saved.isEmpty()) return new View(false,false,true,mode,"NO_SAVED_FORECAST",null);
        Run run=saved.get();
        boolean oldTargets=run.points().isEmpty() || !run.points().getFirst().targetPeriod().isAfter(asOf);
        List<MarketRevision> rows=history.asOf(asOf);
        boolean stale=run.stale() || oldTargets || stale(rows,asOf);
        boolean available=run.status().equals("AVAILABLE") && run.points().size()==24;
        boolean verified=mappingVerified(rows) && !run.qualityFlags().contains("PERIOD_MAPPING_UNVERIFIED");
        String reason=!available ? run.fallbackReason() : stale ? "STALE_FORECAST" : !verified ? "PERIOD_MAPPING_UNVERIFIED" : null;
        return new View(available,available && !stale && verified,stale,mode,reason,run);
    }
}
