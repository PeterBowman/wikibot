package com.github.wikibot.utils;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.exec.CommandLine;

import com.github.wikibot.main.Selectorizable;

public final class Misc {
	private Misc() {}
	
	public static class MyRandom {
		private Set<Integer> set;
		private Random r;
		private int base;
		
		public MyRandom(int digits) {			
			set = new HashSet<>();
			r = new Random();
			base = (int) Math.pow(10, digits - 1);
		}
		
		public int generateInt() {
			int n = 0;
			
			while (!set.contains(n)) {
				set.add(n = nextInt());
			}
			
			return n;
		}
		
		private int nextInt() {
			return base + r.nextInt(9 * base - 1);
		}
	}
	
	public static void serialize(Object target, String source) throws IOException {
		serialize(target, Paths.get(source));
	}
	
	public static void serialize(Object target, Path path) throws IOException {
		try (var out = new ObjectOutputStream(Files.newOutputStream(path))) {
			out.writeObject(target);
			System.out.printf("Object successfully serialized: %s%n", path);
		}
	}
	
	public static <T> T deserialize(String source) throws IOException, ClassNotFoundException {
		return deserialize(Paths.get(source));
	}
	
	public static <T> T deserialize(Path source) throws IOException, ClassNotFoundException {
		try (var in = new ObjectInputStream(Files.newInputStream(source))) {
			@SuppressWarnings("unchecked")
			var target = (T) in.readObject();
			System.out.printf("Object successfully deserialized: %s%n", source);
			return target;
		}
	}
	
	public static void runTimer(Runnable runner) {
		long start = System.currentTimeMillis();
		
		runner.run();
		
		int seconds = (int) (System.currentTimeMillis() - start) / 1000;
		int minutes = (int) Math.floor(seconds/60);
		
		System.out.println(String.format(
			"Tiempo total transcurrido: %dm %ds.%n",
			minutes,
			(minutes != 0) ? (seconds % (minutes * 60)) : seconds
		));
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
	
	public static void runScheduledSelector(Selectorizable c, String arg) {
		char value = arg.toCharArray()[0];
		
		try {
			c.selector(value);
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}
	}
	
	public static String makeList(Map<String, String> map) {
		var worklist = map.keySet().stream()
			.map(title -> String.format("%s%n%n%s", title, map.get(title)))
			.collect(Collectors.joining(String.format("%n%n%s%n%n", "*".repeat(40))));
			
		return worklist;
	}
	
	public static Map<String, String> readList(String[] lines) {
		return readList(String.join("\n", lines));
	}
	
	public static Map<String, String> readList(String data) {
		var map = new LinkedHashMap<String, String>();
		var drafts = Pattern.compile("\\*{40}").split(data);
		
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
		var worklist = map.keySet().stream()
			.map(title -> String.format(
					"%s%n%n%s",
					title,
					map.get(title).stream().collect(Collectors.joining(separator))
				)
			)
			.collect(Collectors.joining(String.format("%n%n%s%n%n", "*".repeat(40))));
		
		return worklist;
	}
	
	public static Map<String, String[]> readMultiList(String[] lines) {
		return readMultiList(String.join("\n", lines));
	}
	
	public static Map<String, String[]> readMultiList(String[] lines, String separator) {
		return readMultiList(String.join("\n", lines), separator);
	}
	
	public static Map<String, String[]> readMultiList(String data) {
		return readMultiList(data, "\n\n-{30}\n\n");
	}
	
	public static Map<String, String[]> readMultiList(String data, String separator) {
		var map = new LinkedHashMap<String, String[]>();
		var drafts = Pattern.compile("\\*{40}").split(String.join("\n", data));
		
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
