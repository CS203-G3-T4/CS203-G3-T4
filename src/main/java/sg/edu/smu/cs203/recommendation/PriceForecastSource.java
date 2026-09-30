package sg.edu.smu.cs203.recommendation;

/**
 * Where F4 gets its price forecast. This is the "ForecastService interface" from CSDT4-19,
 * kept small and owned by F4 so the engine doesn't depend on how F3 stores its runs.
 *
 * Two implementations, chosen by recommendation.forecast-source (see PriceForecastConfiguration
 * and docs/f4-recommendations.md):
 * - demo: a fixed curve from the 26 Sep 2026 replay day, always available, labelled as a demo
 * - f3:   F3's latest saved forecast, used only when F3 marks it actionable
 */
public interface PriceForecastSource {

    Lookup forecast();

    /**
     * Either a forecast F4 may act on, or the reason there isn't one (shown on the page).
     */
    record Lookup(PriceForecast forecast, String unavailableReason) {

        public static Lookup of(PriceForecast forecast) {
            return new Lookup(forecast, null);
        }

        public static Lookup unavailable(String reason) {
            return new Lookup(null, reason);
        }

        public boolean available() {
            return forecast != null;
        }
    }
}
