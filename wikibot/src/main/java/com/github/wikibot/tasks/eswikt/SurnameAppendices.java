package com.github.wikibot.tasks.eswikt;

import java.io.IOException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.wikipedia.Wiki;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;

public final class SurnameAppendices {
	private static final String PREFIX = "Apéndice:Personas/Apellidos/";
	private static final String OTHER_SURNAMES_PAGE = "Apéndice:Personas/Apellidos/otros";
	private static final String SURNAME_TEMPLATE = "Plantilla:apellido";
	
	private static final char SPECIAL_LETTER = '#';
	
	private static final int COLUMNS = 5;
	
	private static final Collator collator;
	private static final Map<Character, Character> stressedVowels;
	
	private static Wikibot wb;
	
	static {
		collator = Collator.getInstance(new Locale("es"));
		
		final Map<Character, Character> _stressedVowels = new HashMap<>();
		_stressedVowels.put('Á', 'A');
		_stressedVowels.put('É', 'E');
		_stressedVowels.put('Í', 'I');
		_stressedVowels.put('Ó', 'O');
		_stressedVowels.put('Ú', 'U');
		
		stressedVowels = Collections.unmodifiableMap(_stressedVowels);
	}

	public static void main(String[] args) throws Exception {
		wb = Login.createSession(Domains.ESWIKT.getDomain());
		
		String[] subPages = getSubPages();
		String[] surnames = getSurnames();
		
		Set<Character> letters = filterTargetLetters(subPages);
		Map<Character, List<String>> groupedSurnames = groupSurnames(surnames, letters);
		
		wb.setThrottle(5000);
		wb.setMarkBot(true);
		wb.setMarkMinor(false);
		
		for (Map.Entry<Character, List<String>> entry : groupedSurnames.entrySet()) {
			Character firstLetter = entry.getKey();
			List<String> surnameList = entry.getValue();
			
			final String targetPage;
			final String header;
			
			if (firstLetter == SPECIAL_LETTER) {
				targetPage = OTHER_SURNAMES_PAGE;
				header = "otros";
			} else {
				targetPage = PREFIX + firstLetter;
				header = firstLetter.toString();
			}
			
			List<String> links = getLinksOnPage(targetPage);
			
			if (!links.containsAll(surnameList)) {
				List<String> mergedList = mergeLists(links, surnameList);
				String pageText = prepareOutput(header, mergedList);
				
				wb.edit(targetPage, pageText, "actualización");
			}
		}
	}

	private static String[] getSubPages() throws IOException {
		return wb.listPages(PREFIX, null, Wiki.MEDIAWIKI_NAMESPACE);
	}
	
	private static String[] getSurnames() throws IOException {
		return wb.whatTranscludesHere(SURNAME_TEMPLATE, Wiki.MAIN_NAMESPACE);
	}
	
	private static Set<Character> filterTargetLetters(String[] subPages) {
		return Stream.of(subPages)
			.map(subPage -> subPage.substring(subPage.lastIndexOf("/") + 1))
			.filter(suffix -> suffix.length() == 1)
			.filter(StringUtils::isAllUpperCase)
			.map(s -> s.charAt(0))
			.collect(Collectors.toSet());
	}
	
	private static Map<Character, List<String>> groupSurnames(String[] surnames, Set<Character> letters) {
		return Stream.of(surnames)
			.sorted(collator)
			.collect(Collectors.groupingBy(getClassifier(letters)));
	}
	
	private static Function<? super String, ? extends Character> getClassifier(Set<Character> letters) {
		return surname -> {
			Character firstLetter = surname.charAt(0);
			firstLetter = Optional.ofNullable(stressedVowels.get(firstLetter)).orElse(firstLetter);
			return letters.contains(firstLetter) ? firstLetter : SPECIAL_LETTER;
		};
	}
	
	private static List<String> getLinksOnPage(String page) throws IOException {
		return Stream.of(wb.getLinksOnPage(page))
			.filter(link -> wb.namespace(link) == Wiki.MAIN_NAMESPACE)
			.collect(Collectors.toList());
	}
	
	private static List<String> mergeLists(List<String> listA, List<String> listB) {
		return Stream.of(listA, listB)
			.flatMap(Collection::stream)
			.distinct()
			.sorted(collator)
			.collect(Collectors.toList());
	}
	
	private static String prepareOutput(String header, List<String> surnames) {
		StringBuilder sb = new StringBuilder(surnames.size() * 20);
		
		sb.append("{{Abecedario|").append(PREFIX.replaceFirst("/$", "")).append("}}");
		sb.append("\n\n==").append(header).append("==\n\n");
		
		sb.append("{| border=0  width=100%").append("\n");
		sb.append("|-").append("\n");
		
		List<List<String>> splitList = splitList(surnames, COLUMNS);
		final int width = (int) Math.floor(100 / COLUMNS);
		
		for (List<String> chunk : splitList) {
			sb.append("|valign=top width=").append(width).append("%|").append("\n");
			sb.append("{|").append("\n");
			
			chunk.stream()
				.map(link -> String.format("*[[%s]]", link))
				.forEach(item -> sb.append(item).append("\n"));
			
			sb.append("|}").append("\n");
		}
		
		sb.append("|}");
		
		return sb.toString();
	}
	
	private static List<List<String>> splitList(List<String> original, int chunks) {
		int chunkSize = (int) Math.ceil((double) original.size() / (double) chunks);
		List<List<String>> list = new ArrayList<>(chunks);
		int cursor = 0;
		
		for (int i = 0; i < chunks; i++) {
			List<String> subList = original.subList(cursor, Math.min(original.size(), cursor + chunkSize));
			list.add(subList);
			cursor += chunkSize;
		}
		
		return list;
	}
}
