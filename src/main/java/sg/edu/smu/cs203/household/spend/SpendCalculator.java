package sg.edu.smu.cs203.household.spend;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CSDT4-41: month-to-date spend, half-hour by half-hour. No Spring and no clock: the caller passes
 * the half-hours, so a test can check it against a hand-worked example.
 *
 * Price-linked plan, for each half-hour that has a market price:
 *     kWh x (USEP / 1000 + every per-kWh charge that applies at that time)          -> SGD
 * plus each monthly charge x (half-hours so far / half-hours in the month), then
 *     subtotal x (1 + GST%)   (9% GST: x 1.09)
 * USEP is SGD/MWh, so kWh x USEP / 1000 is SGD. A half-hour with no saved price is skipped and
 * counted in halfHoursWithoutPrice, never guessed.
 *
 * Fixed-rate plan: kWh x the contracted rate (cents/kWh, as entered in Settings) / 100.
 */
public final class SpendCalculator {

    static final ZoneId SINGAPORE = ZoneId.of("Asia/Singapore");
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final MathContext PRECISE = MathContext.DECIMAL64;

    private SpendCalculator() {
    }

    /** One half-hour of the month so far: when it starts, modelled kWh, and its USEP (null if none saved). */
    public record HalfHour(Instant start, BigDecimal kwh, BigDecimal usepSgdPerMwh) {
    }

    /** How much one charge added, unrounded (round only for display). */
    public record Line(String code, String charge, TariffComponent.Unit unit, BigDecimal rate, BigDecimal amountSgd,
                       String source, String sourceUrl) {
    }

    /**
     * @param energySgd             kWh x USEP / 1000 over the half-hours with a price
     * @param lines                 each other charge and GST, in bill order
     * @param totalSgd              everything, including GST
     * @param kwhCounted            kWh in the half-hours that were priced
     * @param kwhWithoutPrice       kWh in half-hours skipped for having no price
     */
    public record Result(BigDecimal energySgd, List<Line> lines, BigDecimal subtotalSgd, BigDecimal gstSgd,
                         BigDecimal totalSgd, BigDecimal kwhCounted, BigDecimal kwhWithoutPrice, int halfHoursSoFar,
                         int halfHoursCounted, int halfHoursWithoutPrice) {
    }

    public static Result priceLinked(List<HalfHour> halfHours, List<TariffComponent> components,
                                     int halfHoursInMonth) {
        BigDecimal energy = BigDecimal.ZERO;
        BigDecimal kwhCounted = BigDecimal.ZERO;
        BigDecimal kwhWithoutPrice = BigDecimal.ZERO;
        int counted = 0;
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        Map<String, TariffComponent> shown = new LinkedHashMap<>();
        for (TariffComponent component : order(components)) {
            if (component.unit() != TariffComponent.Unit.PERCENT) {
                amounts.putIfAbsent(component.code(), BigDecimal.ZERO);
                shown.putIfAbsent(component.code(), component);
            }
        }

        for (HalfHour halfHour : halfHours) {
            if (halfHour.usepSgdPerMwh() == null) {
                kwhWithoutPrice = kwhWithoutPrice.add(halfHour.kwh());
                continue;
            }
            counted++;
            kwhCounted = kwhCounted.add(halfHour.kwh());
            energy = energy.add(halfHour.kwh().multiply(halfHour.usepSgdPerMwh()).divide(THOUSAND, PRECISE));
            ZonedDateTime at = halfHour.start().atZone(SINGAPORE);
            for (TariffComponent component : effective(components, at.toLocalDate())) {
                if (component.unit() == TariffComponent.Unit.SGD_PER_KWH && component.appliesAt(at.toLocalTime())) {
                    amounts.merge(component.code(), halfHour.kwh().multiply(component.rate()), BigDecimal::add);
                }
            }
        }

        // Monthly charges: the share of the month that has passed (by time, not by priced half-hours).
        LocalDate day = halfHours.isEmpty() ? null : halfHours.getLast().start().atZone(SINGAPORE).toLocalDate();
        BigDecimal monthShare = halfHoursInMonth == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(halfHours.size()).divide(BigDecimal.valueOf(halfHoursInMonth), PRECISE);
        if (day != null) {
            for (TariffComponent component : effective(components, day)) {
                if (component.unit() == TariffComponent.Unit.SGD_PER_MONTH) {
                    amounts.merge(component.code(), component.rate().multiply(monthShare), BigDecimal::add);
                }
            }
        }

        List<Line> lines = new ArrayList<>();
        BigDecimal subtotal = energy;
        for (Map.Entry<String, BigDecimal> amount : amounts.entrySet()) {
            TariffComponent component = shown.get(amount.getKey());
            lines.add(line(component, amount.getValue()));
            subtotal = subtotal.add(amount.getValue());
        }
        BigDecimal gst = BigDecimal.ZERO;
        Optional<TariffComponent> gstComponent = day == null ? Optional.empty()
                : effective(components, day).stream().filter(c -> c.unit() == TariffComponent.Unit.PERCENT).findFirst();
        if (gstComponent.isPresent()) {
            gst = subtotal.multiply(gstComponent.get().rate()).divide(HUNDRED, PRECISE);
            lines.add(line(gstComponent.get(), gst));
        }
        return new Result(energy, lines, subtotal, gst, subtotal.add(gst), kwhCounted, kwhWithoutPrice,
                halfHours.size(), counted, halfHours.size() - counted);
    }

    /** Fixed-rate plan: every half-hour so far at the contracted rate; no market price needed. */
    public static Result fixedRate(List<HalfHour> halfHours, BigDecimal centsPerKwh) {
        BigDecimal kwh = halfHours.stream().map(HalfHour::kwh).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal energy = kwh.multiply(centsPerKwh).divide(HUNDRED, PRECISE);
        return new Result(energy, List.of(), energy, BigDecimal.ZERO, energy, kwh, BigDecimal.ZERO,
                halfHours.size(), halfHours.size(), 0);
    }

    /** For each code, the version in force on that date (the latest effective_from that applies). */
    static List<TariffComponent> effective(List<TariffComponent> components, LocalDate date) {
        Map<String, TariffComponent> latest = new LinkedHashMap<>();
        for (TariffComponent component : order(components)) {
            if (component.effectiveOn(date)) {
                latest.merge(component.code(), component,
                        (a, b) -> a.effectiveFrom().isAfter(b.effectiveFrom()) ? a : b);
            }
        }
        return List.copyOf(latest.values());
    }

    private static List<TariffComponent> order(List<TariffComponent> components) {
        return components.stream()
                .sorted(Comparator.comparing((TariffComponent c) -> c.unit() == TariffComponent.Unit.PERCENT)
                        .thenComparing(c -> c.unit() == TariffComponent.Unit.SGD_PER_MONTH))
                .toList();
    }

    private static Line line(TariffComponent component, BigDecimal amount) {
        return new Line(component.code(), component.charge(), component.unit(), component.rate(), amount,
                component.source(), component.sourceUrl());
    }

    /** Money for display: 2 decimal places. */
    public static BigDecimal sgd(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
