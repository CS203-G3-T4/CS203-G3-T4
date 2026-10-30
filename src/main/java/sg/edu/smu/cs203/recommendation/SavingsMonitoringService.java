package sg.edu.smu.cs203.recommendation;

import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import sg.edu.smu.cs203.household.*;
import sg.edu.smu.cs203.household.appliance.ApplianceResponse;
import sg.edu.smu.cs203.market.price.*;
import sg.edu.smu.cs203.forecast.BaselineForecastService;

/** Immutable proposal/acceptance baselines, empirical ranges and quiet in-app start checks. */
@Service
public class SavingsMonitoringService {
    final LiveEvidenceRepository evidence;
    final LiveComparisonService comparison;
    final PriceForecastSource forecasts;
    final MarketHistoryRepository history;
    final HouseholdRecommendationReader households;
    final ObjectMapper mapper;
    final Clock clock;
    final BigDecimal materialChange;
    public SavingsMonitoringService(LiveEvidenceRepository evidence,LiveComparisonService comparison,PriceForecastSource forecasts,
            MarketHistoryRepository history,HouseholdRecommendationReader households,ObjectMapper mapper,Clock clock,
            @Value("${recommendation.savings-alert.minimum-change-sgd:0.05}") BigDecimal materialChange) {
        if(materialChange.signum()<=0) throw new IllegalArgumentException("Alert change must be positive");
        this.evidence=evidence;this.comparison=comparison;this.forecasts=forecasts;this.history=history;
        this.households=households;this.mapper=mapper;this.clock=clock;this.materialChange=materialChange;
    }
    void remember(long rec,long home,ApplianceResponse appliance,CheapestStartCalculator.Result plan,PriceForecast forecast) {
        if(!comparison.enabled() || forecast.isDemo() || forecast.runId()==null || forecast.modelVersion()==null) return;
        String method=forecast.modelVersion().equals("B3-v1")?"B3":"AI";
        if(method.equals("AI")&&!forecast.modelVersion().equals(comparison.modelVersion())) return;
        var proposal=snapshot(forecast.asOf(),clock.instant(),plan.usualStart(),plan.bestStart(),appliance.powerKw(),appliance.runMinutes(),forecast.points(),method);
        evidence.proposal(rec,home,forecast,method,proposal);
    }
    JsonNode snapshot(Instant issued,Instant created,Instant usual,Instant chosen,BigDecimal kw,int minutes,
                      List<PriceForecast.HalfHourPrice> points,String method) {
        var prices=new HashMap<Instant,BigDecimal>();points.forEach(p->prices.put(p.periodStart(),p.usepSgdPerMwh()));
        var run=new CheapestStartCalculator.ShiftableRun(kw,minutes,null,null,null);
        BigDecimal a=CheapestStartCalculator.costOf(usual,run,prices),b=CheapestStartCalculator.costOf(chosen,run,prices);
        BigDecimal estimate=a==null||b==null?null:a.subtract(b);
        var result=mapper.createObjectNode();
        result.put("issuedAt",issued.toString()).put("createdAt",created.toString()).put("usualStart",usual.toString())
            .put("chosenStart",chosen.toString()).put("powerKw",kw).put("runMinutes",minutes).put("estimatedSavingSgd",estimate);
        result.set("points",mapper.valueToTree(points));
        String bucket=SavingsErrorRange.bucket(minutes,issued,usual,chosen);
        result.put("bucket",bucket);
        result.set("range",mapper.valueToTree(estimate==null ? new SavingsErrorRange.Range(false,null,null,0,0,.90,"ORIGINAL_FORECAST_INCOMPLETE")
                : comparison.range(method,bucket,estimate,kw,minutes,created)));
        return result;
    }
    void accepted(long rec,Instant chosen,BigDecimal kw,int minutes) {
        if(!comparison.enabled()) return;
        var row=evidence.evidence(rec);if(row==null || !row.path("acceptance").isNull()) return;
        var original=row.path("proposal");
        evidence.acceptance(rec,snapshot(Instant.parse(original.path("issuedAt").asText()),clock.instant(),
                Instant.parse(original.path("usualStart").asText()),chosen,kw,minutes,readPoints(original.path("points")),row.path("method").asText()));
    }
    static List<PriceForecast.HalfHourPrice> readPoints(JsonNode array) {
        List<PriceForecast.HalfHourPrice> points=new ArrayList<>();
        for(var p:array) points.add(new PriceForecast.HalfHourPrice(Instant.parse(p.path("periodStart").asText()),
                p.path("usepSgdPerMwh").decimalValue(),p.path("spike").asBoolean()));
        return points;
    }
    Map<Long,JsonNode> views(long home,List<Recommendation> rows) {
        Map<Long,JsonNode> result=new LinkedHashMap<>();
        for(var rec:rows) if(PriceForecast.F3.equals(rec.forecastSource())) {
            var row=evidence.evidence(rec.id());
            var view=mapper.createObjectNode();
            if(row==null) view.putObject("range").put("available",false).put("reason","ORIGINAL_FORECAST_NOT_RECORDED").put("completedDays",0);
            else if(row.path("household_id").asLong()==home) {
                var baseline=row.path("acceptance").isObject()?row.path("acceptance"):row.path("proposal");
                view.set("range",baseline.path("range"));view.set("estimatedSavingSgd",baseline.path("estimatedSavingSgd"));
                view.set("chosenStart",baseline.path("chosenStart"));view.set("baselineAt",baseline.path("createdAt"));
                if(row.path("latest_check").isObject()) view.set("latestCheck",row.path("latest_check"));
            }
            result.put(rec.id(),view);
        }
        return result;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public void recheck() {
        if(!comparison.enabled()) return;
        Instant now=clock.instant();
        var source=forecasts.forecast();
        for(var row:evidence.acceptedEvidence(now)) {
            long rec=row.path("recommendation_id").asLong(),home=row.path("household_id").asLong();
            var check=mapper.createObjectNode().put("checkedAt",now.toString());
            var baseline=row.path("acceptance");
            var range=baseline.path("range");
            if(!source.available() || source.forecast().asOf().isAfter(now) || !Objects.equals(source.forecast().modelVersion(),row.path("model_version").asText())) {
                evidence.checked(rec,check.put("status","NO_MATCHING_FRESH_FORECAST"));continue;
            }
            if(!households.read(home).household().exposedToWholesalePrice()) {
                evidence.checked(rec,check.put("status","FIXED_RATE_PLAN"));continue;
            }
            var forecast=source.forecast();
            Instant usual=Instant.parse(baseline.path("usualStart").asText()),chosen=Instant.parse(baseline.path("chosenStart").asText());
            var values=new HashMap<Instant,BigDecimal>();
            for(var p:forecast.points()) if(p.periodStart().isAfter(BaselineForecastService.floor(now))) values.put(p.periodStart(),p.usepSgdPerMwh());
            var truth=history.asOf(now,(usual.isBefore(chosen)?usual:chosen).minusSeconds(1800));
            var used=check.putArray("observedRevisions");
            for(var t:truth) if(!t.periodStart().isAfter(BaselineForecastService.floor(now)) && "PROVISIONAL".equals(t.priceStatus()) && "NEMS_SN_SG".equals(t.source())) {
                values.put(t.periodStart(),t.usep());used.addObject().put("periodStart",t.periodStart().toString()).put("revision",t.id()).put("availableAt",t.availableAt().toString());
            }
            var run=new CheapestStartCalculator.ShiftableRun(baseline.path("powerKw").decimalValue(),baseline.path("runMinutes").asInt(),null,null,null);
            BigDecimal a=CheapestStartCalculator.costOf(usual,run,values),b=CheapestStartCalculator.costOf(chosen,run,values);
            if(a==null || b==null) {evidence.checked(rec,check.put("status","MISSING_CURRENT_OR_FUTURE_PRICES"));continue;}
            BigDecimal saving=a.subtract(b).setScale(4,RoundingMode.HALF_UP);
            check.put("estimatedSavingSgd",saving).put("forecastAsOf",forecast.asOf().toString()).put("forecastRunId",forecast.runId());
            if(!range.path("available").asBoolean()) {evidence.checked(rec,check.put("status","COLLECTING_LIVE_RESULTS"));continue;}
            BigDecimal lower=range.path("lowerSgd").decimalValue(),upper=range.path("upperSgd").decimalValue();
            String direction=saving.compareTo(lower.subtract(materialChange))<0?"LOWER":saving.compareTo(upper.add(materialChange))>0?"HIGHER":null;
            check.put("status",direction==null?"WITHIN_PAST_ERROR_RANGE":"OUTSIDE_PAST_ERROR_RANGE");evidence.checked(rec,check);
            evidence.resolveAlerts(rec,direction,now);
            if(direction!=null) {
                String text="Your planned run's estimated saving is now S$"+saving.setScale(2,RoundingMode.HALF_UP)+
                    ", outside the earlier past-error range of S$"+lower.setScale(2,RoundingMode.FLOOR)+" to S$"+upper.setScale(2,RoundingMode.CEILING)+
                    ". Review your planned start time; Wattly has not changed it.";
                evidence.alert(rec,home,now,direction,text,saving,lower,upper);
            }
        }
    }
}
