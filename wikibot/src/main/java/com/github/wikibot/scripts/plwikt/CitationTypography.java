package com.github.wikibot.scripts.plwikt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.wikiutils.IOUtils;

import com.github.wikibot.dumps.XMLDumpReader;
import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.PLWikt;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;
import com.github.wikibot.utils.Users;

public final class CitationTypography {
	private static final Pattern PATT = Pattern.compile("^(.*)\\. *(?:'{2})? *(<ref\\b.*?(?:/ *?>|>.*?</ref *?>))(.*)$", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
	private static final String REPLACEMENT = "$1''$2.$3";
	
	private static final List<FieldTypes> ALLOWED_FIELDS = Arrays.asList(
		FieldTypes.EXAMPLES, FieldTypes.ETYMOLOGY, FieldTypes.NOTES
	);
	
	private static PLWikt wb;
	
	public static void main(String[] args) throws Exception {
		wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
		
		int stats = wb.getSiteStatistics().get("pages");
		XMLDumpReader reader = new XMLDumpReader(Domains.PLWIKT);
		Map<String, Collection<String>> map;
		
		try (Stream<XMLRevision> stream = reader.getStAXReader(stats).stream()) {
			map = stream.parallel()
				.filter(XMLRevision::isMainNamespace)
				.filter(XMLRevision::nonRedirect)
				.collect(Collectors.toMap(
					XMLRevision::getTitle,
					CitationTypography::collectMatches,
					(a, b) -> a,
					TreeMap::new
				));
		}
		
		map.values().removeIf(Collection::isEmpty);
		System.out.printf("Size: %d%n", map.size());
		IOUtils.writeToFile(Misc.makeMultiList(map), "./data/test6.txt");
	}
	
	private static Collection<String> collectMatches(XMLRevision rev) {
		Page page = Page.wrap(rev);
		List<String> list = new ArrayList<>();
		
		page.getAllSections().stream()
			.map(s -> s.getAllFields())
			.flatMap(Collection::stream)
			.filter(field -> !ALLOWED_FIELDS.contains(field.getFieldType()))
			.forEach(field -> {
				String text = field.getContent();
				String fieldName = String.format("{{%s}}", field.getFieldType().localised);
				
				while (true) {
					Matcher m = PATT.matcher(text);
					
					if (m.find()) {
						String original = m.group();
						String replaced = PATT.matcher(original).replaceFirst(REPLACEMENT);
						list.add(fieldName + "\n\n" + original + "\n\n" + replaced);
						text = m.replaceAll(REPLACEMENT);
					} else {
						break;
					}
				};
			});
		
		if (!list.isEmpty()) {
			return list;
		} else {
			return Collections.emptyList();
		}
	}
}
