package com.github.wikibot.dumps;

import java.time.OffsetDateTime;
import java.time.Period;
import java.util.Objects;

public final class CompoundTimeline<E extends Enum<E>> extends Timeline<EnumStats<E>> {
    private final Class<E> clazz;

    CompoundTimeline(OffsetDateTime start, OffsetDateTime end, Period period, Class<E> clazz) {
        super(start, end, period);
        this.clazz = Objects.requireNonNull(clazz);
    }

    @Override
    protected Entry<E> makeEntry(OffsetDateTime time) {
        return new Entry<>(time, new EnumStats<>(clazz));
    }

    public static class Entry<E extends Enum<E>> extends Timeline.Entry<EnumStats<E>> {
        Entry(OffsetDateTime time, EnumStats<E> value) {
            super(time, value);
        }

        @Override
        public EnumStats<E> combine(EnumStats<E> other) {
            return value.combine(other);
        }
    }
}
