package sg.edu.smu.cs203.market.price;

public class NoMarketPriceException extends RuntimeException {

    public NoMarketPriceException() {
        super("No market price is available from the feed or stored history");
    }
}
