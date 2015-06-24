package com.github.wikibot.parsing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.Range;

public final class Utils {
	private Utils() {}
	
	@SuppressWarnings("unchecked")
	public static Range<Integer>[] findRanges(String text, String start, String end) {
		int startPos = text.indexOf(start);
		
		if (startPos == -1) {
			return null;
		}
		
		List<Range<Integer>> list = new ArrayList<Range<Integer>>();
		
		while (startPos != -1) {
			int endPos = text.indexOf(end, startPos);
			
			if (endPos != -1) {
				list.add(Range.between(startPos, endPos));
			} else {
				return list.toArray(new Range[list.size()]); 
			}
			
			startPos = text.indexOf(start, endPos);
		}
		
		return list.toArray(new Range[list.size()]);
	}
	
	@SuppressWarnings("unchecked")
	public static Range<Integer>[] findRanges(String text, Pattern patt) {
		List<Range<Integer>> list = new ArrayList<Range<Integer>>();
		Matcher m = patt.matcher(text);
		
		while (m.find()) {
			list.add(Range.between(m.start(), m.end()));
		}
		
		return list.toArray(new Range[list.size()]);
	}

	public static String sanitizeWhitespaces(String text) {
		text = text.replaceAll("[ ]{2,}", " ");
		text = text.replace(" \n", "\n");
		return text;
	}

	public static List<Range<Integer>> getIgnoredRanges(String text) {
		Range<Integer>[] comments = findRanges(text, "<!--", "-->");
		Range<Integer>[] nowikis = findRanges(text, "<nowiki>", "</nowiki>");
		Range<Integer>[] pres = findRanges(text, Pattern.compile("<pre(?: |>).+?</pre>", Pattern.DOTALL));
		Range<Integer>[] codes = findRanges(text, Pattern.compile("<code(?: |>).+?</code>", Pattern.DOTALL));
		
		List<Range<Integer>> ranges = Arrays.asList(comments, nowikis, pres, codes)
			.stream()
			.filter(Objects::nonNull)
			.flatMap(array -> Stream.of(array))
			.sorted((r1, r2) -> Integer.compare(r1.getMinimum(), r2.getMinimum()))
			.collect(Collectors.toList());
		
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
		
		return ranges;
	}
}
