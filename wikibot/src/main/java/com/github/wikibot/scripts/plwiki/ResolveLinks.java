package com.github.wikibot.scripts.plwiki;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.Utils;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Misc;

public final class ResolveLinks {
	// from Linker::formatLinksInComment in Linker.php
	private static final String PATT_LINK = "\\[{2} *?:?(%s) *?(#[^\\|\\]]*?)?(?:\\|((?:]?[^\\]])*+))?\\]{2}([a-zęóąśłżźćńĘÓĄŚŁŻŹĆŃ]+)?";
	
	private static final List<String> SOFT_REDIR_TEMPLATES = List.of(
		"Osobny artykuł", "Osobna strona", "Główny artykuł", "Main", "Mainsec", "Zobacz też", "Seealso"
	);
	
	private static final int[] TARGET_NAMESPACES = new int[] {
		Wiki.MAIN_NAMESPACE, Wiki.USER_NAMESPACE, Wiki.TEMPLATE_NAMESPACE, 100 /* Portal */
	};

	private static Wikibot wb;
	
	public static void main(String[] args) throws Exception {
		CommandLine line = parseArguments(args);
		final var mode = line.getOptionValue("mode");
		final var target = line.getOptionValue("target");
		
		wb = Login.createSession("pl.wikipedia.org");
		
		final List<String> sources;
		final String summary;
		
		if (mode.equals("redir")) {
			sources = wb.whatLinksHere(List.of(target), true, false, Wiki.MAIN_NAMESPACE).get(0);
			System.out.printf("%d redirs: %s%n", sources.size(), sources);
			
			if (sources.isEmpty()) {
				return;
			}
			
			summary = String.format("podmiana przekierowań do „[[%s]]”", target);
		} else if (mode.equals("disamb")) {
			var source = line.getOptionValue("source");
			
			if (source == null) {
				throw new IllegalArgumentException("no source provided");
			}
			
			sources = List.of(source);
			summary = String.format("zamiana linków z „[[%s]]” na „[[%s]]”", source, target);
		} else {
			throw new IllegalArgumentException("illegal mode: " + mode);
		}
		
		var backlinks = wb.whatLinksHere(sources, false, false, TARGET_NAMESPACES).stream()
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
		
		var sourcesIgnoreCase = sources.stream()
			.flatMap(redir -> Stream.of(StringUtils.capitalize(redir), StringUtils.uncapitalize(redir)))
			.collect(Collectors.toList());
		
		var patterns = sourcesIgnoreCase.stream()
			.map(redir -> String.format(PATT_LINK, Pattern.quote(redir)))
			.map(Pattern::compile)
			.collect(Collectors.toList());
		
		BiConsumer<Matcher, StringBuffer> replaceFunc = (m, sb) -> {
			var link = m.group(1);
			var fragment = Optional.ofNullable(m.group(2)).orElse("");
			var text = Optional.ofNullable(m.group(3)).orElse(link);
			var trail = Optional.ofNullable(m.group(4)).orElse("");
			
			if (mode.equals("redir") && sources.contains(text + trail)) {
				m.appendReplacement(sb, String.format("[[%s%s]]", target, fragment));
			} else {
				m.appendReplacement(sb, String.format("[[%s%s|%s]]", target, fragment, text + trail));
			}
		};
		
		var edited = new ArrayList<String>();
		var errors = new ArrayList<String>();
		
		wb.setMarkMinor(true);
		
		for (var page : wb.getContentOfPages(backlinks)) {
			var newText = page.getText();
			
			for (var pattern : patterns) {
				newText = Utils.replaceWithStandardIgnoredRanges(newText, pattern, replaceFunc);
				newText = replaceAdditionalOccurrences(newText, target, sourcesIgnoreCase);
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
	
	private static CommandLine parseArguments(String[] args) throws ParseException {
		Options options = new Options();
		options.addRequiredOption("m", "mode", true, "script mode (redir, disamb)");
		options.addOption("s", "source", true, "source page");
		options.addRequiredOption("t", "target", true, "target page");
		
		if (args.length == 0) {
			System.out.print("Options: ");
			args = Misc.readArgs();
		}
		
		CommandLineParser parser = new DefaultParser();
		return parser.parse(options, args);
	}
	
	private static String replaceAdditionalOccurrences(String text, String target, List<String> sources) {
		for (var templateName : SOFT_REDIR_TEMPLATES) {
			for (var template : ParseUtils.getTemplatesIgnoreCase(templateName, text)) {
				var params = ParseUtils.getTemplateParametersWithValue(template);
				
				params.entrySet().stream()
					.filter(e -> StringUtils.equalsAny(template, "Zobacz też", "Seealso")
						? e.getKey().equals("ParamWithoutName1")
						: e.getKey().startsWith("ParamWithoutName"))
					.filter(e -> sources.contains(e.getValue()))
					.forEach(e -> e.setValue(target));
				
				text = Utils.replaceWithStandardIgnoredRanges(text, Pattern.quote(template), ParseUtils.templateFromMap(params));
			}
		}
		
		return text;
	}
}
