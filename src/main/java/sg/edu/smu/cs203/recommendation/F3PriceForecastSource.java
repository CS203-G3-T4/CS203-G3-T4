package sg.edu.smu.cs203.recommendation;

import java.util.ArrayList;
import java.util.List;

import sg.edu.smu.cs203.forecast.ForecastService;
import sg.edu.smu.cs203.forecast.ForecastTypes;

/**
 * Reads F3's latest saved forecast. As agreed in docs/forecasting/contracts.md, F4 only acts
 * on a forecast F3 marks actionable (available, not stale, period mapping verified).
 */
public class F3PriceForecastSource implements PriceForecastSource {

    private final ForecastService forecasts;

    public F3PriceForecastSource(ForecastService forecasts) {
        this.forecasts = forecasts;
    }

    @Override
    public Lookup forecast() {
        ForecastTypes.View view = forecasts.latest();
        if (!view.actionable() || view.run() == null) {
            String why = view.reason() == null ? "not actionable" : view.reason();
            return Lookup.unavailable("F3's forecast is not usable right now (" + why + ")");
        }
        ForecastTypes.Run run = view.run();
        List<PriceForecast.HalfHourPrice> points = new ArrayList<>();
        for (ForecastTypes.Point point : run.points()) {
            if (point.predictedUsep() != null) {
                points.add(new PriceForecast.HalfHourPrice(point.targetPeriod(), point.predictedUsep()));
            }
        }
        if (points.isEmpty()) {
            return Lookup.unavailable("F3's forecast has no prices");
        }
        return Lookup.of(new PriceForecast(PriceForecast.F3, run.selectedModel(), run.asOf(), points));
    }
}
