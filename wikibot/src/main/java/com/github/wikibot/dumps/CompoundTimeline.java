package com.github.wikibot.dumps;

import java.time.OffsetDateTime;
import java.time.Period;

public final class CompoundTimeline<E extends Enum<E>> extends Timeline<EnumStats<E>> {
    CompoundTimeline(OffsetDateTime start, OffsetDateTime end, Period period, Class<E> clazz) {
        super(start, end, period, (time) -> new Entry<>(time, new EnumStats<>(clazz)));
    }

    public static class Entry<E extends Enum<E>> extends Timeline.Entry<EnumStats<E>> {
        Entry(OffsetDateTime time, EnumStats<E> value) {
            super(time, value);
        }

        @Override
        public void combine(EnumStats<E> other) {
            value.combine(other);
        }
    }
}
