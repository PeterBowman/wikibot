package com.github.wikibot.scripts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.AccountLockedException;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.PageContainer;
import com.thoughtworks.xstream.XStream;

public final class AssistedEdit {
    private static final Path LOCATION = Paths.get("./data/scripts/AssistedEdit/");
    private static final Path TITLES = LOCATION.resolve("titles.txt");
    private static final Path WORKLIST = LOCATION.resolve("worklist.txt");
    private static final Path DONELIST = LOCATION.resolve("done.txt");
    private static final Path TIMESTAMPS = LOCATION.resolve("timestamps.xml");
    private static final Path HASHCODES = LOCATION.resolve("hash.xml");

    private static final String WORKLIST_FILTERED_FORMAT = "worklist-%s.txt";

    private static XStream xstream = new XStream();

    private static Wikibot wb;

    public static void main(String[] args) throws Exception {
        var options = new Options();
        options.addRequiredOption("o", "operation", true, "mode of operation: query|extract|apply|edit");
        options.addRequiredOption("d", "domain", true, "wiki domain name");
        options.addOption("s", "summary", true, "edit summary");
        options.addOption("m", "minor", false, "mark edits as minor");
        options.addOption("t", "throttle", true, "set edit throttle [ms]");
        options.addOption("x", "section", true, "section name");
        options.addOption("l", "language", true, "language section short name (only pl.wiktionary.org)");
        options.addOption("f", "field", true, "field type (only pl.wiktionary.org)");

        if (args.length == 0) {
            System.out.print("Options: ");
            args = Misc.readArgs();
        }

        var parser = new DefaultParser();
        var line = parser.parse(options, args);
        var domain = line.getOptionValue("domain");

        var handler = switch (domain) {
            case "pl.wiktionary.org" -> new PlwiktFragmentHandler(line.getOptionValue("language"), line.getOptionValue("field"));
            default -> new GenericFragmentHandler(line.getOptionValue("section"));
        };

        wb = Wikibot.newSession(domain);
        Login.login(wb);
        wb.setThrottle(Integer.parseInt(line.getOptionValue("throttle", "5000")));

        switch (line.getOptionValue("operation")) {
            case "query" -> getContents();
            case "extract" -> extractFragments(handler);
            case "apply" -> applyFragments(handler);
            case "edit" -> editEntries(line.getOptionValue("summary"), line.hasOption("minor"));
            default -> {
                new HelpFormatter().printHelp(AssistedEdit.class.getName(), options);
                throw new IllegalArgumentException();
            }
        }
    }

    private static void getContents() throws IOException {
        var titles = Files.readAllLines(TITLES).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .distinct()
            .toList();

        System.out.printf("Size: %d%n", titles.size());

        if (titles.isEmpty()) {
            return;
        }

        var pages = wb.getContentOfPages(titles);

        var map = pages.stream().collect(Collectors.toMap(PageContainer::title, PageContainer::text, (a, b) -> a, LinkedHashMap::new));
        Files.write(WORKLIST, List.of(Misc.makeList(map)));

        var timestamps = pages.stream().collect(Collectors.toMap(PageContainer::title, PageContainer::timestamp));
        Files.writeString(TIMESTAMPS, xstream.toXML(timestamps));

        var hashcodes = pages.stream().collect(Collectors.toMap(PageContainer::title, pc -> pc.text().hashCode()));
        Files.writeString(HASHCODES, xstream.toXML(hashcodes));
    }

    private static void extractFragments(FragmentHandler handler) throws IOException {
        if (!handler.isEnabled()) {
            throw new IllegalArgumentException("Fragment parser not enabled.");
        }

        var map = Misc.readList(Files.readString(WORKLIST));
        System.out.printf("Size: %d%n", map.size());

        var fragmentMap = handler.extractFragments(map);
        System.out.printf("Fragments: %d%n", fragmentMap.size());

        var filename = String.format(WORKLIST_FILTERED_FORMAT, handler.getFileFragment());
        Files.writeString(LOCATION.resolve(filename), Misc.makeList(fragmentMap));
    }

    private static void applyFragments(FragmentHandler handler) throws IOException {
        if (!handler.isEnabled()) {
            throw new IllegalArgumentException("Fragment parser not enabled.");
        }

        var map = Misc.readList(Files.readString(WORKLIST));
        System.out.printf("Size: %d%n", map.size());

        var filename = String.format(WORKLIST_FILTERED_FORMAT, handler.getFileFragment());
        var fragmentMap = Misc.readList(Files.readString(LOCATION.resolve(filename)));
        System.out.printf("Fragments: %d%n", fragmentMap.size());

        handler.applyFragments(map, fragmentMap);
        Files.writeString(WORKLIST, Misc.makeList(map));
    }

    private static void editEntries(String summary, boolean minor) throws IOException {
        var map = Misc.readList(Files.readString(WORKLIST));
        final var initialSize = map.size();
        System.out.printf("Size: %d%n", initialSize);

        @SuppressWarnings("unchecked")
        var timestamps = (Map<String, OffsetDateTime>) xstream.fromXML(Files.readString(TIMESTAMPS));

        @SuppressWarnings("unchecked")
        var hashcodes = (Map<String, Integer>) xstream.fromXML(Files.readString(HASHCODES));
        map.entrySet().removeIf(e -> e.getValue().hashCode() == hashcodes.get(e.getKey()));

        if (map.size() != initialSize) {
            System.out.printf("Revised size: %d%n", map.size());
        }

        if (map.isEmpty()) {
            return;
        }

        var errors = new ArrayList<>();

        for (var entry : map.entrySet()) {
            var title = entry.getKey();
            var text = entry.getValue();

            try {
                wb.edit(title, text, summary, minor, true, -2, timestamps.get(title));
            } catch (Throwable t) {
                t.printStackTrace();
                errors.add(title);

                if (t instanceof AssertionError || t instanceof AccountLockedException) {
                    break;
                }
            }
        }

        System.out.printf("Edited: %d%n", map.size() - errors.size());

        if (!errors.isEmpty()) {
            System.out.printf("%d errors: %s%n", errors.size(), errors.toString());
        }

        Files.move(WORKLIST, DONELIST, StandardCopyOption.REPLACE_EXISTING);
    }

    private static interface FragmentHandler {
        Map<String, String> extractFragments(Map<String, String> map);
        void applyFragments(Map<String, String> map, Map<String, String> fragmentMap);
        String getFileFragment();
        boolean isEnabled();
    }

    private static class GenericFragmentHandler implements FragmentHandler {
        private String sectionName;

        public GenericFragmentHandler(String sectionName) {
            this.sectionName = sectionName;
        }

        @Override
        public Map<String, String> extractFragments(Map<String, String> map) {
            if (!isEnabled()) {
                return Collections.emptyMap();
            }

            Objects.requireNonNull(sectionName);

            return map.entrySet().stream()
                .map(e -> com.github.wikibot.parsing.Page.store(e.getKey(), e.getValue()))
                .map(p -> p.findSectionsWithHeader(sectionName))
                .filter(sections -> !sections.isEmpty())
                .map(sections -> sections.get(0))
                .collect(Collectors.toMap(
                    s -> s.getContainingPage().get().getTitle(),
                    s -> s.toString(),
                    (a, b) -> a,
                    LinkedHashMap::new
                ));
        }

        @Override
        public void applyFragments(Map<String, String> map, Map<String, String> fragmentMap) {
            if (isEnabled()) {
                fragmentMap.entrySet().forEach(e -> map.computeIfPresent(e.getKey(), (title, oldText) -> {
                    var page = com.github.wikibot.parsing.Page.store(title, oldText);
                    var sections = page.findSectionsWithHeader(sectionName);

                    if (!sections.isEmpty()) {
                        var newSection = com.github.wikibot.parsing.Section.parse(e.getValue());
                        sections.get(0).replaceWith(newSection);
                    }

                    return page.toString();
                }));
            }
        }

        @Override
        public String getFileFragment() {
            return Objects.requireNonNull(sectionName);
        }

        @Override
        public boolean isEnabled() {
            return sectionName != null;
        }
    }

    private static class PlwiktFragmentHandler implements FragmentHandler {
        private String sectionName;
        private FieldTypes fieldType;

        public PlwiktFragmentHandler(String sectionName, String fieldName) {
            this.sectionName = sectionName;

            if (fieldName != null) {
                fieldType =  Stream.of(FieldTypes.values())
                    .filter(type -> type.localised().equals(fieldName))
                    .findAny()
                    .orElseThrow(() -> new IllegalArgumentException("Unsupported field name: " + fieldName));
            }
        }

        @Override
        public Map<String, String> extractFragments(Map<String, String> map) {
            if (!isEnabled()) {
                return Collections.emptyMap();
            }

            Objects.requireNonNull(sectionName);

            var stream = map.entrySet().stream()
                .map(e -> com.github.wikibot.parsing.plwikt.Page.store(e.getKey(), e.getValue()))
                .flatMap(p -> p.getAllSections().stream())
                .filter(s -> s.getLangShort().equals(sectionName));

            if (fieldType != null) {
                return stream
                    .flatMap(s -> s.getField(fieldType).stream())
                    .filter(f -> !f.isEmpty())
                    .collect(Collectors.toMap(
                        f -> f.getContainingSection().get().getContainingPage().get().getTitle(),
                        Field::getContent,
                        (a, b) -> a,
                        LinkedHashMap::new
                    ));
            } else {
                return stream.collect(Collectors.toMap(
                    s -> s.getContainingPage().get().getTitle(),
                    s -> s.toString(),
                    (a, b) -> a,
                    LinkedHashMap::new
                ));
            }
        }

        @Override
        public void applyFragments(Map<String, String> map, Map<String, String> fragmentMap) {
            if (isEnabled()) {
                Objects.requireNonNull(sectionName);

                fragmentMap.entrySet().forEach(e -> map.computeIfPresent(e.getKey(), (title, oldText) -> {
                    var page = com.github.wikibot.parsing.plwikt.Page.store(title, oldText);
                    var section = page.getSection(sectionName, true);

                    if (section.isPresent()) {
                        if (fieldType != null) {
                            var field = section.get().getField(fieldType).get();
                            field.editContent(e.getValue());
                        } else {
                            var newSection = com.github.wikibot.parsing.plwikt.Section.parse(e.getValue());
                            section.get().replaceWith(newSection);
                        }
                    }

                    return page.toString();
                }));
            }
        }

        @Override
        public String getFileFragment() {
            Objects.requireNonNull(sectionName);

            return Optional.ofNullable(fieldType)
                .map(FieldTypes::localised)
                .map(fieldName -> String.format("%s-%s", sectionName, fieldName))
                .orElse(sectionName);
        }

        @Override
        public boolean isEnabled() {
            return sectionName != null || fieldType != null;
        }
    }
}