package com.github.wikibot.parsing.plwikt;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.github.wikibot.parsing.AbstractSection;
import com.github.wikibot.parsing.ParsingException;

public class Section extends AbstractSection<Section> implements Comparable<Section> {
    private List<Field> fields;
    private String lang;
    private String langShort;
    private SectionTypes sectionType;
    private String headerTitle;
    private static final Pattern P_HEADER = Pattern.compile("^(.*?) *?\\(\\{\\{(.+?)(?:\\|[^\\}]*?)?\\}\\}\\)$");

    Section() {
        super(null);

        this.lang = "";
        this.langShort = "";
        this.headerTitle = "";
        this.fields = new ArrayList<>();
    }

    Section(String text) {
        super(text);

        if (level != 2) {
            throw new ParsingException("Invalid section level: " + level);
        } else if (text.isEmpty()) {
            throw new ParsingException("Empty text parameter (Section.constructor)");
        }

        this.fields = new ArrayList<>();

        parseSection();
        extractHeader();
        identifySectionType();
    }

    public static Section parse(String text) {
        Objects.requireNonNull(text);
        text = text.trim() + "\n";
        return new Section(text);
    }

    public static Section create(String lang, String title) {
        Objects.requireNonNull(lang);
        Objects.requireNonNull(title);

        Section section = new Section();

        section.setLevel(2);
        section.setHeader(String.format("%s ({{%s}})", title, lang));
        section.identifySectionType();
        section.addMissingFields();

        return section;
    }

    public String getLang() {
        return lang;
    }

    public String getLangShort() {
        return langShort;
    }

    public void setLang(String lang) {
        this.lang = lang;
        this.langShort = lang.replaceAll("^język ", "");

        updateHeader();

        if (containingPage != null && containingPage instanceof Page page) {
            page.sortSections();
        }
    }

    public boolean isPolishSection() {
        return "język polski".equals(lang) || "termin obcy w języku polskim".equals(lang);
    }

    public String getHeaderTitle() {
        return headerTitle;
    }

    public void setHeaderTitle(String headerTitle) {
        this.headerTitle = headerTitle;
        updateHeader();
    }

    @Override
    public void setHeader(String header) {
        this.header = header;
        extractHeader();
    }

    private void parseSection() {
        intro += "\n".repeat(trailingNewlines);

        SortedMap<Integer, FieldTypes> indexMap = Stream.of(FieldTypes.values())
            .collect(Collectors.toMap(
                field -> intro.indexOf(String.format("{{%s}}", field.localised())),
                field -> field,
                (k1, k2) -> k1,
                TreeMap::new
            ));

        indexMap.remove(-1);
        Integer[] keys = indexMap.keySet().toArray(new Integer[indexMap.size()]);

        for (int i = 0; i < keys.length; i++) {
            FieldTypes fieldType = indexMap.get(keys[i]);
            String template = String.format("{{%s}}", fieldType.localised());
            int bound;

            try {
                bound = keys[i + 1];
            } catch (ArrayIndexOutOfBoundsException e) {
                bound = intro.length();
            }

            String content = intro.substring(keys[i] + template.length(), bound);
            Field field = Field.parseField(fieldType, content);
            field.containingSection = this;
            fields.add(field);
        }

        if (!indexMap.isEmpty()) {
            intro = intro.substring(0, Math.max(indexMap.firstKey() - 1, 0));
            leadingNewlines = 0;
            trailingNewlines = 0;
            extractIntro();
        }
    }

    private void extractHeader() {
        Matcher m = P_HEADER.matcher(header);

        if (!m.matches()) {
            throw new ParsingException("Invalid header format: " + header);
        }

        headerTitle = m.group(1);
        lang = m.group(2).strip();
        langShort = lang.replaceFirst("^język ", "");
    }

    private void updateHeader() {
        Matcher m = P_HEADER.matcher(header);

        if (!m.matches()) {
            throw new ParsingException("Invalid header format: " + header);
        }

        String pre = header.substring(0, m.start(1));
        String mid = header.substring(m.end(1), m.start(2));
        String post = header.substring(m.end(2));
        header = String.format("%s%s%s%s%s", pre, headerTitle, mid, lang, post);
    }

    private void identifySectionType() {
        sectionType = switch (lang) {
            case "język polski", "termin obcy w języku polskim" -> SectionTypes.POLISH;
            case "znak chiński" -> SectionTypes.CHINESE_SIGN;
            case "język chiński standardowy" -> SectionTypes.CHINESE;
            case "język staroegipski" -> SectionTypes.EGYPTIAN;
            case "język koreański" -> SectionTypes.KOREAN;
            case "język japoński" -> SectionTypes.JAPANESE;
            case "esperanto" -> SectionTypes.ESPERANTO;
            case "esperanto (morfem)" -> SectionTypes.ESPERANTO_M;
            case "użycie międzynarodowe" -> SectionTypes.INTERNATIONAL;
            default -> SectionTypes.LATIN;
        };
    }

    private void addMissingFields() {
        for (FieldTypes fieldType : sectionType.fieldTypes()) {
            if (!fields.stream().anyMatch(field -> field.getFieldType().equals(fieldType))) {
                fields.add(Field.parseField(fieldType, ""));
            }
        }
    }

    // TODO
    @SuppressWarnings("unused")
    private void removeWrongFields() {
        List<FieldTypes> onlyThisFields = Arrays.asList(sectionType.fieldTypes());
        Iterator<Field> iterator = fields.iterator();

        while (iterator.hasNext()) {
            Field field = iterator.next();

            if (!onlyThisFields.contains(field.getFieldType())) {
                iterator.remove();
            }
        }
    }

    private void sortFields() {
        Collections.sort(fields);
    }

    public Optional<? extends Field> getField(FieldTypes fieldType) {
        return fields.stream()
            .filter(f -> f.getFieldType().equals(fieldType))
            .findAny();
    }

    public Field addField(FieldTypes fieldType, String text, boolean isNewline) {
        var fieldOpt = getField(fieldType);

        if (fieldOpt.isPresent()) {
            var field = fieldOpt.get();
            field.editContent(text, isNewline);
            return field;
        } else {
            var sb = new StringBuilder(isNewline ? "\n" : " ");
            var field = Field.parseField(fieldType, sb.append(text).toString());
            field.containingSection = this;
            fields.add(field);
            sortFields();
            return field;
        }
    }

    public boolean removeField(FieldTypes fieldType) {
        Optional<? extends Field> fieldOpt = getField(fieldType);

        if (!fieldOpt.isPresent()) {
            return false;
        }

        fields.remove(fieldOpt.get());
        return true;
    }

    public List<Field> getAllFields() {
        return Collections.unmodifiableList(new ArrayList<>(fields));
    }

    @Override
    public int compareTo(Section s) {
        final List<String> list = List.of("użycie międzynarodowe", "polski", "termin obcy w języku polskim");
        String targetLang = s.getLangShort();
        boolean containsSelf = list.contains(langShort);
        boolean containsOther = list.contains(targetLang);

        if (containsSelf && containsOther) {
            return Integer.compare(list.indexOf(langShort), list.indexOf(targetLang));
        } else if (!containsSelf && !containsOther) {
            Collator collator = Collator.getInstance(Locale.forLanguageTag("pl"));
            collator.setStrength(Collator.SECONDARY);
            return collator.compare(langShort, targetLang);
        } else {
            return containsSelf ? -1 : 1;
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(super.toString());
        sb.append("\n");

        for (Field field : fields) {
            sb.append(field);
        }

        return sb.toString();
    }
}
