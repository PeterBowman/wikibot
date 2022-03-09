package com.github.wikibot.parsing;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.Range;
import org.wikiutils.ParseUtils;

public final class Utils {
    private static final int P_OPTIONS = Pattern.DOTALL | Pattern.CASE_INSENSITIVE;
    private static final Pattern P_NOWIKI = Pattern.compile("<(?<tag>nowiki)\\b[^>]*?(?<!/ ?)>.*?</\\k<tag>\\s*>", P_OPTIONS);
    private static final Pattern P_PRE = Pattern.compile("<(?<tag>pre)\\b[^>]*?(?<!/ ?)>.*?</\\k<tag>\\s*>", P_OPTIONS);
    private static final Pattern P_CODE = Pattern.compile("<(?<tag>code)\\b[^>]*?(?<!/ ?)>.*?</\\k<tag>\\s*>", P_OPTIONS);

    private Utils() {}

    public static String sanitizeWhitespaces(String text) {
        text = text.replace("\t", " ");
        text = text.replaceAll("[ ]{2,}", " ");
        text = text.replace(" \n", "\n");
        return text;
    }

    public static List<Range<Integer>> findRanges(String text, String start, String end) {
        return findRanges(text, start, end, false);
    }

    public static List<Range<Integer>> findRanges(String text, String start, String end, boolean lazyClosingTag) {
        int startPos = text.indexOf(start);

        if (startPos == -1) {
            return new ArrayList<Range<Integer>>();
        }

        var list = new ArrayList<Range<Integer>>();

        while (startPos != -1) {
            int endPos = text.indexOf(end, startPos);

            if (endPos != -1) {
                endPos += end.length();
                list.add(Range.between(startPos, endPos - 1)); // inclusive/inclusive
                startPos = text.indexOf(start, endPos);
            } else if (lazyClosingTag) {
                endPos = text.length();
                list.add(Range.between(startPos, endPos - 1)); // inclusive/inclusive
                break;
            } else {
                return list;
            }
        }

        return list;
    }

    public static List<Range<Integer>> findRanges(String text, Pattern patt) {
        var list = new ArrayList<Range<Integer>>();
        var m = patt.matcher(text);

        while (m.find()) {
            list.add(Range.between(m.start(), m.end() - 1)); // inclusive/inclusive
        }

        return list;
    }

    public static List<Range<Integer>> getStandardIgnoredRanges(String text) {
        // TODO: use DOM parsing?
        var comments = findRanges(text, "<!--", "-->", true);
        // FIXME: process stray open tags (see findRanges(String, String, String, boolean))
        var nowikis = findRanges(text, P_NOWIKI);
        var pres = findRanges(text, P_PRE);
        var codes = findRanges(text, P_CODE);

        return getCombinedRanges(List.of(comments, nowikis, pres, codes));
    }

    public static List<Range<Integer>> getCombinedRanges(List<List<Range<Integer>>> ranges) {
        if (ranges.isEmpty()) {
            return new ArrayList<>();
        }

        var filtered = ranges.stream()
            .filter(Objects::nonNull)
            .filter(l -> !l.isEmpty())
            .toList();

        if (filtered.isEmpty()) {
            return new ArrayList<>();
        } else if (filtered.size() == 1) {
            var temp = filtered.get(0);
            return new ArrayList<>(temp);
        } else {
            var list = sortIgnoredRanges(filtered);
            combineIgnoredRanges(list);
            return list;
        }
    }

    private static List<Range<Integer>> sortIgnoredRanges(List<List<Range<Integer>>> ranges) {
        var list = ranges.stream()
            .filter(Objects::nonNull)
            .flatMap(List::stream)
            .sorted(Comparator.comparingInt(Range::getMinimum))
            .collect(Collectors.toCollection(ArrayList::new));

        return list;
    }

    private static void combineIgnoredRanges(List<Range<Integer>> ranges) {
        var iterator = ranges.listIterator(ranges.size());

        while (iterator.hasPrevious()) {
            var range = iterator.previous();
            int previousIndex = iterator.previousIndex();

            if (previousIndex != -1) {
                var range2 = ranges.get(previousIndex);

                if (range2.isOverlappedBy(range)) {
                    iterator.remove();
                }
            }
        }
    }

    public static boolean containedInRanges(List<Range<Integer>> ignoredRanges, int index) {
        if (ignoredRanges == null || ignoredRanges.isEmpty()) {
            return false;
        }

        for (var range : ignoredRanges) {
            if (range.contains(index)) {
                return true;
            }
        }

        return false;
    }

    public static String replaceWithStandardIgnoredRanges(String text, String regex, String replacement) {
        return replaceWithIgnoredRanges(text, Pattern.compile(regex), replacement, getStandardIgnoredRanges(text));
    }

    public static String replaceWithStandardIgnoredRanges(String text, Pattern patt, String replacement) {
        return replaceWithIgnoredRanges(text, patt, replacement, getStandardIgnoredRanges(text));
    }

    public static String replaceWithStandardIgnoredRanges(String text, Pattern patt, BiConsumer<Matcher, StringBuilder> biCons) {
        return replaceWithIgnoredRanges(text, patt, getStandardIgnoredRanges(text), biCons);
    }

    public static String replaceWithStandardIgnoredRanges(String text, Pattern patt, Function<MatchResult, String> replacer) {
        BiConsumer<Matcher, StringBuilder> biCons = (m, sb) -> m.appendReplacement(sb, replacer.apply(m));
        return replaceWithIgnoredRanges(text, patt, getStandardIgnoredRanges(text), biCons);
    }

    public static String replaceWithStandardIgnoredRanges(String text, Pattern patt,
            ToIntFunction<Matcher> func, BiConsumer<Matcher, StringBuilder> biCons) {
        return replaceWithIgnoredRanges(text, patt, getStandardIgnoredRanges(text), func, biCons);
    }

    public static String replaceWithStandardIgnoredRanges(String text, Pattern patt,
            ToIntFunction<Matcher> func, Function<MatchResult, String> replacer) {
        BiConsumer<Matcher, StringBuilder> biCons = (m, sb) -> m.appendReplacement(sb, replacer.apply(m));
        return replaceWithIgnoredRanges(text, patt, getStandardIgnoredRanges(text), func, biCons);
    }

    public static String replaceWithIgnoredRanges(String text, Pattern patt, String replacement, List<Range<Integer>> ignoredRanges) {
        BiConsumer<Matcher, StringBuilder> biCons = (m, sb) -> m.appendReplacement(sb, replacement);
        return replaceWithIgnoredRanges(text, patt, ignoredRanges, biCons);
    }

    public static String replaceWithIgnoredRanges(String text, Pattern patt,
            List<Range<Integer>> ignoredRanges, BiConsumer<Matcher, StringBuilder> biCons) {
        return replaceWithIgnoredRanges(text, patt, ignoredRanges, Matcher::start, biCons);
    }

    public static String replaceWithIgnoredRanges(String text, Pattern patt,
            List<Range<Integer>> ignoredRanges, Function<MatchResult, String> replacer) {
        BiConsumer<Matcher, StringBuilder> biCons = (m, sb) -> m.appendReplacement(sb, replacer.apply(m));
        return replaceWithIgnoredRanges(text, patt, ignoredRanges, Matcher::start, biCons);
    }

    public static String replaceWithIgnoredRanges(String text, Pattern patt,
            List<Range<Integer>> ignoredRanges, ToIntFunction<Matcher> func,
            BiConsumer<Matcher, StringBuilder> biCons) {
        var m = patt.matcher(text);
        var sb = new StringBuilder(text.length());

        while (m.find()) {
            if (containedInRanges(ignoredRanges, func.applyAsInt(m))) {
                continue;
            }

            biCons.accept(m, sb);
        }

        return m.appendTail(sb).toString();
    }

    public static String replaceTemplates(String text, String templateName, UnaryOperator<String> func) {
        var templates = ParseUtils.getTemplates(templateName, text);

        if (templates.isEmpty()) {
            return text;
        }

        var sb = new StringBuilder(text.length());

        int index = 0;
        int lastIndex = 0;

        for (var template : templates) {
            index = indexOfIgnoringRanges(text, template, lastIndex);
            sb.append(text.substring(lastIndex, index));
            sb.append(func.apply(template));
            lastIndex = index + template.length();
        }

        sb.append(text.substring(lastIndex));
        return sb.toString();
    }

    public static int indexOfIgnoringRanges(String str, String target, int fromIndex) {
        var noWiki = ParseUtils.getIgnorePositions(str, "<nowiki>", "</nowiki>");
        var comment = ParseUtils.getIgnorePositions(str, "<!--", "-->");

        int index = 0;

        while (true) {
            index = str.indexOf(target, fromIndex);

            if (index == -1 || (
                !ParseUtils.isIgnorePosition(noWiki, index) &&
                !ParseUtils.isIgnorePosition(comment, index)
            )) {
                break;
            } else {
                fromIndex = index + target.length();
            }
        }

        return index;
    }

    public static String loadResource(String filename, Class<?> caller) {
        try (var lines = Files.lines(Paths.get(caller.getResource(filename).toURI()))) {
            return lines.collect(Collectors.joining("\n"));
        } catch (IOException | NullPointerException | URISyntaxException e) {
            throw new MissingResourceException("Error loading resource: " + filename, caller.getName(), filename);
        }
    }

    public static List<String> readLinesFromResource(String filename, Class<?> caller) {
        try (var lines = Files.lines(Paths.get(caller.getResource(filename).toURI()))) {
            return lines.map(String::trim).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        } catch (IOException | NullPointerException | URISyntaxException e) {
            throw new MissingResourceException("Error loading resource: " + filename, caller.getName(), filename);
        }
    }
}
