package org.greenplum.pxf.plugins.jdbc;

import lombok.Getter;

import java.util.EnumSet;

import static org.greenplum.pxf.plugins.jdbc.IntervalType.*;

@Getter
public class Interval {

    IntervalType type;
    protected long value;

    public static class LongInterval extends Interval {
        public LongInterval(String interval) {
            this.value = Long.parseLong(interval);
            this.type = IntervalType.NUMBER;
        }
    }

    public static class DateInterval extends Interval {

        private static final EnumSet<IntervalType> DATE_INTERVAL_TYPES = EnumSet.of(DAY, MONTH, YEAR);

        public DateInterval(String interval) {
            String[] intervalSplit = interval.split(":");
            if (intervalSplit.length != 2) {
                throw new IllegalArgumentException(
                        "The parameter 'INTERVAL' has invalid format. The correct format for partition of type DATE is '<interval_num>:{year|month|day}'"
                );
            }
            this.value = Integer.parseInt(intervalSplit[0]);
            String intervalType = intervalSplit[1];
            this.type = IntervalType.typeOf(intervalType);
            if (!DATE_INTERVAL_TYPES.contains(type)) {
                throw new IllegalArgumentException(String.format("Invalid date interval '%s'", intervalType));
            }
        }
    }

    public static class TimestampInterval extends Interval {

        private static final EnumSet<IntervalType> TIMESTAMP_INTERVAL_TYPES = EnumSet.of(SECOND, MINUTE, HOUR, DAY, MONTH, YEAR);

        public TimestampInterval(String interval) {
            String[] intervalSplit = interval.split(":");
            if (intervalSplit.length != 2) {
                throw new IllegalArgumentException(
                        "The parameter 'INTERVAL' has invalid format. The correct format for partition of type TIMESTAMP is '<interval_num>:{year|month|day|hour|minute|second}'"
                );
            }
            this.value = Integer.parseInt(intervalSplit[0]);
            String intervalType = intervalSplit[1];
            this.type = IntervalType.typeOf(intervalType);
            if (!TIMESTAMP_INTERVAL_TYPES.contains(type)) {
                throw new IllegalArgumentException(String.format("Invalid timestamp interval '%s'", intervalType));
            }
        }
    }
}
