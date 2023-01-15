package com.github.wikibot.dumps;

import java.time.OffsetDateTime;
import java.time.Period;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Collector;

public final class TimelineCollectors {
    private TimelineCollectors() {
        throw new AssertionError("Non-instantiable class");
    }

    public static Collector<XMLRevision, ?, SimpleTimeline>
    filtering(OffsetDateTime start, OffsetDateTime end, Period period, Predicate<XMLRevision> condition) {
        return new TimelineCollector<>(
            () -> new SimpleTimeline(start, end, period),
            (rev, acc) -> condition.test(rev) ? acc + 1 : acc);
    }

    public static Collector<XMLRevision, ?, SimpleTimeline>
    filtering(OffsetDateTime start, OffsetDateTime end, Period period, ToLongFunction<XMLRevision> mapper) {
        return new TimelineCollector<>(
            () -> new SimpleTimeline(start, end, period),
            (rev, acc) -> mapper.applyAsLong(rev));
    }

    public static <E extends Enum<E>> Collector<XMLRevision, ?, CompoundTimeline<E>>
    filtering(OffsetDateTime start, OffsetDateTime end, Period period, Class<E> clazz, BiConsumer<XMLRevision, EnumStats<E>> consumer) {
        return new TimelineCollector<>(
            () -> new CompoundTimeline<>(start, end, period, clazz),
            (rev, acc) -> { consumer.accept(rev, acc); return acc; });
    }

    private static class TimelineCollector<V, T extends Timeline<V>> implements Collector<XMLRevision, T, T> {
        private final Supplier<T> supplier;
        private final BiFunction<XMLRevision, V, V> bifunction;

        private XMLRevision previousRev;
        private Iterator<Timeline.Entry<V>> iterator;
        private Timeline.Entry<V> referenceEntry;

        public TimelineCollector(Supplier<T> supplier, BiFunction<XMLRevision, V, V> bifunction) {
            this.supplier = Objects.requireNonNull(supplier);
            this.bifunction = Objects.requireNonNull(bifunction);
        }

        @Override
        public Supplier<T> supplier() {
            return supplier;
        }

        @Override
        public BiConsumer<T, XMLRevision> accumulator() {
            return (timeline, rev) -> {
                if (previousRev == null || previousRev.getPageid() != rev.getPageid()) {
                    if (referenceEntry != null && previousRev != null) {
                        referenceEntry.setValue(bifunction.apply(rev, referenceEntry.getValue()));
                    }

                    previousRev = rev;
                    iterator = timeline.iterator();
                    referenceEntry = null;

                    var thisTimestamp = OffsetDateTime.parse(rev.getTimestamp());

                    while (iterator.hasNext()) {
                        var entry = iterator.next();

                        if (entry.getTime().isAfter(thisTimestamp)) {
                            referenceEntry = entry;
                            break;
                        }
                    }
                } else {
                    var thisTimestamp = OffsetDateTime.parse(rev.getTimestamp());

                    if (referenceEntry != null && thisTimestamp.isAfter(referenceEntry.getTime())) {
                        referenceEntry.setValue(bifunction.apply(rev, referenceEntry.getValue()));

                        if (iterator.hasNext()) {
                            referenceEntry = iterator.next();
                        } else {
                            referenceEntry = null;
                        }
                    }

                    previousRev = rev;
                }
            };
        }

        @Override
        public BinaryOperator<T> combiner() {
            // should never be called on sequential streams
            return null;
        }

        @Override
        public Function<T, T> finisher() {
            if (referenceEntry != null && previousRev != null) {
                referenceEntry.setValue(bifunction.apply(previousRev, referenceEntry.getValue()));
            }

            return UnaryOperator.identity();
        }

        @Override
        public Set<Characteristics> characteristics() {
            return Collections.emptySet();
        }
    }
}
