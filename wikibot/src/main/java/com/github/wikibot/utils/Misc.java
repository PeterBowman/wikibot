package com.github.wikibot.utils;

import java.io.Console;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.text.Collator;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.wikiutils.ParseUtils;

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
	
	public static void serialize(Object target, String source) throws FileNotFoundException, IOException {
		serialize(target, new File(source));
	}
	
	public static void serialize(Object target, File f) throws FileNotFoundException, IOException {
		try (ObjectOutputStream out = new ObjectOutputStream(new FileOutputStream(f))) {
			out.writeObject(target);
			System.out.printf("Object successfully serialized: %s%n", f.getName());
		}
	}
	
	public static <T> T deserialize(String source) throws FileNotFoundException, IOException, ClassNotFoundException {
		return deserialize(new File(source));
	}
	
	@SuppressWarnings("unchecked")
	public static <T> T deserialize(File f) throws FileNotFoundException, IOException, ClassNotFoundException {
		try (ObjectInputStream in = new ObjectInputStream(new FileInputStream(f))) {
			T target = (T) in.readObject();
			System.out.printf("Object successfully deserialized: %s%n", f.getName());
			return target;
		}
	}
	
	public static String makePluralPL(int value, String nominative, String genitive) {
		return makePluralPL(value, null, nominative, genitive);
	}
	
	public static String makePluralPL(int value, String singular, String nominative, String genitive) {
		if (singular != null && value == 1) {
			return Integer.toString(value) + " " + singular;
		}
		
		String strValue = Integer.toString(value);
		char[] digits = new char[strValue.length()];
		strValue.getChars(0, strValue.length(), digits, 0);

		if (digits.length > 4) {
			String temp = "";
			
			for (int i = 1; i <= digits.length; i++) {
				temp = digits[digits.length - i] + temp;
				
				if (i % 3 == 0 && i != digits.length) {
					temp = " " + temp;
				}
			}
			strValue = temp;
		}
		
		switch (strValue.charAt(strValue.length() - 1)) {
			case '2':
			case '3':
			case '4':
				if (strValue.length() > 1 && strValue.charAt(strValue.length() - 2) == '1') {
					return strValue + " " + genitive;
				} else {
					return strValue + " " + nominative;
				}
			default:
				return strValue + " " + genitive;
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
		String worklist = map.keySet().stream()
			.map(title -> String.format("%s%n%n%s", title, map.get(title)))
			.collect(Collectors.joining(String.format("%n%n%s%n%n", ParseUtils.getString('*', 40))));
			
		return worklist;
	}
	
	public static Map<String, String> readList(String[] lines) {
		return readList(String.join("\n", lines));
	}
	
	public static Map<String, String> readList(String data) {
		Map<String, String> map = new LinkedHashMap<>();
		String[] drafts = Pattern.compile("\\*{40}").split(String.join("\n", data));
		
		for (String draft : drafts) {
			draft = draft.trim();
			String title = draft.substring(0, draft.indexOf("\n"));
			String content = draft.substring(draft.indexOf("\n")).trim();
			map.put(title, content);
		}
		
		return map;
	}

	public static String makeMultiList(Map<String, Collection<String>> map) {
		return makeMultiList(map, String.format("%n%n%s%n%n", ParseUtils.getString('-', 30)));
	}
	
	public static String makeMultiList(Map<String, Collection<String>> map, String separator) {
		String worklist = map.keySet().stream()
				.map(title -> String.format(
						"%s%n%n%s",
						title,
						map.get(title).stream().collect(Collectors.joining(separator))
					)
				)
				.collect(Collectors.joining(String.format("%n%n%s%n%n", ParseUtils.getString('*', 40))));
			
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
		Map<String, String[]> map = new LinkedHashMap<>();
		String[] drafts = Pattern.compile("\\*{40}").split(String.join("\n", data));
		
		for (String draft : drafts) {
			draft = draft.trim();
			String title = draft.substring(0, draft.indexOf("\n"));
			String content = draft.substring(draft.indexOf("\n")).trim();
			String[] contents = content.split(Pattern.compile(separator, Pattern.LITERAL).toString());
			map.put(title, contents);
		}
		
		return map;
	}
	
	public static void sortList(List<String> coll, String lang) {
		Collections.sort(coll, getCollator(lang));
	}
	
	public static Collator getCollator(String lang) {
		Collator collator = Collator.getInstance(new Locale(lang));
		collator.setStrength(Collator.SECONDARY);
		return collator;
	}
	
	public static PageContainer retrievePage(PageContainer[] pages, String title) {
		return retrievePage(Arrays.asList(pages), title);
	}
	
	public static PageContainer retrievePage(Collection<PageContainer> pages, String title) {
		return pages.stream()
			.filter(page -> page.getTitle().equals(title))
			.findFirst()
			.orElse(null);
	}
	
	public static String readLine() {
		Console console = System.console();
		
		if (console != null) {
			return console.readLine();
		} else {
			@SuppressWarnings("resource")
			Scanner scanner = new Scanner(System.in);
			return scanner.nextLine();
		}
	}
	
	public static char[] readPassword() {
		Console console = System.console();
		
		if (console != null) {
			return console.readPassword();
		} else {
			@SuppressWarnings("resource")
			Scanner scanner = new Scanner(System.in);
			return scanner.nextLine().toCharArray();
		}
	}

	public static void main(String[] args) {
		System.out.println(Misc.makePluralPL(22738730, "krowy", "krów"));
		System.out.println(Misc.makePluralPL(21641, "krowy", "krów"));
		System.out.println(Misc.makePluralPL(254634132, "krowy", "krów"));
		System.out.println(Misc.makePluralPL(2653, "krowy", "krów"));
		System.out.println(Misc.makePluralPL(26544, "krowy", "krów"));
		System.out.println(Misc.makePluralPL(2646545, "krowy", "krów"));
		System.out.println(Misc.makePluralPL(2646512, "krowy", "krów"));
	}
}
