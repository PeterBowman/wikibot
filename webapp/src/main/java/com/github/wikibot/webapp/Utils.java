package com.github.wikibot.webapp;

public final class Utils {
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
	
	public static String lastPathPart(String path) {
		return path.substring(path.lastIndexOf("/") + 1);
	}
}
