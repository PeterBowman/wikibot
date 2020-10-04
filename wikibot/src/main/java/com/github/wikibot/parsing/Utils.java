package com.github.wikibot.parsing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.ToIntFunction;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.IOUtils;
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
		
		return getCombinedRanges(comments, nowikis, pres, codes);
	}
	
	@SafeVarargs
	public static List<Range<Integer>> getCombinedRanges(List<Range<Integer>>... ranges) {
		if (ranges.length == 0) {
			return new ArrayList<>();
		}
		
		List<List<Range<Integer>>> filtered = Stream.of(ranges)
			.filter(Objects::nonNull)
			.filter(l -> !l.isEmpty())
			.collect(Collectors.toList());
		
		if (filtered.isEmpty()) {
			return new ArrayList<>();
		} else if (filtered.size() == 1) {
			List<Range<Integer>> temp = filtered.get(0);
			return new ArrayList<>(temp);
		} else {
			List<Range<Integer>> list = sortIgnoredRanges(filtered);
			combineIgnoredRanges(list);
			return list;
		}
	}

	private static List<Range<Integer>> sortIgnoredRanges(List<List<Range<Integer>>> ranges) {
		List<Range<Integer>> list = ranges.stream()
			.filter(Objects::nonNull)
			.flatMap(List::stream)
			.sorted((r1, r2) -> Integer.compare(r1.getMinimum(), r2.getMinimum()))
			.collect(Collectors.toList());
					
		return list;
	}

	private static void combineIgnoredRanges(List<Range<Integer>> ranges) {
		ListIterator<Range<Integer>> iterator = ranges.listIterator(ranges.size());
		
		while (iterator.hasPrevious()) {
			Range<Integer> range = iterator.previous();
			int previousIndex = iterator.previousIndex();
			
			if (previousIndex != -1) {
				Range<Integer> range2 = ranges.get(previousIndex);
				
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
		
		for (Range<Integer> range : ignoredRanges) {
			if (range.contains(index)) {
				return true;
			}
		}
		
		return false;
	}
	
	public static String replaceWithStandardIgnoredRanges(String text, String regex, String replacement) {
		return replaceWithIgnoredranges(text, Pattern.compile(regex), replacement, getStandardIgnoredRanges(text));
	}
	
	public static String replaceWithStandardIgnoredRanges(String text, Pattern patt, String replacement) {
		return replaceWithIgnoredranges(text, patt, replacement, getStandardIgnoredRanges(text));
	}
	
	public static String replaceWithStandardIgnoredRanges(String text, Pattern patt,
			BiConsumer<Matcher, StringBuffer> biCons) {
		ToIntFunction<Matcher> func = Matcher::start;
		return replaceWithIgnoredranges(text, patt, getStandardIgnoredRanges(text), func, biCons);
	}
	
	public static String replaceWithStandardIgnoredRanges(String text, Pattern patt,
			ToIntFunction<Matcher> func, BiConsumer<Matcher, StringBuffer> biCons) {
		return replaceWithIgnoredranges(text, patt, getStandardIgnoredRanges(text), func, biCons);
	}
	
	public static String replaceWithIgnoredranges(String text, Pattern patt, String replacement,
			List<Range<Integer>> ignoredRanges) {
		ToIntFunction<Matcher> func = Matcher::start;
		BiConsumer<Matcher, StringBuffer> biCons = (m, sb) -> m.appendReplacement(sb, replacement);
		return replaceWithIgnoredranges(text, patt, ignoredRanges, func, biCons);
	}
	
	public static String replaceWithIgnoredranges(String text, Pattern patt,
			List<Range<Integer>> ignoredRanges, ToIntFunction<Matcher> func,
			BiConsumer<Matcher, StringBuffer> biCons) {
		Matcher m = patt.matcher(text);
		StringBuffer sb = new StringBuffer(text.length());
		
		while (m.find()) {
			if (containedInRanges(ignoredRanges, func.applyAsInt(m))) {
				continue;
			}
			
			biCons.accept(m, sb);
		}
		
		return m.appendTail(sb).toString();
	}

	public static String replaceTemplates(String text, String templateName, UnaryOperator<String> func) {
		List<String> templates = ParseUtils.getTemplates(templateName, text);
		
		if (templates.isEmpty()) {
			return text;
		}
		
		StringBuilder sb = new StringBuilder(text.length());
		
		int index = 0;
		int lastIndex = 0;
		
		for (String template : templates) {
			index = indexOfIgnoringRanges(text, template, lastIndex);
			sb.append(text.substring(lastIndex, index));
			sb.append(func.apply(template));
			lastIndex = index + template.length();
		}
		
		sb.append(text.substring(lastIndex));
		return sb.toString();
	}
	
	public static int indexOfIgnoringRanges(String str, String target, int fromIndex) {
		HashMap<Integer, Integer> noWiki = ParseUtils.getIgnorePositions(str, "<nowiki>", "</nowiki>");
		HashMap<Integer, Integer> comment = ParseUtils.getIgnorePositions(str, "<!--", "-->");
		
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
		try (InputStream is = caller.getResourceAsStream(filename)) {
			List<String> lines = IOUtils.readLines(is, StandardCharsets.UTF_8);
			// could have used IOUtils.toString(), but newlines are platform-dependent
			return String.join("\n", lines);
		} catch (IOException | NullPointerException e) {
			throw new MissingResourceException("Error loading resource: " + filename, caller.getName(), filename);
		}
	}
	
	public static Stream<String> readLinesFromResource(String filename, Class<?> caller) {
		try (InputStream is = caller.getResourceAsStream(filename)) {
			return IOUtils.readLines(is, StandardCharsets.UTF_8).stream()
				.map(String::trim)
				.filter(line -> !line.isEmpty() && !line.startsWith("#"));
		} catch (IOException | NullPointerException e) {
			throw new MissingResourceException("Error loading resource: " + filename, caller.getName(), filename);
		}
	}
}
