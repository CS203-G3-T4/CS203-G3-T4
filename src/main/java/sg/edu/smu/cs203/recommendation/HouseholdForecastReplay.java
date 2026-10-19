package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import sg.edu.smu.cs203.forecast.BaselineForecastService;
import sg.edu.smu.cs203.household.HouseholdDirectory;

/** Counterfactual replay of real F2 configuration through the unchanged F4 optimizer. */
@RestController
public class HouseholdForecastReplay {
    public record Request(long householdId,Instant asOf,Instant modelUsableFrom,Instant baselineValidationEnd,
                          String modelVersion,String baselineMethod,String truthPolicy,String datasetDigest,
                          List<PriceForecast.HalfHourPrice> ai,List<PriceForecast.HalfHourPrice> baseline,
                          List<PriceForecast.HalfHourPrice> actual) {}
    private final HouseholdDirectory households;
    public HouseholdForecastReplay(HouseholdDirectory households) { this.households=households; }

    @PostMapping(value="/api/v1/admin/forecast-savings-replay",consumes="application/json")
    public Map<String,Object> replay(@RequestBody Request request,HttpServletRequest http) {
        if (http.getUserPrincipal()==null || !http.isUserInRole("ADMIN")) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        try { return compare(request); } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,exception.getMessage());
        }
    }
    private static Instant chosenStart(CheapestStartCalculator.Result result) {
        return switch (result.outcome()) {
            case SHIFT, AVOID -> result.bestStart();
            case USUAL_IS_CHEAPEST, NOT_ENOUGH_FORECAST -> result.usualStart();
        };
    }
    public Map<String,Object> compare(Request request) {
        if (request.ai()==null || request.baseline()==null || request.actual()==null || request.actual().size()>24
                || request.asOf()==null || request.modelUsableFrom()==null || request.baselineValidationEnd()==null
                || request.modelUsableFrom().isAfter(request.asOf()) || request.baselineValidationEnd().isAfter(request.asOf())
                || !List.of("B1","B2","B3").contains(request.baselineMethod()) || request.modelVersion()==null
                || request.datasetDigest()==null || request.truthPolicy()==null)
            throw new IllegalArgumentException("Require frozen earlier model/baseline selection and provenance");
        var targets=BaselineForecastService.targets(request.asOf());
        for (var prices:List.of(request.ai(),request.baseline())) if (prices.size()!=24
                || !prices.stream().map(PriceForecast.HalfHourPrice::periodStart).toList().equals(targets)
                || prices.stream().anyMatch(p->p.usepSgdPerMwh()==null))
            throw new IllegalArgumentException("Both forecasts must cover the same ordered24 targets");
        Map<Instant,BigDecimal> actual=new HashMap<>();
        for (var point:request.actual()) {
            if (!targets.contains(point.periodStart()) || point.usepSgdPerMwh()==null || actual.putIfAbsent(point.periodStart(),point.usepSgdPerMwh())!=null)
                throw new IllegalArgumentException("Actuals must be unique matching target periods");
        }
        var household=households.household(request.householdId());
        List<Map<String,Object>> cases=new ArrayList<>();
        Map<String,Integer> excluded=new LinkedHashMap<>();
        if (household.exposedToWholesalePrice()) for (var appliance:households.flexibleAppliances(request.householdId())) {
            if (!appliance.enabled() || appliance.runsPerWeek()==0) continue;
            var run=new CheapestStartCalculator.ShiftableRun(appliance.powerKw(),appliance.runMinutes(),
                    appliance.earliestStart(),appliance.mustFinishBy(),appliance.usualStart());
            var ai=CheapestStartCalculator.cheapestStart(run,request.ai(),request.asOf(),new BigDecimal("0.01"));
            var baseline=CheapestStartCalculator.cheapestStart(run,request.baseline(),request.asOf(),new BigDecimal("0.01"));
            if (ai.outcome()==CheapestStartCalculator.Outcome.NOT_ENOUGH_FORECAST || baseline.outcome()==CheapestStartCalculator.Outcome.NOT_ENOUGH_FORECAST) {
                excluded.merge("USUAL_RUN_OUTSIDE_FORECAST",1,Integer::sum); continue;
            }
            Instant aiStart=chosenStart(ai);
            Instant baselineStart=chosenStart(baseline);
            BigDecimal usual=CheapestStartCalculator.costOf(ai.usualStart(),run,actual);
            BigDecimal aiCost=CheapestStartCalculator.costOf(aiStart,run,actual),baselineCost=CheapestStartCalculator.costOf(baselineStart,run,actual);
            if (usual==null || aiCost==null || baselineCost==null) { excluded.merge("MISSING_REALIZED_PRICE",1,Integer::sum); continue; }
            Map<String,Object> row=new LinkedHashMap<>();
            row.put("caseId",household.id()+":"+appliance.id()+":"+ai.usualStart());
            row.put("householdId",household.id()); row.put("applianceId",appliance.id()); row.put("asOf",request.asOf());
            row.put("usualStart",ai.usualStart()); row.put("aiStart",aiStart); row.put("baselineStart",baselineStart);
            row.put("usualCostSgd",usual); row.put("aiCostSgd",aiCost); row.put("baselineCostSgd",baselineCost);
            row.put("aiSavingsSgd",usual.subtract(aiCost)); row.put("baselineSavingsSgd",usual.subtract(baselineCost));
            row.put("runsPerWeek",appliance.runsPerWeek()); row.put("run",run); row.put("actual",request.actual());
            cases.add(row);
        }
        return Map.of("household",household,"cases",cases,"exclusions",excluded,
                "fixedRateNotShifted",!household.exposedToWholesalePrice(),"modelled",true,
                "historicalConfigurationVerified",false,
                "assumptions",List.of("Current F2 configuration held fixed as a counterfactual scenario, not verified historical configuration",
                    "Unchanged F4 cheapest-start constraints and minimum saving0.01SGD; recommendations assumed followed",
                    "Caller deduplicates caseId across forecast origins and weights run frequency consistently"));
    }
}
