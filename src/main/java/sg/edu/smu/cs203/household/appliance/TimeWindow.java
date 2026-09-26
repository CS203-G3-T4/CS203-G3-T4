package sg.edu.smu.cs203.household.appliance;

import java.time.LocalTime;

/**
 * A daily time-of-day window such as 07:00 -> 21:00, which may cross midnight (22:00 -> 07:00).
 * The same start and end (e.g. 00:00 -> 00:00) means "any time of day".
 * An end of 00:00 means "by midnight", so 07:00 -> 00:00 is 17 hours and does not cross midnight.
 */
public final class TimeWindow {

    static final int MINUTES_PER_DAY = 24 * 60;

    private final LocalTime start;
    private final LocalTime end;

    private TimeWindow(LocalTime start, LocalTime end) {
        this.start = start;
        this.end = end;
    }

    public static TimeWindow of(LocalTime start, LocalTime end) {
        if (start == null || end == null) {
            throw new IllegalArgumentException("A time window needs both a start and an end");
        }
        return new TimeWindow(start, end);
    }

    public LocalTime start() {
        return start;
    }

    public LocalTime end() {
        return end;
    }

    public boolean isAllDay() {
        return start.equals(end);
    }

    /** True for windows like 22:00 -> 07:00 that finish on the next calendar day. */
    public boolean crossesMidnight() {
        if (isAllDay() || end.equals(LocalTime.MIDNIGHT)) {
            return false;
        }
        return end.isBefore(start);
    }

    public int lengthMinutes() {
        if (isAllDay()) {
            return MINUTES_PER_DAY;
        }
        int length = minuteOfDay(end) - minuteOfDay(start);
        if (length <= 0) {
            length = length + MINUTES_PER_DAY;
        }
        return length;
    }

    public boolean fitsRunOf(int runMinutes) {
        return runMinutes <= lengthMinutes();
    }

    /** Can a run of this length start at this time and still finish inside the window? */
    public boolean allowsStartAt(LocalTime startTime, int runMinutes) {
        if (isAllDay()) {
            return runMinutes <= MINUTES_PER_DAY;
        }
        int offset = minuteOfDay(startTime) - minuteOfDay(start);
        if (offset < 0) {
            offset = offset + MINUTES_PER_DAY;
        }
        return offset + runMinutes <= lengthMinutes();
    }

    /** Latest start that still finishes by the end of the window (null if the run doesn't fit). */
    public LocalTime latestStartFor(int runMinutes) {
        if (!fitsRunOf(runMinutes)) {
            return null;
        }
        if (isAllDay()) {
            return start.minusMinutes(runMinutes);
        }
        return end.minusMinutes(runMinutes);
    }

    private static int minuteOfDay(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }
}
