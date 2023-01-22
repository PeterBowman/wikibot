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
        return new TimelineCollector<>(
            () -> new SimpleTimeline(start, end, period),
            rev  -> condition.test(rev) ? 1L : 0L
        );
    }

    public static Collector<XMLRevision, ?, SimpleTimeline>
    mapping(OffsetDateTime start, OffsetDateTime end, Period period, ToLongFunction<XMLRevision> mapper) {
        return new TimelineCollector<>(
            () -> new SimpleTimeline(start, end, period),
            rev -> mapper.applyAsLong(rev)
        );
    }

    public static <E extends Enum<E>> Collector<XMLRevision, ?, CompoundTimeline<E>>
    consuming(OffsetDateTime start, OffsetDateTime end, Period period, Class<E> clazz, BiConsumer<XMLRevision, EnumStats<E>> consumer) {
        return new TimelineCollector<>(
            () -> new CompoundTimeline<>(start, end, period, clazz),
            rev -> {
                var stats = new EnumStats<>(clazz);
                consumer.accept(rev, stats);
                return stats;
            });
    }

    private static class TimelineCollector<V, T extends Timeline<V>> implements Collector<XMLRevision, T, T> {
        private final Supplier<T> supplier;
        private final Function<XMLRevision, V> processor;

        private XMLRevision previousRev;
        private Iterator<Timeline.Entry<V>> iterator;
        private Timeline.Entry<V> referenceEntry;

        public TimelineCollector(Supplier<T> supplier, Function<XMLRevision, V> bifunction) {
            this.supplier = Objects.requireNonNull(supplier);
            this.processor = Objects.requireNonNull(bifunction);
        }

        @Override
        public Supplier<T> supplier() {
            return supplier;
        }

        @Override
        public BiConsumer<T, XMLRevision> accumulator() {
            return (timeline, rev) -> {
                if (previousRev == null || previousRev.getPageid() != rev.getPageid()) {
                    // either the first revision ever processed, or a new page

                    if (referenceEntry != null && previousRev != null) {
                        // process the last revision of the previous page
                        applyValue(previousRev);
                    }

                    iterator = timeline.iterator();

                    var thisTimestamp = OffsetDateTime.parse(rev.getTimestamp());

                    while (iterator.hasNext()) {
                        // advance the iterator until the closest timeline entry after this revision
                        var entry = iterator.next();

                        if (entry.getTime().isAfter(thisTimestamp)) {
                            referenceEntry = entry;
                            break;
                        }
                    }
                } else if (referenceEntry != null) {
                    // the same page as the previous revision, and still within the targeted timeline range
                    var thisTimestamp = OffsetDateTime.parse(rev.getTimestamp());

                    if (thisTimestamp.isAfter(referenceEntry.getTime())) {
                        // this revision is newer than the last timeline entry, therefore process it
                        applyValue(previousRev, thisTimestamp);
                    }
                }

                previousRev = rev;
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
                // process the last revision of the last page
                applyValue(previousRev);
            }

            return UnaryOperator.identity();
        }

        @Override
        public Set<Characteristics> characteristics() {
            return Collections.emptySet();
        }

        private void applyValue(XMLRevision rev) {
            applyValue(rev, null);
        }

        private void applyValue(XMLRevision rev, OffsetDateTime refTime) {
            var value = processor.apply(rev);
            referenceEntry.combine(value);

            while (iterator.hasNext()) {
                // advance the iterator until the closest timeline entry after this revision
                referenceEntry = iterator.next();

                if (refTime != null && referenceEntry.getTime().isAfter(refTime)) {
                    return; // preserve this entry for later
                }

                // apply the same value to all intermediate timeline entries
                referenceEntry.combine(value);
            }

            referenceEntry = null; // timeline depleted, no eligible entries left
        }
    }
}
