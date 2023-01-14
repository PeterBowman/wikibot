package com.github.wikibot.dumps;

import java.time.OffsetDateTime;
import java.time.Period;
import java.util.Collections;
import java.util.Iterator;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
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
        return new SimpleTimelineCollector(
            () -> new SimpleTimeline(start, end, period),
            rev -> condition.test(rev) ? 1L : 0L);
    }

    public static Collector<XMLRevision, ?, SimpleTimeline>
    filtering(OffsetDateTime start, OffsetDateTime end, Period period, ToLongFunction<XMLRevision> mapper) {
        return new SimpleTimelineCollector(
            () -> new SimpleTimeline(start, end, period),
            rev -> mapper.applyAsLong(rev));
    }

    public static <E extends Enum<E>> Collector<XMLRevision, ?, CompoundTimeline<E>>
    filtering(OffsetDateTime start, OffsetDateTime end, Period period, Class<E> clazz, BiConsumer<XMLRevision, EnumStats<E>> consumer) {
        return new CompoundTimelineCollector<>(
            () -> new CompoundTimeline<>(start, end, period, clazz),
            consumer);
    }

    private static class SimpleTimelineCollector implements Collector<XMLRevision, SimpleTimeline, SimpleTimeline> {
        private final Supplier<SimpleTimeline> supplier;
        private final Function<XMLRevision, Long> mapper;

        private XMLRevision previousRev;
        private Iterator<SimpleTimeline.Entry> iterator;
        private SimpleTimeline.Entry referenceEntry;

        public SimpleTimelineCollector(Supplier<SimpleTimeline> supplier, Function<XMLRevision, Long> mapper) {
            this.supplier = Objects.requireNonNull(supplier);
            this.mapper = Objects.requireNonNull(mapper);
        }

        @Override
        public Supplier<SimpleTimeline> supplier() {
            return supplier;
        }

        @Override
        public BiConsumer<SimpleTimeline, XMLRevision> accumulator() {
            return (timeline, rev) -> {
                if (previousRev == null || previousRev.getPageid() != rev.getPageid()) {
                    if (referenceEntry != null && previousRev != null) {
                        referenceEntry.setValue(referenceEntry.getValue() + mapper.apply(previousRev));
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
                        referenceEntry.setValue(referenceEntry.getValue() + mapper.apply(previousRev));

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
        public BinaryOperator<SimpleTimeline> combiner() {
            // should never be called on sequential streams
            return null;
        }

        @Override
        public Function<SimpleTimeline, SimpleTimeline> finisher() {
            if (referenceEntry != null && previousRev != null) {
                referenceEntry.setValue(referenceEntry.getValue() + mapper.apply(previousRev));
            }

            return UnaryOperator.identity();
        }

        @Override
        public Set<Characteristics> characteristics() {
            return Collections.emptySet();
        }
    }

    private static class CompoundTimelineCollector<E extends Enum<E>> implements Collector<XMLRevision, CompoundTimeline<E>, CompoundTimeline<E>> {
        private final Supplier<CompoundTimeline<E>> supplier;
        private final BiConsumer<XMLRevision, EnumStats<E>> consumer;

        private XMLRevision previousRev;
        private Iterator<CompoundTimeline.Entry<E>> iterator;
        private CompoundTimeline.Entry<E> referenceEntry;

        public CompoundTimelineCollector(Supplier<CompoundTimeline<E>> supplier, BiConsumer<XMLRevision, EnumStats<E>> consumer) {
            this.supplier = Objects.requireNonNull(supplier);
            this.consumer = Objects.requireNonNull(consumer);
        }

        @Override
        public Supplier<CompoundTimeline<E>> supplier() {
            return supplier;
        }

        @Override
        public BiConsumer<CompoundTimeline<E>, XMLRevision> accumulator() {
            return (timeline, rev) -> {
                if (previousRev == null || previousRev.getPageid() != rev.getPageid()) {
                    if (referenceEntry != null && previousRev != null) {
                        consumer.accept(rev, referenceEntry.getValue());
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
                        consumer.accept(rev, referenceEntry.getValue());

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
        public BinaryOperator<CompoundTimeline<E>> combiner() {
            // should never be called on sequential streams
            return null;
        }

        @Override
        public Function<CompoundTimeline<E>, CompoundTimeline<E>> finisher() {
            if (referenceEntry != null && previousRev != null) {
                consumer.accept(previousRev, referenceEntry.getValue());
            }

            return UnaryOperator.identity();
        }

        @Override
        public Set<Characteristics> characteristics() {
            return Collections.emptySet();
        }
    }
}
