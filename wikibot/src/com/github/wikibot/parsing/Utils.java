package com.github.wikibot.parsing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.Range;
import org.wikiutils.ParseUtils;

public final class Utils {
	private Utils() {}
	
	public static String sanitizeWhitespaces(String text) {
		text = text.replace("\t", " ");
		text = text.replaceAll("[ ]{2,}", " ");
		text = text.replace(" \n", "\n");
		return text;
	}

	@SuppressWarnings("unchecked")
	public static Range<Integer>[] findRanges(String text, String start, String end) {
		int startPos = text.indexOf(start);
		
		if (startPos == -1) {
			return new Range[]{};
		}
		
		List<Range<Integer>> list = new ArrayList<Range<Integer>>();
		
		while (startPos != -1) {
			int endPos = text.indexOf(end, startPos);
			
			if (endPos != -1) {
				endPos += end.length();
				list.add(Range.between(startPos, endPos - 1)); // inclusive/inclusive
				startPos = text.indexOf(start, endPos);
			} else {
				return list.toArray(new Range[list.size()]); 
			}
		}
		
		return list.toArray(new Range[list.size()]);
	}
	
	@SuppressWarnings("unchecked")
	public static Range<Integer>[] findRanges(String text, Pattern patt) {
		List<Range<Integer>> list = new ArrayList<Range<Integer>>();
		Matcher m = patt.matcher(text);
		
		while (m.find()) {
			list.add(Range.between(m.start(), m.end() - 1)); // inclusive/inclusive
		}
		
		return list.toArray(new Range[list.size()]);
	}
	
	public static List<Range<Integer>> getStandardIgnoredRanges(String text) {
		final int patternOptions = Pattern.DOTALL | Pattern.CASE_INSENSITIVE;
		Range<Integer>[] comments = findRanges(text, "<!--", "-->");
		// TODO: use DOM parsing?
		Range<Integer>[] nowikis = findRanges(text, Pattern.compile("<nowiki(?: |>).+?</nowiki *?>", patternOptions));
		Range<Integer>[] pres = findRanges(text, Pattern.compile("<pre(?: |>).+?</pre *?>", patternOptions));
		Range<Integer>[] codes = findRanges(text, Pattern.compile("<code(?: |>).+?</code *?>", patternOptions));
		
		return getCombinedRanges(comments, nowikis, pres, codes);
	}
	
	@SafeVarargs
	public static List<Range<Integer>> getCombinedRanges(Range<Integer>[]... ranges) {
		if (ranges.length == 0) {
			return new ArrayList<Range<Integer>>();
		}
		
		List<Range<Integer>[]> filtered = Stream.of(ranges)
			.filter(Objects::nonNull)
			.filter(arr -> arr.length != 0)
			.collect(Collectors.toList());
		
		if (filtered.isEmpty()) {
			return new ArrayList<Range<Integer>>();
		} else if (filtered.size() == 1) {
			List<Range<Integer>> temp = Arrays.asList(filtered.get(0));
			return new ArrayList<Range<Integer>>(temp);
		} else {
			List<Range<Integer>> list = sortIgnoredRanges(filtered);
			combineIgnoredRanges(list);
			return list;
		}
	}

	private static List<Range<Integer>> sortIgnoredRanges(List<Range<Integer>[]> ranges) {
		List<Range<Integer>> list = ranges.stream()
			.filter(Objects::nonNull)
			.flatMap(Stream::of)
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
		List<Range<Integer>> ignoredRanges = getStandardIgnoredRanges(text);
		return replaceWithIgnoredranges(text, regex, replacement, ignoredRanges);
	}
	
	public static String replaceWithIgnoredranges(String text, String regex, String replacement, List<Range<Integer>> ignoredRanges) {
		Matcher m = Pattern.compile(regex).matcher(text);
		StringBuffer sb = new StringBuffer(text.length());
		
		while (m.find()) {
			if (containedInRanges(ignoredRanges, m.start())) {
				continue;
			}
			
			m.appendReplacement(sb, replacement);
		}
		
		m.appendTail(sb);
		return sb.toString();
	}
	
	public static String replaceTemplates(String text, String templateName, Function<String, String> func) {
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
}
