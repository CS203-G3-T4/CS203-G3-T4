package sg.edu.smu.cs203.market.price;

public class SimulatedFeedOutageException extends IllegalStateException {

    public SimulatedFeedOutageException() {
        super("Simulated NEMS outage (admin outage switch is on)");
    }
}
