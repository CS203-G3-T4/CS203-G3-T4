package sg.edu.smu.cs203.forecast;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import sg.edu.smu.cs203.market.price.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static sg.edu.smu.cs203.forecast.ForecastTypes.*;

@Component
public class ForecastJob {
    private static final Logger LOG=LoggerFactory.getLogger(ForecastJob.class);
    private final MarketHistoryRepository history;
    private final ForecastRepository repository;
    private final BaselineForecastService baselines;
    private final CurrentAssessmentService assessments;
    private final PythonForecastClient python;
    private final ForecastService service;
    private final ObjectMapper mapper;
    private final Clock clock;
    // ponytail: one coalescing worker per process; DB uniqueness handles retries across processes.
    private final ExecutorService worker=Executors.newSingleThreadExecutor(Thread.ofPlatform().name("forecast-worker").daemon().factory());
    private final AtomicBoolean running=new AtomicBoolean();
    private final AtomicBoolean pending=new AtomicBoolean();

    public ForecastJob(MarketHistoryRepository history,ForecastRepository repository,BaselineForecastService baselines,
            CurrentAssessmentService assessments,PythonForecastClient python,ForecastService service,ObjectMapper mapper,Clock clock) {
        this.history=history; this.repository=repository; this.baselines=baselines; this.assessments=assessments;
        this.python=python; this.service=service; this.mapper=mapper; this.clock=clock;
    }
    @EventListener
    public void onObservation(MarketObservationSaved ignored) {
        if (service.mode().equals("LIVE")) trigger();
    }
    public void trigger() {
        if (worker.isShutdown()) return;
        pending.set(true);
        if (running.compareAndSet(false,true)) worker.submit(() -> {
            try {
                while (pending.getAndSet(false)) {
                    try { runOnce(); } catch (RuntimeException exc) { LOG.error("Forecast failed; prior saved run retained",exc); }
                }
            } finally {
                running.set(false);
                if (pending.get()) trigger();
            }
        });
    }
    /** F5 can invoke after supplying its Clock and canonical history in REPLAY mode. */
    public synchronized void runOnce() {
        Instant asOf=clock.instant();
        List<MarketRevision> rows=history.asOf(asOf);
        String snapshot=mapper.writeValueAsString(rows);
        String input=hash(snapshot);
        String requestId=hash(service.mode()+"|"+BaselineForecastService.floor(asOf)+"|"+input);
        boolean stale=service.stale(rows,asOf);
        boolean verified=service.mappingVerified(rows);
        List<String> flags=new ArrayList<>();
        if (!verified) flags.add("PERIOD_MAPPING_UNVERIFIED");
        if (stale) flags.add("STALE_INPUT");
        Map<String,List<BigDecimal>> predictions=stale ? new LinkedHashMap<>() : new LinkedHashMap<>(baselines.calculate(rows,asOf));
        List<String> ranking=repository.baselineRanking(asOf,service.mode());
        String reason=stale ? "STALE_INPUT" : !verified ? "PERIOD_MAPPING_UNVERIFIED" : null;
        JsonNode model=null;
        String version=null;
        if (!stale && verified) {
            try {
                JsonNode response=python.forecast(requestId,asOf,rows);
                List<BigDecimal> ai=new ArrayList<>();
                for (JsonNode p : response.path("points")) ai.add(p.path("predictedUsep").decimalValue());
                predictions.put("AI",ai); model=response.path("manifest"); version=response.path("modelVersion").asText();
                ranking=List.of(mapper.convertValue(response.path("baselineRanking"),String[].class));
                for (JsonNode flag : response.path("qualityFlags")) flags.add(flag.asText());
            } catch (RuntimeException exc) {
                reason="PYTHON_UNAVAILABLE_OR_INVALID";
                LOG.warn("Python forecast unavailable; selecting Java baseline ({})",exc.getClass().getSimpleName());
            }
        }
        if (ranking.isEmpty()) { ranking=BaselineForecastService.DEFAULT_ORDER; flags.add("BASELINE_RANKING_UNRANKED"); }
        String selected=predictions.containsKey("AI") ? "AI" : ranking.stream().filter(predictions::containsKey).findFirst().orElse("NONE");
        if (version==null) version=selected.equals("NONE") ? "none-v1" : selected+"-v1";
        if (selected.equals("NONE") && !stale) reason="INSUFFICIENT_HISTORY";
        Map<String,List<Point>> candidates=new LinkedHashMap<>();
        List<Instant> targets=BaselineForecastService.targets(asOf);
        for (var entry : predictions.entrySet()) {
            List<Point> points=new ArrayList<>();
            for (int i=0;i<24;i++) {
                Reference ref=assessments.reference(rows,targets.get(i),asOf);
                BigDecimal price=entry.getValue().get(i);
                points.add(new Point(i+1,targets.get(i),price,ref.threshold(),ref.available() ? price.compareTo(ref.threshold())>0 : null));
            }
            candidates.put(entry.getKey(),points);
        }
        Run run=new Run(hash(requestId+"|"+version),asOf,clock.instant(),input,service.mode(),
                selected.equals("AI") ? "AI" : selected.equals("NONE") ? "NONE" : "BASELINE",version,selected,
                selected.equals("AI") ? null : reason,List.copyOf(flags),stale,selected.equals("NONE") ? "UNAVAILABLE" : "AVAILABLE",
                candidates.getOrDefault(selected,List.of()));
        repository.save(run,snapshot,candidates,model);
        repository.evaluateAvailable(asOf,service.mode());
    }
    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exc) { throw new IllegalStateException(exc); }
    }
    @PreDestroy
    public void close() { pending.set(false); worker.shutdownNow(); }
}
