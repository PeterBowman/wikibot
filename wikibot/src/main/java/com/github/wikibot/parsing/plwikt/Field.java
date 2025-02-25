package com.github.wikibot.parsing.plwikt;

import java.security.InvalidParameterException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;

public class Field implements Comparable<Field> {
    private static final String[] NEWLINE_MARKERS = new String[] { ":", "*", "#", ";" };

    private FieldTypes fieldType;
    protected String content;
    private boolean isNewLine;
    private int leadingNewlines;
    private int trailingNewlines;
    private Character eolMark;
    Section containingSection;
    private UUID uuid;

    protected Field(FieldTypes fieldType, String content) {
        this.fieldType = fieldType;
        this.content = content.replaceAll("^ +", "");
        this.leadingNewlines = 0;
        this.trailingNewlines = 0;
        this.containingSection = null;
        this.uuid = UUID.randomUUID();

        extractContent();
    }

    static Field parseField(FieldTypes fieldType, String content) {
        return switch (fieldType) {
            case DEFINITIONS -> new DefinitionsField(fieldType, content);
            default -> new Field(fieldType, content);
        };
    }

    public FieldTypes getFieldType() {
        return fieldType;
    }

    public boolean isEmpty() {
        if (content.isBlank()) {
            return true;
        } else if (fieldType == FieldTypes.EXAMPLES && content.trim().equals(": (1.1)")) {
            return true;
        }

        return false;
    }

    public boolean isNewLine() {
        return isNewLine;
    }

    public String getContent() {
        return content;
    }

    public Field editContent(String content) {
        return editContent(content, isNewLine);
    }

    public Field editContent(String content, boolean isNewline) {
        this.content = content;
        this.isNewLine = isNewline;
        normalizeNewlines();
        return this;
    }

    private void extractContent() {
        while (content.endsWith("\n")) {
            trailingNewlines++;
            content = content.substring(0, content.length() - 1);
        }

        while (content.startsWith("\n")) {
            leadingNewlines++;
            content = content.substring(1, content.length());
        }

        if (!content.isEmpty() && leadingNewlines > 0) {
            isNewLine = true;
            leadingNewlines--;
        } else if (content.isEmpty() && trailingNewlines > 0) {
            isNewLine = true;
            trailingNewlines = Math.max(trailingNewlines - 1, 1);
        } else {
            isNewLine = false;
        }

        if (trailingNewlines > 0 || content.isEmpty()) {
            eolMark = '\n';
            trailingNewlines = Math.max(trailingNewlines - 1, 0);
        } else {
            eolMark = null;
        }
    }

    void normalizeNewlines() {
        leadingNewlines = 0;
        trailingNewlines = 0;
        content = content.trim();

        if (content.isEmpty()) {
            return;
        }

        boolean suppressColon = StringUtils.startsWithAny(content, NEWLINE_MARKERS) || content.startsWith("{{współczesna}}");

        if (!isNewLine && suppressColon) {
            isNewLine = true;
        }

        if (isNewLine && !suppressColon && fieldType != FieldTypes.DEFINITIONS && fieldType != FieldTypes.SOURCES) {
            content = ": " + content;
        }
    }

    void setLeadingNewlines(int leadingNewlines) {
        if (leadingNewlines < 0) {
            throw new InvalidParameterException("\"leadingNewlines\" cannot be negative");
        }

        if (!content.isEmpty()) {
            this.leadingNewlines = leadingNewlines;
        } else {
            this.trailingNewlines += leadingNewlines;
        }
    }

    void setTrailingNewlines(int trailingNewlines) {
        if (trailingNewlines < 0) {
            throw new InvalidParameterException("\"trailingNewlines\" cannot be negative");
        }

        this.trailingNewlines = trailingNewlines;
    }

    void setEolMark(Character eolMark) {
        this.eolMark = eolMark;
    }

    public Optional<Section> getContainingSection() {
        return Optional.ofNullable(containingSection);
    }

    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        } else if (o instanceof Field f) {
            return uuid.equals(f.uuid);
        } else {
            return false;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(content.length());
        sb.append(String.format("{{%s}}", fieldType.localised()));

        if (!content.isEmpty()) {
            if (isNewLine) {
                sb.append("\n");
            } else {
                sb.append(" ");
            }
        }

        sb.append("\n".repeat(leadingNewlines));
        sb.append(content);
        sb.append("\n".repeat(trailingNewlines));
        sb.append(Objects.requireNonNullElse(eolMark, ""));

        return sb.toString();
    }

    @Override
    public int compareTo(Field f) {
        return Integer.compare(fieldType.ordinal(), f.getFieldType().ordinal());
    }
}
