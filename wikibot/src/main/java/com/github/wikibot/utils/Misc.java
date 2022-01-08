package com.github.wikibot.utils;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.exec.CommandLine;

import com.github.wikibot.main.Selectorizable;

public final class Misc {
	private Misc() {}
		
	public static void serialize(Object target, Path path) throws IOException {
		try (var out = new ObjectOutputStream(Files.newOutputStream(path))) {
			out.writeObject(target);
			System.out.printf("Object successfully serialized: %s%n", path);
		}
	}
	
	public static <T> T deserialize(Path source) throws IOException, ClassNotFoundException {
		try (var in = new ObjectInputStream(Files.newInputStream(source))) {
			@SuppressWarnings("unchecked")
			var target = (T) in.readObject();
			System.out.printf("Object successfully deserialized: %s%n", source);
			return target;
		}
	}
	
	public static void runTimerWithSelector(Selectorizable c) {
		System.out.print("Introduce el modo de operación: ");
		
		try {
			char op = (char) System.in.read();
			long start = System.currentTimeMillis();
			
			c.selector(op);
			
			int seconds = (int) (System.currentTimeMillis() - start) / 1000;
			int minutes = (int) Math.floor(seconds/60);
			
			System.out.println(String.format(
				"Tiempo total transcurrido: %dm %ds.%n",
				minutes,
				(minutes != 0) ? (seconds % (minutes * 60)) : seconds
			));
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}
	}
	
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
			return scanner.nextLine();
		}
	}
	
	public static String[] readArgs() {
		// first parsed element becomes the executable
		return CommandLine.parse("dummy " + readLine()).getArguments();
	}
}
