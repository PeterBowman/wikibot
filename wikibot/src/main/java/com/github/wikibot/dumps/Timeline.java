package com.github.wikibot.dumps;

import java.time.OffsetDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Spliterator;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public abstract class Timeline<T> implements Iterable<Timeline.Entry<T>> {
    private final List<Entry<T>> storage;

    Timeline(OffsetDateTime start, OffsetDateTime end, Period period, Function<OffsetDateTime, Entry<T>> entrySupplier) {
        Objects.requireNonNull(start);
        Objects.requireNonNull(end);
        Objects.requireNonNull(period);
        Objects.requireNonNull(entrySupplier);

        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("end must be after start");
        }

        if (period.isNegative() || period.isZero()) {
            throw new IllegalArgumentException("period must be positive");
        }

        // beware of https://stackoverflow.com/q/13440375/10404307
        this.storage = initializeStorage(start, end, period, entrySupplier);
    }

    private List<Entry<T>> initializeStorage(OffsetDateTime start, OffsetDateTime end, Period period, Function<OffsetDateTime, Entry<T>> entrySupplier) {
        var storage = new ArrayList<Entry<T>>();

        for (int i = 0; ; i++) {
            var time = start.plus(period.multipliedBy(i));

            if (time.isAfter(end)) {
                break;
            }

            storage.add(entrySupplier.apply(time));
        }

        return storage;
    }

    @Override
    public Iterator<Entry<T>> iterator() {
        return storage.iterator();
    }

    @Override
    public Spliterator<Entry<T>> spliterator() {
        return storage.spliterator();
    }

    public Stream<Entry<T>> stream() {
        return StreamSupport.stream(spliterator(), false);
    }

    @Override
    public String toString() {
        return storage.toString();
    }

    public static abstract class Entry<T> implements Comparable<Entry<T>> {
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

        public abstract void combine(T other);

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
            return String.format("[%s, %s]", time.toString(), value.toString());
        }

        @Override
        public int compareTo(Entry<T> other) {
            return time.compareTo(other.time);
        }
    }
}
