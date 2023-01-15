package com.github.wikibot.dumps;

import java.time.OffsetDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

abstract class Timeline<T> implements Iterable<Timeline.Entry<T>> {
    private final List<Entry<T>> storage;

    Timeline(OffsetDateTime start, OffsetDateTime end, Period period) {
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
        Objects.requireNonNull(period);

        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }

        if (period.isNegative() || period.isZero()) {
            throw new IllegalArgumentException("period must be positive");
        }

        this.storage = initializeStorage(start, end, period);
    }

    private List<Entry<T>> initializeStorage(OffsetDateTime start, OffsetDateTime end, Period period) {
        var storage = new ArrayList<Entry<T>>();

        for (int i = 0; ; i++) {
            var time = start.plus(period.multipliedBy(i));

            if (time.isAfter(end)) {
                break;
            }

            storage.add(makeEntry(time));
        }

        return storage;
    }

    protected abstract Entry<T> makeEntry(OffsetDateTime time);

    @Override
    public Iterator<Entry<T>> iterator() {
        return storage.iterator();
    }

    @Override
    public String toString() {
        return storage.toString();
    }

    static class Entry<T> implements Comparable<OffsetDateTime> {
        private final OffsetDateTime time;
        protected T value;

        Entry(OffsetDateTime time, T value) {
            this.time = Objects.requireNonNull(time);
            this.value = Objects.requireNonNull(value);
        }

        public OffsetDateTime getTime() {
            return time;
        }

        public T getValue() {
            return value;
        }

        public void setValue(T value) {
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
