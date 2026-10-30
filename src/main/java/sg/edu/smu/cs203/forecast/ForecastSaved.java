package sg.edu.smu.cs203.forecast;

/** Emitted inside the save transaction; consumers must wait for its successful commit. */
public record ForecastSaved(String runId, String mode) {}
