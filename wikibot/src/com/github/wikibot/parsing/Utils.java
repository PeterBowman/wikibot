package com.github.wikibot.parsing;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
}
