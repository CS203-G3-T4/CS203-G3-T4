package sg.edu.smu.cs203.recommendation;

import java.math.*;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import sg.edu.smu.cs203.forecast.*;
import sg.edu.smu.cs203.household.*;
import sg.edu.smu.cs203.household.appliance.ApplianceResponse;
import sg.edu.smu.cs203.market.price.*;

/** Fixed-window, prospective first-plan comparison. It never trains or promotes a model. */
@Service
public class LiveComparisonService {
    static final ZoneId SGT=ZoneId.of("Asia/Singapore");
    final LiveEvidenceRepository evidence;
    final ForecastRepository forecasts;
    final HouseholdDirectory households;
    final HouseholdRecommendationReader reader;
    final RecommendationRepository recommendations;
    final MarketHistoryRepository history;
    final ObjectMapper mapper;
    final Clock clock;
    final boolean enabled;
    final String trialId,modelVersion,modelHash;
    final int days,maxActive;
    final BigDecimal minimum;
    public LiveComparisonService(LiveEvidenceRepository evidence,ForecastRepository forecasts,HouseholdDirectory households,
            HouseholdRecommendationReader reader,RecommendationRepository recommendations,MarketHistoryRepository history,
            ObjectMapper mapper,Clock clock,@Value("${forecast.live-comparison.enabled:false}") boolean enabled,
            @Value("${forecast.live-comparison.id:live-savings-v1}") String trialId,
            @Value("${forecast.live-comparison.model-version:}") String modelVersion,
            @Value("${forecast.live-comparison.model-sha256:}") String modelHash,
            @Value("${forecast.live-comparison.days:28}") int days,
            @Value("${recommendation.minimum-saving-sgd:0.05}") BigDecimal minimum,
            @Value("${recommendation.max-active:3}") int maxActive) {
        this.evidence=evidence;this.forecasts=forecasts;this.households=households;this.reader=reader;
        this.recommendations=recommendations;this.history=history;this.mapper=mapper;this.clock=clock;
        this.enabled=enabled;this.trialId=trialId;this.modelVersion=modelVersion;this.modelHash=modelHash;
        this.days=days;this.minimum=minimum;this.maxActive=maxActive;
        if(enabled && (modelVersion.isBlank() || !modelHash.matches("[0-9a-f]{64}") || days<1 || days>90))
            throw new IllegalArgumentException("Live comparison requires a pinned model hash/version and bounded window");
    }
    public boolean enabled() { return enabled; }
    public String trialId() { return trialId; }
    public String modelVersion() { return modelVersion; }
    JsonNode policy() { return mapper.valueToTree(Map.of("version","FIRST_ELIGIBLE_PLAN_V1","baseline","B3-v1",
        "minimumSavingSgd",minimum,"maximumSuggestions",maxActive,"days",days,
        "truth","FIRST_COMPLETE_PROVISIONAL_AS_OBSERVED","weight","runsPerWeek/7",
        "calibration","90_PERCENT_DAILY_MAX_NORMALIZED_SAVINGS_ERROR_20_COMPLETE_DAYS",
        "scope","Modelled wholesale costs assuming plans followed; not metered household bills")); }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void initialize() { if(enabled) evidence.trial(trialId,modelVersion,modelHash,policy(),clock.instant(),days); }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void capture(String runId) {
        if(!enabled) return;
        Instant now=clock.instant();
        var trial=evidence.trial(trialId,modelVersion,modelHash,policy(),now,days);
        var run=evidence.run(runId,forecasts);
        if(run==null || !"LIVE".equals(run.mode()) || run.asOf().isBefore(LiveEvidenceRepository.time(trial,"started_at"))
                || !run.asOf().isBefore(LiveEvidenceRepository.time(trial,"ends_at"))) return;
        var ai=forecasts.points(runId,"AI");var b3=forecasts.points(runId,"B3");
        String reason=null;
        if(run.asOf().isAfter(now) || run.generatedAt().isAfter(now) || run.stale() || run.qualityFlags().contains("PERIOD_MAPPING_UNVERIFIED") || !"AVAILABLE".equals(run.status())) reason="STALE_OR_FUTURE_RUN";
        else if(!"AI".equals(run.modelType())) reason="AI_UNAVAILABLE_BASELINE_FALLBACK";
        else if(!modelVersion.equals(run.modelVersion()) || !modelHash.equals(evidence.modelHash(run.modelVersion()))) reason="MODEL_CHANGED";
        else if(!aligned(ai,b3,run.asOf())) reason="MISSING_OR_UNMATCHED_FORECASTS";
        if(!evidence.issue(trialId,runId,now,reason==null,reason) || reason!=null) return;
        evidence.prices(runId,ai,b3);
        var aiPrices=points(ai);var b3Prices=points(b3);
        for(var home:households.households()) {
            var input=reader.read(home.id());
            if(!input.household().exposedToWholesalePrice()) continue;
            Map<Long,CheapestStartCalculator.Result> a=new LinkedHashMap<>(),b=new LinkedHashMap<>();
            List<RecommendationService.Candidate> ac=new ArrayList<>(),bc=new ArrayList<>();
            for(var appliance:input.flexibleAppliances()) {
                if(appliance.runsPerWeek()<=0 || appliance.earliestStart()==null || appliance.mustFinishBy()==null) continue;
                var ar=CheapestStartCalculator.cheapestStart(RecommendationService.runOf(appliance),aiPrices,run.asOf(),minimum);
                var br=CheapestStartCalculator.cheapestStart(RecommendationService.runOf(appliance),b3Prices,run.asOf(),minimum);
                if(ar.outcome()==CheapestStartCalculator.Outcome.NOT_ENOUGH_FORECAST || br.outcome()==CheapestStartCalculator.Outcome.NOT_ENOUGH_FORECAST
                        || !Objects.equals(ar.usualStart(),br.usualStart()) || recommendations.decidedForRun(appliance.id(),ar.usualStart())) continue;
                a.put(appliance.id(),ar);b.put(appliance.id(),br);
                if(moves(ar)) ac.add(new RecommendationService.Candidate(appliance,ar));
                if(moves(br)) bc.add(new RecommendationService.Candidate(appliance,br));
            }
            var selectedA=RecommendationService.rank(ac,maxActive).stream().map(c->c.appliance().id()).collect(Collectors.toSet());
            var selectedB=RecommendationService.rank(bc,maxActive).stream().map(c->c.appliance().id()).collect(Collectors.toSet());
            for(var appliance:input.flexibleAppliances()) if(a.containsKey(appliance.id())) {
                var ar=a.get(appliance.id());var br=b.get(appliance.id());
                Instant as=selectedA.contains(appliance.id())?ar.bestStart():ar.usualStart();
                Instant bs=selectedB.contains(appliance.id())?br.bestStart():br.usualStart();
                BigDecimal ae=selectedA.contains(appliance.id())?ar.saving():BigDecimal.ZERO;
                BigDecimal be=selectedB.contains(appliance.id())?br.saving():BigDecimal.ZERO;
                String bucket=SavingsErrorRange.bucket(appliance.runMinutes(),run.asOf(),ar.usualStart(),as.isAfter(bs)?as:bs);
                var prediction=mapper.createObjectNode();
                prediction.put("AI",ae).put("B3",be).put("aiWarning",selectedA.contains(appliance.id())&&ar.outcome()==CheapestStartCalculator.Outcome.AVOID)
                    .put("b3Warning",selectedB.contains(appliance.id())&&br.outcome()==CheapestStartCalculator.Outcome.AVOID);
                prediction.set("aiRange",mapper.valueToTree(range("AI",bucket,ae,appliance.powerKw(),appliance.runMinutes(),run.asOf())));
                prediction.set("b3Range",mapper.valueToTree(range("B3",bucket,be,appliance.powerKw(),appliance.runMinutes(),run.asOf())));
                evidence.opportunity(trialId,runId,home.id(),appliance.id(),run.asOf(),ar.usualStart(),as,bs,appliance.powerKw(),appliance.runMinutes(),
                    BigDecimal.valueOf(appliance.runsPerWeek()).divide(BigDecimal.valueOf(7),10,RoundingMode.HALF_UP),bucket,prediction);
            }
        }
    }
    static boolean aligned(List<ForecastTypes.Point> ai,List<ForecastTypes.Point> b3,Instant asOf) {
        var targets=BaselineForecastService.targets(asOf);
        if(ai.size()!=24 || b3.size()!=24) return false;
        for(int i=0;i<24;i++) if(!targets.get(i).equals(ai.get(i).targetPeriod()) || !targets.get(i).equals(b3.get(i).targetPeriod())
            || ai.get(i).predictedUsep()==null || b3.get(i).predictedUsep()==null
            || !Objects.equals(ai.get(i).spikeThreshold(),b3.get(i).spikeThreshold())) return false;
        return true;
    }
    static List<PriceForecast.HalfHourPrice> points(List<ForecastTypes.Point> rows) {
        return rows.stream().map(p->new PriceForecast.HalfHourPrice(p.targetPeriod(),p.predictedUsep(),Boolean.TRUE.equals(p.spikeFlag()))).toList();
    }
    static boolean moves(CheapestStartCalculator.Result r) { return r.outcome()==CheapestStartCalculator.Outcome.SHIFT || r.outcome()==CheapestStartCalculator.Outcome.AVOID; }
    SavingsErrorRange.Range range(String method,String bucket,BigDecimal estimate,BigDecimal kw,int minutes,Instant asOf) {
        var samples=enabled?evidence.calibration(trialId,method,bucket,asOf):List.<SavingsErrorRange.Sample>of();
        return SavingsErrorRange.calculate(estimate,SavingsErrorRange.energy(kw,minutes),samples,asOf.atZone(SGT).toLocalDate());
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void evaluate() {
        if(!enabled || evidence.trial(trialId)==null) return;
        Instant now=clock.instant();
        var pending=evidence.pendingOpportunities(trialId,now);var prices=evidence.pendingPrices(trialId,now);
        if(pending.isEmpty() && prices.isEmpty()) return;
        Instant from=now;
        for(var row:pending) if(LiveEvidenceRepository.time(row,"usual_start").isBefore(from)) from=LiveEvidenceRepository.time(row,"usual_start");
        for(var row:pending) for(String k:List.of("ai_start","b3_start")) if(LiveEvidenceRepository.time(row,k).isBefore(from)) from=LiveEvidenceRepository.time(row,k);
        for(var row:prices) if(LiveEvidenceRepository.time(row,"target_period").isBefore(from)) from=LiveEvidenceRepository.time(row,"target_period");
        var truth=history.provisionalAsOf("NEMS_SN_SG",now,from.minusSeconds(1800)).stream()
                .collect(Collectors.toMap(MarketRevision::periodStart,t->t));
        for(var row:prices) {
            var actual=truth.get(LiveEvidenceRepository.time(row,"target_period"));
            if(actual!=null) evidence.priceTruth(row,actual,now);
        }
        Map<Instant,BigDecimal> values=new HashMap<>();truth.forEach((p,t)->values.put(p,t.usep()));
        for(var row:pending) {
            var run=new CheapestStartCalculator.ShiftableRun(row.path("power_kw").decimalValue(),row.path("run_minutes").asInt(),null,null,null);
            BigDecimal usual=CheapestStartCalculator.costOf(LiveEvidenceRepository.time(row,"usual_start"),run,values);
            BigDecimal ai=CheapestStartCalculator.costOf(LiveEvidenceRepository.time(row,"ai_start"),run,values);
            BigDecimal b3=CheapestStartCalculator.costOf(LiveEvidenceRepository.time(row,"b3_start"),run,values);
            if(usual==null || ai==null || b3==null) continue;
            var outcome=mapper.createObjectNode();
            BigDecimal energy=SavingsErrorRange.energy(run.powerKw(),run.runMinutes());
            for(var pair:Map.of("AI",ai,"B3",b3).entrySet()) {
                BigDecimal saving=usual.subtract(pair.getValue());
                var score=outcome.putObject(pair.getKey());
                score.put("savingSgd",saving).put("chosenCostSgd",pair.getValue()).put("usualCostSgd",usual)
                    .put("errorPerKwh",saving.subtract(row.path("prediction").path(pair.getKey()).decimalValue()).divide(energy,10,RoundingMode.HALF_UP));
            }
            var ids=outcome.putArray("truthRevisions");
            Set<Instant> touched=new HashSet<>();
            for(String key:List.of("usual_start","ai_start","b3_start")) {
                Instant start=LiveEvidenceRepository.time(row,key),end=start.plusSeconds(run.runMinutes()*60L);
                for(Instant p=BaselineForecastService.floor(start);p.isBefore(end);p=p.plusSeconds(1800)) touched.add(p);
            }
            touched.stream().sorted().forEach(p->{var t=truth.get(p);ids.addObject().put("periodStart",p.toString()).put("revision",t.id()).put("availableAt",t.availableAt().toString());});
            evidence.outcome(row.path("id").asLong(),outcome,now);
        }
    }
    public Map<String,Object> report() {
        Map<String,Object> out=new LinkedHashMap<>();var trial=evidence.trial(trialId);
        out.put("enabled",enabled);out.put("trial",trial);out.put("asOf",clock.instant());out.put("productionPromotion",false);
        out.put("captureActive",enabled && trial!=null && clock.instant().isBefore(LiveEvidenceRepository.time(trial,"ends_at")));
        out.put("interpretation","Counterfactual wholesale savings if frozen first eligible plans were followed; not metered bills. Forecast pairs may share actual target periods.");
        if(trial==null) return out;
        var all=evidence.opportunities(trialId);var scored=all.stream().filter(r->!r.path("outcome").isNull()).toList();
        out.put("frozenOpportunities",all.size());out.put("scoredOpportunities",scored.size());out.put("pendingOpportunities",all.size()-scored.size());
        out.put("captureCoverage",evidence.exclusions(trialId));
        Map<String,Object> methods=new LinkedHashMap<>();
        for(String method:List.of("AI","B3")) {
            BigDecimal saving=BigDecimal.ZERO,loss=BigDecimal.ZERO;int harmful=0,moved=0,covered=0,intervals=0;
            Map<String,BigDecimal> weeks=new TreeMap<>();
            for(var row:scored) {
                BigDecimal value=row.path("outcome").path(method).path("savingSgd").decimalValue();
                BigDecimal weighted=value.multiply(row.path("weight").decimalValue());saving=saving.add(weighted);
                if(value.signum()<0) {harmful++;loss=loss.subtract(weighted);}
                if(!row.path(method.equals("AI")?"ai_start":"b3_start").equals(row.path("usual_start"))) moved++;
                String week="week-"+(1+Duration.between(LiveEvidenceRepository.time(trial,"started_at"),LiveEvidenceRepository.time(row,"issued_at")).toDays()/7);
                weeks.merge(week,weighted,BigDecimal::add);
                var range=row.path("prediction").path(method.equals("AI")?"aiRange":"b3Range");
                if(range.path("available").asBoolean()) {intervals++;if(value.compareTo(range.path("lowerSgd").decimalValue())>=0 && value.compareTo(range.path("upperSgd").decimalValue())<=0) covered++;}
            }
            methods.put(method,Map.of("modelledSavingsSgd",saving,"harmfulReschedules",harmful,"harmfulLossSgd",loss,"reschedules",moved,
                "weeklySavingsSgd",weeks,"testedRanges",intervals,"coveredRanges",covered));
        }
        out.put("methods",methods);var price=evidence.scoredPrices(trialId);out.put("priceAccuracy",priceReport(price));
        Map<Integer,Object> horizons=new LinkedHashMap<>();
        for(int h=1;h<=24;h++) {final int horizon=h;horizons.put(h,priceReport(price.stream().filter(p->p.path("horizon").asInt()==horizon).toList()));}
        out.put("priceAccuracyByHorizon",horizons);
        out.put("calibrationMinimumCompletedDays",SavingsErrorRange.MINIMUM_DAYS);return out;
    }
    static Map<String,Object> priceReport(List<JsonNode> rows) {
        Map<String,Object> result=new LinkedHashMap<>();
        for(String method:List.of("AI","B3")) {
            Map<String,Object> metrics=new LinkedHashMap<>();BigDecimal ae=BigDecimal.ZERO,ordinary=BigDecimal.ZERO,spike=BigDecimal.ZERO;
            double square=0;int normal=0,high=0,tp=0,fp=0,fn=0;
            for(var row:rows) {
                BigDecimal actual=row.path("actual").decimalValue(),pred=row.path(method.toLowerCase()).decimalValue(),error=pred.subtract(actual);
                ae=ae.add(error.abs());square+=error.doubleValue()*error.doubleValue();
                if(row.path("threshold").isNumber()) {
                    boolean truth=actual.compareTo(row.path("threshold").decimalValue())>0,warning=pred.compareTo(row.path("threshold").decimalValue())>0;
                    if(truth){spike=spike.add(error.abs());high++;if(warning)tp++;else fn++;} else {ordinary=ordinary.add(error.abs());normal++;if(warning)fp++;}
                }
            }
            metrics.put("pairs",rows.size());metrics.put("distinctActualPeriods",rows.stream().map(r->r.path("target_period").asText()).distinct().count());
            metrics.put("maeSgdPerMwh",mean(ae,rows.size()));metrics.put("rmseSgdPerMwh",rows.isEmpty()?null:Math.sqrt(square/rows.size()));
            metrics.put("ordinaryMaeSgdPerMwh",mean(ordinary,normal));metrics.put("spikeMaeSgdPerMwh",mean(spike,high));
            metrics.put("truePositiveTargetPairs",tp);metrics.put("falsePositiveTargetPairs",fp);metrics.put("missedSpikeTargetPairs",fn);
            metrics.put("spikeMetricUnit","SAVED_RUN_AND_TARGET_PAIR; not independent onset episodes");result.put(method,metrics);
        }
        return result;
    }
    static BigDecimal mean(BigDecimal n,int count) { return count==0?null:n.divide(BigDecimal.valueOf(count),6,RoundingMode.HALF_UP); }
}
