package com.github.wikibot.dumps;

import java.time.OffsetDateTime;
import java.time.Period;

public final class SimpleTimeline extends Timeline<Long> {
    SimpleTimeline(OffsetDateTime start, OffsetDateTime end, Period period) {
        super(start, end, period);
    }

    @Override
    protected Entry makeEntry(OffsetDateTime time) {
        return new Entry(time, 0L);
    }

    public static class Entry extends Timeline.Entry<Long> {
        Entry(OffsetDateTime time, long value) {
            super(time, value);
        }

        @Override
        public Long combine(Long other) {
            value += other;
            return other;
        }
    }
}
