package sg.edu.smu.cs203.recommendation;

/** Asked for the demo forecast while suggestions use F3's forecast (CSDT4-40). */
public class DemoForecastNotInUseException extends RuntimeException {

    public DemoForecastNotInUseException() {
        super("Suggestions use F3's forecast right now; chart /api/v1/forecast/latest instead");
    }
}
