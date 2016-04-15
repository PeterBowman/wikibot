package com.github.wikibot.webapp;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class Utils {
	public static String formatTimestamp(String timestamp, String timeZone, String locale) {
		try {
			SimpleDateFormat standardFormat = new SimpleDateFormat("yyyyMMddHHmmss", new Locale(locale));
			Date date = standardFormat.parse(timestamp);
			standardFormat.applyPattern("HH:mm, dd MMM yyyy (z)");
			standardFormat.setTimeZone(TimeZone.getTimeZone(timeZone));
			return standardFormat.format(date);
		} catch (ParseException e) {
			return timestamp;
		}
	}
	
	public static String makePluralPL(int value, String singular, String nominative, String genitive) {
		if (value == 1) {
			return singular;
		}
		
		String strValue = Integer.toString(value);
		
		switch (strValue.charAt(strValue.length() - 1)) {
			case '2':
			case '3':
			case '4':
				if (strValue.length() > 1 && strValue.charAt(strValue.length() - 2) == '1') {
					return genitive;
				} else {
					return nominative;
				}
			default:
				return genitive;
		}
	}
	
	public static boolean bitCompare(int bitmask, int target) {
		return (bitmask & target) == target;
	}
}
