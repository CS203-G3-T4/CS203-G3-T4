package sg.edu.smu.cs203.market.price;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketRevision(long id, String source, Instant periodStart, Instant sourceUpdatedAt,
        Instant availableAt, BigDecimal usep, BigDecimal demand, BigDecimal vcp,
        String priceStatus, String timeMapping) {
    public MarketRevision {
        if (source == null || source.isBlank() || periodStart == null || periodStart.getNano() != 0
                || Math.floorMod(periodStart.getEpochSecond(), 1800) != 0 || usep == null
                || demand != null && demand.signum() < 0
                || !java.util.Set.of("FINAL", "PROVISIONAL").contains(priceStatus)
                || timeMapping == null || (sourceUpdatedAt == null) != (availableAt == null)) {
            throw new IllegalArgumentException("Invalid canonical market revision");
        }
        usep = usep.setScale(4,java.math.RoundingMode.HALF_UP);
        if (demand != null) demand = demand.setScale(3,java.math.RoundingMode.HALF_UP);
        if (vcp != null) vcp = vcp.setScale(4,java.math.RoundingMode.HALF_UP);
    }
}
