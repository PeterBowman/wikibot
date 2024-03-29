package com.github.wikibot.parsing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.wikiutils.ParseUtils;

public abstract class AbstractSection<T extends AbstractSection<T>> {
    protected String header;
    protected String intro;
    protected int level;
    protected int tocLevel;
    protected int leadingNewlines;
    protected int trailingNewlines;
    protected String headerFormat;
    private String headerLeadingComments;
    private String headerTrailingComments;
    protected T parentSection;
    protected List<T> siblingSections;
    protected List<T> childSections;
    protected AbstractPage<T> containingPage;
    private UUID uuid;

    private static final Pattern P_HEADER_REFS = Pattern.compile("<ref\\b.*?(?:/ *?>|>.*?</ref *?>)", Pattern.CASE_INSENSITIVE);

    protected AbstractSection(String text) {
        header = "";
        intro = "";
        level = 0;
        tocLevel = 0;
        leadingNewlines = 0;
        trailingNewlines = 0;
        headerFormat = "%1$s %2$s %1$s";
        headerLeadingComments = "";
        headerTrailingComments = "";
        parentSection = null;
        // these fields will be usually overwritten by Page's parsing methods
        siblingSections = new ArrayList<>(0);
        childSections = new ArrayList<>(0);
        containingPage = null;
        uuid = UUID.randomUUID();

        if (text != null && !text.isEmpty()) {
            // FIXME: this won't parse inner sections
            parseSection(text);
        }
    }

    private void parseSection(String text) {
        String[] lines = text.split("\n", -1);
        parseHeader(lines[0]);

        if (lines.length == 1) {
            intro = "";
        } else if (String.join("", Arrays.copyOfRange(lines, 1, lines.length)).isEmpty()) {
            intro = "";
            trailingNewlines = lines.length - 1;
        } else {
            intro = text.substring(text.indexOf("\n") + 1);
            extractIntro();
        }
    }

    private void parseHeader(String header) {
        // TODO: catch "=" inside comment regions; review PageBase.P_SECTION
        int i = 6;

        for (; i >= 1; --i) {
            String re = String.format("^((?:<!--.*?-->)*+)={%1$d}(.+)={%1$d}((?:<!--.*?-->|\\s*)*)$", i);
            Matcher m = Pattern.compile(re).matcher(header);

            if (m.matches()) {
                this.headerLeadingComments = m.group(1);
                this.header = m.group(2).trim();
                this.level = i;
                this.headerTrailingComments = m.group(3);
                buildHeaderFormatString(m.group(2));
                break;
            }
        }

        if (i < 1) {
            throw new ParsingException("Parsing error (SectionBase.parseHeader)");
        }
    }

    private void buildHeaderFormatString(String header) {
        if (header.isBlank()) {
            headerFormat = "%1$s %1$s";
        } else {
            headerFormat = header.replaceAll("^( *+).+?( *+)$", "%1\\$s$1%2\\$s$2%1\\$s");
        }
    }

    protected void extractIntro() {
        while (intro.endsWith("\n")) {
            trailingNewlines++;
            intro = intro.substring(0, intro.length() - 1);
        }

        while (intro.startsWith("\n")) {
            leadingNewlines++;
            intro = intro.substring(1);
        }
    }

    public String getHeader() {
        return header;
    }

    public String getStrippedHeader() {
        String header = stripHeaderReferences(this.header);
        header = ParseUtils.removeCommentsAndNoWikiText(header);
        header = Utils.sanitizeWhitespaces(header);
        return header.trim();
    }

    public void setHeader(String header) {
        boolean wasEmpty = this.header.isEmpty();
        this.header = header.trim();

        if (wasEmpty && !this.header.isEmpty()) {
            headerFormat = "%1$s %2$s %1$s";
        } else if (!wasEmpty && this.header.isEmpty()) {
            headerFormat = "%1$s %1$s";
        }
    }

    public String getIntro() {
        return intro;
    }

    public void setIntro(String intro) {
        this.intro = intro.trim();

        if (intro.isEmpty()) {
            leadingNewlines = 0;
        }
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        if (level < 1 || level > 6) {
            throw new IllegalArgumentException("Invalid level value");
        }

        this.level = level;

        if (this.containingPage != null) {
            this.containingPage.buildSectionTree();
        }
    }

    public int getTocLevel() {
        return tocLevel;
    }

    public String getHeaderFormat() {
        return headerFormat;
    }

    public void setHeaderFormat(String headerFormat) {
        this.headerFormat = headerFormat;
    }

    public int getLeadingNewlines() {
        return leadingNewlines;
    }

    public void setLeadingNewlines(int leadingNewlines) {
        if (leadingNewlines < 0) {
            throw new IllegalArgumentException("\"leadingNewlines\" cannot be negative");
        }

        if (!intro.isEmpty()) {
            this.leadingNewlines = leadingNewlines;
        } else {
            this.trailingNewlines += leadingNewlines;
        }
    }

    public int getTrailingNewlines() {
        return trailingNewlines;
    }

    public void setTrailingNewlines(int trailingNewlines) {
        if (trailingNewlines < 0) {
            throw new IllegalArgumentException("\"trailingNewlines\" cannot be negative");
        }

        this.trailingNewlines = trailingNewlines;
    }

    public Optional<T> getParentSection() {
        return Optional.ofNullable(parentSection);
    }

    public Optional<AbstractPage<T>> getContainingPage() {
        return Optional.ofNullable(containingPage);
    }

    public List<T> getSiblingSections() {
        return Collections.unmodifiableList(new ArrayList<>(siblingSections));
    }

    public List<T> getChildSections() {
        return Collections.unmodifiableList(new ArrayList<>(childSections));
    }

    public Optional<T> nextSection() {
        if (containingPage == null) {
            throw new UnsupportedOperationException("Cannot traverse Sections with no containing Page");
        }

        int selfIndex = containingPage.sections.indexOf(this);

        try {
            return Optional.of(containingPage.sections.get(selfIndex + 1));
        } catch (IndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }

    public Optional<T> previousSection() {
        if (containingPage == null) {
            throw new UnsupportedOperationException("Cannot traverse Sections with no containing Page");
        }

        int selfIndex = containingPage.sections.indexOf(this);

        try {
            return Optional.of(containingPage.sections.get(selfIndex - 1));
        } catch (IndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }

    public Optional<T> nextSiblingSection() {
        if (containingPage == null) {
            throw new UnsupportedOperationException("Cannot traverse Sections with no containing Page");
        }

        if (siblingSections.isEmpty()) {
            throw new UnsupportedOperationException("No sibling Sections attached");
        }

        int selfIndex = siblingSections.indexOf(this);

        try {
            return Optional.of(siblingSections.get(selfIndex + 1));
        } catch (IndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }

    public Optional<T> previousSiblingSection() {
        if (containingPage == null) {
            throw new UnsupportedOperationException("Cannot traverse Sections with no containing Page");
        }

        if (siblingSections.isEmpty()) {
            throw new UnsupportedOperationException("No sibling Sections attached");
        }

        int selfIndex = siblingSections.indexOf(this);

        try {
            return Optional.of(siblingSections.get(selfIndex - 1));
        } catch (IndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }

    public void appendSections(List<T> sections) {
        if (sections.isEmpty()) {
            return;
        }

        for (T section : sections) {
            if (section.level <= level) {
                throw new UnsupportedOperationException("Invalid level of appended Sections: " + section.level + " (must be > " + level + ")");
            }
        }

        if (containingPage != null) {
            var flattened = flattenSubSections(sections);

            if (!childSections.isEmpty()) {
                T lastChild = childSections.get(childSections.size() - 1);
                int index = containingPage.sections.indexOf(lastChild);
                containingPage.sections.addAll(index + 1, flattened);
            } else {
                int index = containingPage.sections.indexOf(this);
                containingPage.sections.addAll(index + 1, flattened);
            }

            containingPage.buildSectionTree();
        } else {
            childSections.addAll(sections);
        }
    }

    public void prependSections(List<T> sections) {
        if (sections.isEmpty()) {
            return;
        }

        for (T section : sections) {
            if (section.level <= level) {
                throw new UnsupportedOperationException("Invalid level of prepended Sections: " + section.level + " (must be > " + level + ")");
            }
        }

        if (containingPage != null) {
            int index = containingPage.sections.indexOf(this);
            var flattened = flattenSubSections(sections);
            containingPage.sections.addAll(index + 1, flattened);
            containingPage.buildSectionTree();
        } else {
            childSections.addAll(0, sections);
        }
    }

    public void insertSectionsAfter(List<T> sections) {
        // TODO: don't throw if parentSection is non null
        if (containingPage == null) {
            throw new UnsupportedOperationException("Cannot insert Sections with no containing Page");
        }

        if (sections.isEmpty()) {
            return;
        }

        for (T section : sections) {
            if (section.level != level) {
                throw new UnsupportedOperationException("Invalid level of prepended Sections: " + section.level + " (must be == " + level + ")");
            }
        }

        int index = siblingSections.indexOf(this);
        siblingSections.addAll(index + 1, sections);

        if (parentSection != null) {
            parentSection.propagateTree();
        } else {
            containingPage.sections = flattenSubSections(siblingSections);
            containingPage.buildSectionTree();
        }
    }

    public void insertSectionsBefore(List<T> sections) {
        // TODO: don't throw if parentSection is non null
        if (containingPage == null) {
            throw new UnsupportedOperationException("Cannot insert Sections with no containing Page");
        }

        if (sections.isEmpty()) {
            return;
        }

        for (T section : sections) {
            if (section.level != level) {
                throw new UnsupportedOperationException("Invalid level of prepended Sections: " + section.level + " (must be == " + level + ")");
            }
        }

        int index = siblingSections.indexOf(this);
        siblingSections.addAll(index, sections);

        if (parentSection != null) {
            parentSection.propagateTree();
        } else {
            containingPage.sections = flattenSubSections(siblingSections);
            containingPage.buildSectionTree();
        }
    }

    public void detach() {
        if (parentSection == null && containingPage == null) {
            throw new UnsupportedOperationException("Cannot detach Sections with no parent Section and containing Page");
        }

        if (containingPage != null) {
            containingPage.sections.remove(this);

            if (!childSections.isEmpty()) {
                var flattened = flattenSubSections(childSections);
                containingPage.sections.removeAll(flattened);
            }

            containingPage.buildSectionTree();
            containingPage = null;
        } else if (parentSection != null) {
            parentSection.childSections.remove(this);

            for (T sibling : parentSection.childSections) {
                sibling.siblingSections.remove(this);
            }

            parentSection = null;
        }
    }

    public void detachOnlySelf() {
        if (containingPage == null) {
            throw new UnsupportedOperationException("Cannot detach Sections with no containing Page");
        }

        containingPage.sections.remove(this);
        containingPage.buildSectionTree();
        containingPage = null;
    }

    public void pushLevels(int diff) {
        if (diff == 0) {
            return;
        }

        if (diff < -5 || diff > 5) {
            throw new IllegalArgumentException("Level diffs must be included either in [-5,-1] or [1,5]");
        }

        if (level + diff < 1) {
            throw new IllegalArgumentException("New level out of accepted range (SectionBase.pushLevels)");
        }

        if (!childSections.isEmpty()) {
            var subSections = flattenSubSections(childSections);

            if (diff > 0) {
                int highestLevel = subSections.stream()
                    .mapToInt(AbstractSection::getLevel)
                    .max()
                    .getAsInt();

                if (highestLevel + diff > 6) {
                    throw new IllegalArgumentException("New level out of accepted range (SectionBase.pushLevels)");
                }
            }

            for (T section : subSections) {
                int subLevel = section.getLevel();
                section.setLevel(subLevel + diff);
            }
        }

        setLevel(level + diff);
    }

    public boolean hasSubSectionWithHeader(String regex) {
        if (!childSections.isEmpty()) {
            return flattenSubSections(childSections).stream()
                .anyMatch(section -> section.getStrippedHeader().matches(regex));
        } else {
            return false;
        }
    }

    public List<T> filterSubSections(Predicate<T> predicate) {
        if (!childSections.isEmpty()) {
            return flattenSubSections(childSections).stream()
                .filter(predicate)
                .collect(Collectors.toCollection(ArrayList::new));
        } else {
            return new ArrayList<>(0);
        }
    }

    public List<T> findSubSectionsWithHeader(String regex) {
        return filterSubSections(section -> section.getStrippedHeader().matches(regex));
    }

    public void replaceWith(T section) {
        if (containingPage == null) {
            throw new UnsupportedOperationException("Cannot replace Sections with no containing Page");
        }

        if (section.getLevel() != level) {
            throw new UnsupportedOperationException("Target and replacing Sections must have the same level");
        }

        int index = containingPage.sections.indexOf(this);
        AbstractPage<T> page = containingPage;
        this.detach();
        // containingPage has been set to null
        page.sections.add(index, section);

        if (!section.childSections.isEmpty()) {
            section.propagateTree();
        } else {
            page.buildSectionTree();
        }
    }

    protected void propagateTree() {
        if (containingPage == null || childSections.isEmpty()) {
            return;
        }

        int index = containingPage.sections.indexOf(this);

        var flattened = flattenSubSections(childSections);
        containingPage.sections.removeAll(flattened);
        containingPage.sections.addAll(index + 1, flattened);
        containingPage.buildSectionTree();
    }

    public String getFlattenedContent() {
        var sb = new StringBuilder(1500);
        sb.append(intro);
        sb.append("\n".repeat(trailingNewlines));

        if (!childSections.isEmpty()) {
            sb.append("\n");

            for (T subSection : childSections) {
                sb.append(subSection);
                sb.append("\n");
            }

            sb.deleteCharAt(sb.length() - 1);
        }

        return sb.toString().trim();
    }

    public static <U extends AbstractSection<U>> List<U> flattenSubSections(U section) {
        return flattenSubSections(List.of(section));
    }

    public static <U extends AbstractSection<U>> List<U> flattenSubSections(List<U> sections) {
        var list = new ArrayList<U>();

        for (var section : sections) {
            list.add(section);

            if (!section.childSections.isEmpty()) {
                list.addAll(flattenSubSections(section.childSections));
            }
        }

        return list;
    }

    public static String stripHeaderReferences(String header) {
        return P_HEADER_REFS.matcher(header).replaceAll("").trim();
    }

    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        } else if (obj instanceof AbstractSection<?> s) {
            return uuid.equals(s.uuid);
        } else {
            return false;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(1500);
        sb.append(headerLeadingComments);
        sb.append(String.format(headerFormat, "=".repeat(level), header));
        sb.append(headerTrailingComments);

        if (!intro.isEmpty()) {
            sb.append("\n");
        }

        sb.append("\n".repeat(leadingNewlines));
        sb.append(intro);
        sb.append("\n".repeat(trailingNewlines));

        if (!childSections.isEmpty()) {
            sb.append("\n");

            for (T subSection : childSections) {
                sb.append(subSection);
                sb.append("\n");
            }

            sb.deleteCharAt(sb.length() - 1);
        }

        return sb.toString();
    }
}
