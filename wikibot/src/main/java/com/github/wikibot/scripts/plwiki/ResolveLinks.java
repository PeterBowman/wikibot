package com.github.wikibot.scripts.plwiki;

import java.nio.file.Paths;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.apache.commons.lang3.Range;
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
	
	private static final List<String> IGNORED_REDIR_TEMPLATES = List.of(
		"Inne znaczenia", "DisambigR"
	);
	
	private static final int[] TARGET_NAMESPACES = new int[] {
		Wiki.MAIN_NAMESPACE, Wiki.USER_NAMESPACE, Wiki.PROJECT_NAMESPACE, Wiki.TEMPLATE_NAMESPACE, 100 /* Portal */
	};
	
	private static final List<String> PROJECT_WHITELIST = List.of(
		"Wikipedia:Skarbnica Wikipedii", "Wikipedia:Indeks biografii"
	);
	
	private static final int USER_SUBPAGE_SIZE_LIMIT = 500000;

	private static Wikibot wb;
	
	public static void main(String[] args) throws Exception {
		CommandLine line = parseArguments(args);
		final var mode = line.getOptionValue("mode");
		final var target = line.getOptionValue("target");
		
		wb = Login.createSession("pl.wikipedia.org");
		
		final List<String> sources;
		final String summary;
		
		if (mode.equals("redir")) {
			if (line.hasOption("file")) {
				throw new IllegalArgumentException("Option 'file' not compatible with mode 'redir'");
			}
			
			sources = wb.whatLinksHere(List.of(target), true, false, TARGET_NAMESPACES).get(0);
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
		
		final Stream<String> titles;
		
		if (line.hasOption("file")) {
			var path = Paths.get(line.getOptionValue("file"));
			var namespaces = Arrays.stream(TARGET_NAMESPACES).boxed().toList();
			titles = Files.readAllLines(path).stream().filter(title -> namespaces.contains(wb.namespace(title)));
		} else {
			titles = wb.whatLinksHere(sources, false, false, TARGET_NAMESPACES).stream().flatMap(Collection::stream);
		}
		
		var backlinks = titles.sorted().distinct()
			// retain user sandboxes
			.filter(title -> wb.namespace(title) != Wiki.USER_NAMESPACE || !wb.getRootPage(title).equals(title))
			// retain biography notes
			.filter(title -> wb.namespace(title) != Wiki.PROJECT_NAMESPACE || PROJECT_WHITELIST.contains(wb.getRootPage(title)))
			.toList();
		
		System.out.printf("%d unique backlinks found.%n", backlinks.size());
		
		if (backlinks.isEmpty()) {
			return;
		}
		
		var sourcesIgnoreCase = sources.stream()
			.flatMap(redir -> Stream.of(StringUtils.capitalize(redir), StringUtils.uncapitalize(redir)))
			.toList();
		
		var patterns = sourcesIgnoreCase.stream()
			.map(redir -> String.format(PATT_LINK, Pattern.quote(redir)))
			.map(Pattern::compile)
			.toList();
		
		BiConsumer<Matcher, StringBuilder> replaceFunc = (m, sb) -> {
			var link = m.group(1);
			var fragment = Optional.ofNullable(m.group(2)).orElse("");
			var text = Optional.ofNullable(m.group(3)).orElse(link);
			var trail = Optional.ofNullable(m.group(4)).orElse("");
			
			final String replacement;
			
			if (mode.equals("redir") && sources.contains(text + trail)) {
				replacement = String.format("[[%s%s]]", target, fragment);
			} else if (fragment.isEmpty() && target.equals(text + trail)) {
				replacement = String.format("[[%s]]", target);
			} else if (fragment.isEmpty() && target.equals(text)) {
				replacement = String.format("[[%s]]%s", target, trail);
			} else {
				replacement = String.format("[[%s%s|%s]]", target, fragment, text + trail);
			}
			
			m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
		};
		
		var props = wb.getPageProps(backlinks);
		
		var edited = new ArrayList<String>();
		var errors = new ArrayList<String>();
		
		wb.setMarkMinor(true);
		
		for (var page : wb.getContentOfPages(backlinks)) {
			if (props.stream().filter(m -> page.getTitle().equals(m.get("pagename"))).anyMatch(m -> m.containsKey("disambiguation"))) {
				System.out.println("Page is a disambiguation: " + page.getTitle());
				errors.add(page.getTitle());
				continue;
			}
			
			if (StringUtils.containsAnyIgnoreCase(page.getText(), "#PATRZ", "#PRZEKIERUJ", "#TAM", "#REDIRECT")) {
				System.out.println("Page is a redirect: " + page.getTitle());
				errors.add(page.getTitle());
				continue;
			}
			
			if (
				wb.namespace(page.getTitle()) == Wiki.USER_NAMESPACE &&
				(Integer)wb.getPageInfo(List.of(page.getTitle())).get(0).get("size") > USER_SUBPAGE_SIZE_LIMIT
			) {
				System.out.println("User subpage exceeds size limit: " + page.getTitle());
				errors.add(page.getTitle());
				continue;
			}
			
			var ignoredRanges = IGNORED_REDIR_TEMPLATES.stream()
				.flatMap(templateName -> ParseUtils.getTemplatesIgnoreCase(templateName, page.getText()).stream())
				.distinct()
				.map(template -> Pattern.compile(template, Pattern.LITERAL))
				.map(patt -> Utils.findRanges(page.getText(), patt))
				.collect(Collectors.toCollection(ArrayList::new));
			
			ignoredRanges.add(Utils.getStandardIgnoredRanges(page.getText()));
			
			@SuppressWarnings("unchecked")
			var combinedRanges = (List<Range<Integer>>)Utils.getCombinedRanges(ignoredRanges.toArray(new List[0]));
			
			var newText = page.getText();
			
			for (var pattern : patterns) {
				newText = Utils.replaceWithIgnoredRanges(newText, pattern, combinedRanges, replaceFunc);
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
		options.addOption("f", "file", true, "path to worklist");
		
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
				var hash = params.hashCode();
				
				params.entrySet().stream()
					.filter(e -> StringUtils.equalsAny(templateName, "Zobacz też", "Seealso")
						? e.getKey().equals("ParamWithoutName1")
						: e.getKey().startsWith("ParamWithoutName"))
					.filter(e -> sources.contains(e.getValue()))
					.forEach(e -> e.setValue(target));
				
				if (params.hashCode() != hash) {
					text = Utils.replaceWithStandardIgnoredRanges(text, Pattern.quote(template), ParseUtils.templateFromMap(params));
				}
			}
		}
		
		for (var template : ParseUtils.getTemplatesIgnoreCase("Link-interwiki", text)) {
			var params = ParseUtils.getTemplateParametersWithValue(template);
			var local = params.getOrDefault("pl", params.getOrDefault("ParamWithoutName1", ""));
			
			if (sources.contains(local)) {
				if (params.containsKey("pl")) {
					params.put("pl", target);
				} else {
					params.put("ParamWithoutName1", target);
				}
				
				if (!params.containsKey("tekst") && !params.containsKey("ParamWithoutName4")) {
					if (!params.containsKey("ParamWithoutName3")) {
						params.put("tekst", local);
					} else {
						params.put("ParamWithoutName4", local);
					}
				}
				
				text = Utils.replaceWithStandardIgnoredRanges(text, Pattern.quote(template), ParseUtils.templateFromMap(params));
			}
		}
		
		for (var template : ParseUtils.getTemplatesIgnoreCase("Sort", text)) {
			var params = ParseUtils.getTemplateParametersWithValue(template);
			var key = params.getOrDefault("ParamWithoutName1", "");
			
			if (sources.contains(key) && !params.containsKey("ParamWithoutName2")) {
				params.put("ParamWithoutName2", String.format("[[%s|%s]]", target, key));
				text = Utils.replaceWithStandardIgnoredRanges(text, Pattern.quote(template), ParseUtils.templateFromMap(params));
			}
		}
		
		for (var template : ParseUtils.getTemplatesIgnoreCase("Sortname", text)) {
			var params = ParseUtils.getTemplateParametersWithValue(template);
			
			if (!params.containsKey("nolink")) {
				var key = "";
				
				if (params.containsKey("ParamWithoutName3")) {
					key = params.get("ParamWithoutName3");
				} else {
					var name = params.getOrDefault("ParamWithoutName1", "");
					var surname = params.getOrDefault("ParamWithoutName2", "");
					key = String.format("%s %s", name, surname);
				}
				
				if (sources.contains(key)) {
					params.put("ParamWithoutName3", target);
					text = Utils.replaceWithStandardIgnoredRanges(text, Pattern.quote(template), ParseUtils.templateFromMap(params));
				}
			}
		}
		
		return text;
	}
}
