package sg.edu.smu.cs203.forecast;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ForecastTypes {
    private ForecastTypes() {}
    public record Point(int horizon, Instant targetPeriod, BigDecimal predictedUsep,
                        BigDecimal spikeThreshold, Boolean spikeFlag) {}
    public record Run(String id, Instant asOf, Instant generatedAt, String inputRevision, String mode,
                      String modelType, String modelVersion, String selectedModel, String fallbackReason,
                      List<String> qualityFlags, boolean stale, String status, List<Point> points) {}
    public record View(boolean available, boolean actionable, boolean stale, String mode, String reason, Run run) {}
    public record Reference(boolean available, BigDecimal typical, BigDecimal spread, BigDecimal threshold,
                            int sampleCount, int windowDays) {}
    public record Assessment(boolean available, boolean stale, BigDecimal actualPrice, BigDecimal typical,
                             BigDecimal deviation, BigDecimal deviationPercent, BigDecimal spikeThreshold,
                             String classification, int sampleCount, int windowDays, String reason) {}
}
