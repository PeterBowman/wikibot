package com.github.wikibot.dumps;

import java.time.OffsetDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public final class CompoundTimeline<E extends Enum<E>> implements Iterable<CompoundTimeline.Entry<E>> {
    private final List<Entry<E>> storage;

    CompoundTimeline(OffsetDateTime start, OffsetDateTime end, Period period, Class<E> clazz) {
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
        Objects.requireNonNull(period);

        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }

        if (period.isNegative() || period.isZero()) {
            throw new IllegalArgumentException("period must be positive");
        }

        storage = initializeStorage(start, end, period, clazz);
    }

    CompoundTimeline(OffsetDateTime start, Period length, Period period, Class<E> clazz) {
        this(start, Objects.requireNonNull(start).plus(Objects.requireNonNull(period)), period, clazz);
    }

    CompoundTimeline(OffsetDateTime start, Period period, Class<E> clazz) {
        this(start, OffsetDateTime.now(), period, clazz);
    }

    private static <E extends Enum<E>> List<Entry<E>>
    initializeStorage(OffsetDateTime start, OffsetDateTime end, Period period, Class<E> clazz) {
        var storage = new ArrayList<Entry<E>>();
        storage.add(new Entry<>(start, new EnumStats<>(clazz)));

        return storage;
    }

    @Override
    public Iterator<Entry<E>> iterator() {
        return storage.iterator();
    }

    @Override
    public String toString() {
        return storage.toString();
    }

    public static final class Entry<E extends Enum<E>> implements Comparable<OffsetDateTime> {
        private final OffsetDateTime time;
        private final EnumStats<E> value;

        Entry(OffsetDateTime time, EnumStats<E> value) {
            this.time = Objects.requireNonNull(time);
            this.value = Objects.requireNonNull(value);
        }

        public OffsetDateTime getTime() {
            return time;
        }

        public EnumStats<E> getValue() {
            return value;
        }

        @Override
        public int hashCode() {
            return time.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            } else if (obj instanceof Entry<?> other) {
                return time.equals(other.time);
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            return String.format("[%s,%s]", time.toString(), value.toString());
        }

        @Override
        public int compareTo(OffsetDateTime other) {
            return time.compareTo(other);
        }
    }
}
