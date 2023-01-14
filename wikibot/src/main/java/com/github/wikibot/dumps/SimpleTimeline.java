package com.github.wikibot.dumps;

import java.time.OffsetDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public final class SimpleTimeline implements Iterable<SimpleTimeline.Entry> {
    private final List<Entry> storage;

    SimpleTimeline(OffsetDateTime start, OffsetDateTime end, Period period) {
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
        Objects.requireNonNull(period);

        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }

        if (period.isNegative() || period.isZero()) {
            throw new IllegalArgumentException("period must be positive");
        }

        storage = initializeStorage(start, end, period);
    }

    SimpleTimeline(OffsetDateTime start, Period length, Period period) {
        this(start, Objects.requireNonNull(start).plus(Objects.requireNonNull(period)), period);
    }

    SimpleTimeline(OffsetDateTime start, Period period) {
        this(start, OffsetDateTime.now(), period);
    }

    private static List<Entry> initializeStorage(OffsetDateTime start, OffsetDateTime end, Period period) {
        var storage = new ArrayList<Entry>();
        storage.add(new Entry(start));

        return storage;
    }

    @Override
    public Iterator<Entry> iterator() {
        return storage.iterator();
    }

    @Override
    public String toString() {
        return storage.toString();
    }

    public static final class Entry implements Comparable<OffsetDateTime> {
        private final OffsetDateTime time;
        private long value;

        Entry(OffsetDateTime time) {
            this.time = Objects.requireNonNull(time);
        }

        public OffsetDateTime getTime() {
            return time;
        }

        public long getValue() {
            return value;
        }

        public void setValue(long value) {
            this.value = value;
        }

        @Override
        public int hashCode() {
            return time.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            } else if (obj instanceof Entry other) {
                return time.equals(other.time);
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            return String.format("[%s,%d]", time.toString(), value);
        }

        @Override
        public int compareTo(OffsetDateTime other) {
            return time.compareTo(other);
        }
    }
}
