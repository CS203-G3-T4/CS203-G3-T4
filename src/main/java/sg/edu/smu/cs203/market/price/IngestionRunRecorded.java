package sg.edu.smu.cs203.market.price;

/** Published after every NEMS poll outcome is saved, so feed health can track incidents (CSDT4-25). */
public record IngestionRunRecorded(IngestionRun run) {
}
