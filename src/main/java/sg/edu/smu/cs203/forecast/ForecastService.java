package sg.edu.smu.cs203.forecast;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import sg.edu.smu.cs203.market.price.*;
import static sg.edu.smu.cs203.forecast.ForecastTypes.*;
import sg.edu.smu.cs203.common.DemoClock;
import sg.edu.smu.cs203.market.price.ReplayPriceService;

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
    private ReplayPriceService replayPrices;

    public ForecastService(ForecastRepository repository,MarketHistoryRepository history,IngestionRunRepository ingestion,
            Clock clock,@Value("${market.feed.stale-after}") Duration staleAfter,
            @Value("${forecast.mode:LIVE}") String mode,@Value("${forecast.period-mapping-evidence:}") String mappingEvidence) {
        if (!List.of("LIVE","REPLAY").contains(mode)) throw new IllegalArgumentException("Forecast mode must be LIVE or REPLAY");
        this.repository=repository; this.history=history; this.ingestion=ingestion; this.clock=clock;
        this.staleAfter=staleAfter; this.mode=mode; this.mappingVerified=!mappingEvidence.isBlank();
    }
    @Autowired(required = false)
    public void setReplayPrices(ReplayPriceService replayPrices) {
        this.replayPrices = replayPrices;
    }
    public String mode() { return mode; }
    public java.util.Map<String,Object> today() {
        Instant now=clock.instant();
        Instant start=now.atZone(java.time.ZoneId.of("Asia/Singapore")).toLocalDate().atStartOfDay(java.time.ZoneId.of("Asia/Singapore")).toInstant();
        if (clock instanceof DemoClock demoClock && demoClock.isRunning()) {
            if (replayPrices == null) {
                throw new IllegalStateException("Replay price service is required while demo mode is active");
            }
            var prices = replayPrices.today(now).stream()
                    .map(price -> java.util.Map.<String,Object>of(
                            "periodStart", price.intervalStart(), "usep", price.usepSgdPerMwh()))
                    .toList();
            return java.util.Map.of("asOf",now,"start",start,"intervalMinutes",30,"units","SGD_PER_MWH",
                    "prices",prices,"expectedPeriods",Duration.between(start,BaselineForecastService.floor(now))
                    .toMinutes()/30+1,"observedPeriods",prices.size());
        }
        var prices=history.asOf(now).stream().filter(row -> !row.periodStart().isBefore(start)).toList();
        return java.util.Map.of("asOf",now,"start",start,"intervalMinutes",30,"units","SGD_PER_MWH","prices",prices,
                "expectedPeriods",java.time.Duration.between(start,BaselineForecastService.floor(now)).toMinutes()/30+1,
                "observedPeriods",prices.size());
    }
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
