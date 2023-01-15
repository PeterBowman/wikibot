package com.github.wikibot.dumps;

import java.time.OffsetDateTime;
import java.time.Period;

public final class SimpleTimeline extends Timeline<Long> {
    SimpleTimeline(OffsetDateTime start, OffsetDateTime end, Period period) {
        super(start, end, period);
    }

    @Override
    protected Entry<Long> makeEntry(OffsetDateTime time) {
        return new Entry<>(time, Long.valueOf(0));
    }
}
