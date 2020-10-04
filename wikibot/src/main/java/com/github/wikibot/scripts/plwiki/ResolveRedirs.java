package com.github.wikibot.scripts.plwiki;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.Utils;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;

public final class ResolveRedirs {
	// from Linker::formatLinksInComment in Linker.php
	private static final String PATT_TEMPLATE = "\\[{2} *?:?(%s) *?(?:\\|((?:]?[^\\]])*+))?\\]{2}([a-zęóąśłżźćńĘÓĄŚŁŻŹĆŃ]+)?";
	
	private static final List<String> SOFT_REDIR_TEMPLATES = List.of(
		"Osobny artykuł", "Osobna strona", "Główny artykuł", "Main", "Mainsec", "Zobacz też", "Seealso"
	);
	
	private static final int[] TARGET_NAMESPACES;
	private static Wikibot wb;
	
	static {
		TARGET_NAMESPACES = new int[] { Wiki.MAIN_NAMESPACE, Wiki.USER_NAMESPACE, Wiki.TEMPLATE_NAMESPACE, 100 /* Portal */ };
	}
	
	public static void main(String[] args) throws Exception {
		final String target;
		
		if (args.length == 0) {
			System.out.print("Target page: ");
			target = String.join(" ", Misc.readArgs());
		} else {
			target = args[1];
		}
		
		wb = Login.createSession("pl.wikipedia.org");

		var redirs = wb.whatLinksHere(List.of(target), true, false, Wiki.MAIN_NAMESPACE).get(0);
		
		System.out.printf("%d redirs: %s%n", redirs.size(), redirs);
		
		if (redirs.isEmpty()) {
			return;
		}
		
		var backlinks = wb.whatLinksHere(redirs, false, false, TARGET_NAMESPACES).stream()
			.flatMap(Collection::stream)
			.sorted()
			.distinct()
			// retain user sandboxes
			.filter(title -> wb.namespace(title) != Wiki.USER_NAMESPACE || !wb.getRootPage(title).equals(title))
			.collect(Collectors.toList());
		
		System.out.printf("%d unique backlinks found.%n", backlinks.size());
		
		if (backlinks.isEmpty()) {
			return;
		}
		
		var patterns = redirs.stream()
			.map(redir -> String.format(PATT_TEMPLATE, Pattern.quote(redir)))
			.map(Pattern::compile)
			.collect(Collectors.toList());
		
		BiConsumer<Matcher, StringBuffer> replaceFunc = (m, sb) -> {
			var link = m.group(1);
			var text = Optional.ofNullable(m.group(2)).orElse(link);
			var trail = Optional.ofNullable(m.group(3)).orElse("");
			
			if (redirs.contains(text + trail)) {
				m.appendReplacement(sb, String.format("[[%s]]", target));
			} else {
				m.appendReplacement(sb, String.format("[[%s|%s]]", target, text + trail));
			}
		};
		
		final var summary = String.format("podmiana przekierowań do „[[%s]]”", target);
		
		var edited = new ArrayList<String>();
		var errors = new ArrayList<String>();
		
		wb.setMarkMinor(true);
		
		for (var page : wb.getContentOfPages(backlinks)) {
			var newText = page.getText();
			
			for (var pattern : patterns) {
				newText = Utils.replaceWithStandardIgnoredRanges(newText, pattern, replaceFunc);
				newText = replaceAdditionalOccurrences(newText, target, redirs);
			}
			
			if (!newText.equals(page.getText())) {
				try {
					wb.edit(page.getTitle(), newText, summary, page.getTimestamp());
					edited.add(page.getTitle());
				} catch (Throwable t) {
					t.printStackTrace();
					errors.add(page.getTitle());
				}
			}
		}
		
		System.out.printf("%d edited pages: %s%n", edited.size(), edited);
		System.out.printf("%d errors: %s%n", errors.size(), errors);
	}
	
	private static String replaceAdditionalOccurrences(String text, String target, List<String> redirs) {
		for (var templateName : SOFT_REDIR_TEMPLATES) {
			for (var template : ParseUtils.getTemplatesIgnoreCase(templateName, text)) {
				var params = ParseUtils.getTemplateParametersWithValue(template);
				
				params.entrySet().stream()
					.filter(e -> StringUtils.equalsAny(template, "Zobacz też", "Seealso")
						? e.getKey().equals("ParamWithoutName1")
						: e.getKey().startsWith("ParamWithoutName"))
					.filter(e -> redirs.contains(e.getValue()))
					.forEach(e -> e.setValue(target));
				
				text = Utils.replaceWithStandardIgnoredRanges(text, Pattern.quote(template), ParseUtils.templateFromMap(params));
			}
		}
		
		return text;
	}
}
