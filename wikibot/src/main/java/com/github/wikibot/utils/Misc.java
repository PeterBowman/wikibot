package com.github.wikibot.utils;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Scanner;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.exec.CommandLine;

public final class Misc {
	private Misc() {}
		
	public static String makeList(Map<String, String> map) {
		return map.keySet().stream()
			.map(title -> String.format("%s%n%n%s", title, map.get(title)))
			.collect(Collectors.joining(String.format("%n%n%s%n%n", "*".repeat(40))));
	}
	
	public static Map<String, String> readList(String data) {
		var map = new LinkedHashMap<String, String>();
		var drafts = Pattern.compile("\n{2}\\*{40}\n{2}").split(data.replace("\r\n", "\n"));
		
		for (var draft : drafts) {
			draft = draft.trim();
			var title = draft.substring(0, draft.indexOf("\n\n"));
			var content = draft.substring(draft.indexOf("\n\n") + 2);
			map.put(title, content);
		}
		
		return map;
	}

	public static String makeMultiList(Map<String, Collection<String>> map) {
		return makeMultiList(map, String.format("%n%n%s%n%n", "-".repeat(30)));
	}
	
	public static String makeMultiList(Map<String, Collection<String>> map, String separator) {
		return map.keySet().stream()
			.map(title -> String.format(
				"%s%n%n%s",
				title,
				map.get(title).stream().collect(Collectors.joining(separator))
			))
			.collect(Collectors.joining(String.format("%n%n%s%n%n", "*".repeat(40))));
	}
	
	public static Map<String, String[]> readMultiList(String data) {
		return readMultiList(data, "\n\n-{30}\n\n");
	}
	
	public static Map<String, String[]> readMultiList(String data, String separator) {
		var map = new LinkedHashMap<String, String[]>();
		var drafts = Pattern.compile("\\*{40}").split(String.join("\n", data.replace("\r\n", "\n")));
		
		for (var draft : drafts) {
			draft = draft.trim();
			var title = draft.substring(0, draft.indexOf("\n"));
			var content = draft.substring(draft.indexOf("\n")).trim();
			var contents = content.split(Pattern.compile(separator, Pattern.LITERAL).pattern());
			map.put(title, contents);
		}
		
		return map;
	}
	
	public static String readLine() {
		var console = System.console();
		
		if (console != null) {
			return console.readLine();
		} else {
			@SuppressWarnings("resource")
			var scanner = new Scanner(System.in);
			
			try {
				return scanner.nextLine();
			} catch (NoSuchElementException e) {
				return "";
			}
		}
	}
	
	public static String[] readArgs() {
		// first parsed element becomes the executable
		return CommandLine.parse("dummy " + readLine()).getArguments();
	}
}
