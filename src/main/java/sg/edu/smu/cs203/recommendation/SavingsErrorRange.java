package sg.edu.smu.cs203.recommendation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Measured whole-run savings errors, grouped by completed issue day to limit pseudoreplication. */
public final class SavingsErrorRange {
    public static final int MINIMUM_DAYS = 20;
    public record Sample(LocalDate day, BigDecimal errorPerKwh) {}
    public record Range(boolean available, BigDecimal lowerSgd, BigDecimal upperSgd,
                        int completedDays, int samples, double targetCoverage, String reason) {}
    private SavingsErrorRange() {}

    public static String bucket(int minutes,Instant issuedAt,Instant usual,Instant chosen) {
        long lead = Math.max(Duration.between(issuedAt,usual).toMinutes(),Duration.between(issuedAt,chosen).toMinutes());
        return (minutes<=60?"SHORT":minutes<=120?"MEDIUM":"LONG")+":"+(lead<=120?"2H":lead<=360?"6H":"12H");
    }
    public static BigDecimal energy(BigDecimal kw,int minutes) {
        return kw.multiply(BigDecimal.valueOf(minutes)).divide(BigDecimal.valueOf(60),10,RoundingMode.HALF_UP);
    }
    public static Range calculate(BigDecimal estimate,BigDecimal kwh,List<Sample> past,LocalDate today) {
        Map<LocalDate,BigDecimal> maxima = new TreeMap<>();
        int count=0;
        for (Sample sample:past) {
            if (sample.day().isBefore(today)) {
                maxima.merge(sample.day(),sample.errorPerKwh().abs(),BigDecimal::max); count++;
            }
        }
        if (estimate==null || kwh.signum()<=0 || maxima.size()<MINIMUM_DAYS)
            return new Range(false,null,null,maxima.size(),count,.90,"COLLECTING_LIVE_RESULTS");
        if (Collections.max(maxima.keySet()).isBefore(today.minusDays(7)))
            return new Range(false,null,null,maxima.size(),count,.90,"LIVE_ERROR_HISTORY_STALE");
        var errors=maxima.values().stream().sorted().toList();
        int rank=(int)Math.ceil((errors.size()+1)*.90);
        if (rank>errors.size()) return new Range(false,null,null,errors.size(),count,.90,"INSUFFICIENT_DAYS");
        BigDecimal radius=errors.get(rank-1).multiply(kwh);
        return new Range(true,estimate.subtract(radius).setScale(4,RoundingMode.FLOOR),
                estimate.add(radius).setScale(4,RoundingMode.CEILING),errors.size(),count,.90,
                "EMPIRICAL_DAILY_MAX_ERROR_NOT_A_GUARANTEE");
    }
}
