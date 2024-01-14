package com.github.wikibot.utils;

import java.time.OffsetDateTime;

public record PageContainer(String title, String text, long revid, OffsetDateTime timestamp) {
    @Override
    public int hashCode() {
        return Long.hashCode(revid); // better performance?
    }
}
