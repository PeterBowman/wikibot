package com.github.wikibot.utils;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

public class PageContainer implements Serializable {
    private static final long serialVersionUID = -2537816315120752316L;

    protected String title;
    protected String text;
    protected long revid;
    protected OffsetDateTime timestamp;

    public PageContainer(String title, String text, long revid, OffsetDateTime timestamp) {
        this.title = Objects.requireNonNull(title);
        this.text = Objects.requireNonNull(text);
        this.revid = revid;
        this.timestamp = Objects.requireNonNull(timestamp);
    }

    public String getTitle() {
        return title;
    }

    public String getText() {
        return text;
    }

    public long getRevid() {
        return revid;
    }

    public OffsetDateTime getTimestamp() {
        return timestamp;
    }

    public String toString() {
        return String.format("%s = %s", title, text);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(revid);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        } else if (obj instanceof PageContainer pc) {
            return revid == pc.revid;
        } else {
            return false;
        }
    }
}
