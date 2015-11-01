package com.github.wikibot.parsing.eswikt;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.LoginException;

import org.apache.commons.lang3.Range;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableInt;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.ESWikt;
import com.github.wikibot.parsing.AbstractEditor;
import com.github.wikibot.parsing.AbstractSection;
import com.github.wikibot.parsing.Utils;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public class Editor extends AbstractEditor {
	private static final Pattern P_TMPL_DEPTH = Pattern.compile("\\{\\{(?!.*?\\{\\{).+?\\}\\}", Pattern.DOTALL);
	private static final Pattern P_PREFIXED_TEMPLATE;
	private static final Pattern P_TAGS = Pattern.compile("<(\\w+?)[^>]*?(?<!/ ?)>.+?</\\1+? ?>", Pattern.DOTALL);
	private static final Pattern P_LINE_JOINER;
	private static final Pattern P_LINE_SPLITTER_LEFT;
	private static final Pattern P_LINE_SPLITTER_BOTH;
	private static final Pattern P_TEMPLATE = Pattern.compile("\\{\\{(.+?)(\\|(?:\\{\\{.+?\\}\\}|.*?)+)?\\}\\}", Pattern.DOTALL);
	private static final Pattern P_XX_ES_TEMPLATE = Pattern.compile("\\{\\{ *?.+?-ES( *?\\| *?(\\{\\{.+?\\}\\}|.*?)+)*?\\}\\}", Pattern.DOTALL);
	private static final Pattern P_OLD_STRUCT_HEADER = Pattern.compile("^(.*?)(\\{\\{ *?(?:ES|[\\w-]+?-ES|TRANSLIT|lengua|translit)(?: *?\\| *?(?:\\{\\{.+?\\}\\}|.*?)+)*?\\}\\}) *(.*)$", Pattern.MULTILINE);
	private static final Pattern P_ADAPT_PRON_TMPL;
	private static final Pattern P_AMBOX_TMPLS;
	private static final Pattern P_TMPL_LINE = Pattern.compile("((?:<!--.*?-->| *?)*?)[:;#]*?\\* *?('{0,3}.+?:'{0,3})(.+?)(?: *?\\.)?((?:<!--.*?-->| *?)*)$", Pattern.MULTILINE);
	private static final Pattern P_IMAGES;
	private static final Pattern P_COMMENTS = Pattern.compile(" *?<!--.+-->");
	private static final Pattern P_BR_TAGS = Pattern.compile("(\n*.*?)<br\\b([^>]*?)>(.*?\n+|.*?)", Pattern.CASE_INSENSITIVE);
	private static final Pattern P_BR_CLEAR = Pattern.compile("clear *?= *?(?<quote>['\"]?)all\\k<quote>", Pattern.CASE_INSENSITIVE);
	private static final Pattern P_BR_STYLE = Pattern.compile("style *?=[^=]*?\\bclear *?:.+", Pattern.CASE_INSENSITIVE);
	private static final Pattern P_ETYM_TMPL = Pattern.compile("[:;*#]*?(\\{\\{ *?etimología2? *?(?:\\|(?:\\{\\{.+?\\}\\}|.*?)+)?\\}\\}([^\n]*))", Pattern.DOTALL);
	private static final Pattern P_LIST_ARGS = Pattern.compile("(?:[^,\\(\\)\\[\\]\\{\\}]|\\(.+?\\)|\\[\\[.+?\\]\\]|\\{\\{.+?\\}\\})+");
	private static final Pattern P_LINK = Pattern.compile("\\[\\[(.+?)(?:(?:#.+?)?\\|([^\\]]+?))?\\]\\](.*)");
	private static final Pattern P_PARENS = Pattern.compile("(.*?) \\(([^\\)]+)\\)");
	private static final Pattern P_LINK_TMPLS = Pattern.compile("(\\{\\{l\\+?\\|[^\\}]+\\}\\})(?: *?\\((.+)\\))?");
	private static final Pattern P_CATEGORY_LINKS = Pattern.compile("\\[\\[ *?(?i:category|categoría) *?:[^\\[\\{\\}]+?\\]\\]");
	private static final Pattern P_CLEAR_TMPLS = Pattern.compile("\n?\\{\\{ *?clear *?\\}\\}\n?");
	private static final Pattern P_UCF = Pattern.compile("^; *?\\d+?(?: *?\\{\\{[^\\{]+?\\}\\})? *?: *?(\\[\\[:?([^\\]\\|]+)(?:\\|((?:\\]?[^\\]\\|])*+))*\\]\\])(.*)$", Pattern.MULTILINE);
	private static final Pattern P_TERM = Pattern.compile("^;( *?\\d+?)( *?\\{\\{[^\\{]+?\\}\\})?( *?:)(.*)$", Pattern.MULTILINE);
	
	private static final List<String> LENG_PARAM_TMPLS = Arrays.asList(
		"etimología", "etimología2", "transliteración", "homófono", "grafía alternativa", "variantes",
		"parónimo", "sinónimo", "antónimo", "hiperónimo", "hipónimo", "uso", "ámbito", "apellido",
		"doble conjugación", "derivad", "grafía", "pron-graf", "rima", "relacionado", "pronunciación",
		"diacrítico", "ampliable"
	);
	
	private static final List<String> PRON_TMPLS = Arrays.asList(
		"pronunciación", "pron.la",  "audio", "transliteración", "homófono", "grafía alternativa",
		"variantes", "parónimo", "diacrítico", "ortografía alternativa"
	);
	
	private static final List<String> PRON_TMPLS_ALIAS = Arrays.asList(
		null, null, null, "transliteraciones", "homófonos", "grafías alternativas",
		"variante", "parónimos", null, "ortografías alternativas"
	);
	
	private static final List<String> TERM_TMPLS = Arrays.asList(
		"ámbito", "uso", "sinónimo", "antónimo", "hipónimo", "hiperónimo", "relacionado", "anagrama", "derivado"
	);
	
	private static final List<String> TERM_TMPLS_ALIAS = Arrays.asList(
		null, null, "sinónimos", "antónimos", "hipónimos", "hiperónimos", "relacionados", "anagramas", "derivados"
	);
	
	// https://es.wiktionary.org/wiki/Categor%C3%ADa:Wikcionario:Plantillas_de_mantenimiento
	private static final List<String> AMBOX_TMPLS = Arrays.asList(
		"ampliable", "creado por bot", "definición", "discutido", "esbozo", "stub", "estructura", "formato",
		"falta", "revisión", "revisar"
	);
	
	private static final List<String> SPANISH_PRON_TMPL_PARAMS = Arrays.asList(
		"y", "ll", "s", "c", "ys", "yc", "lls", "llc"
	);
	
	private static final List<String> RECONSTRUCTED_LANGS = Arrays.asList(
		"poz-pol", "ine",
		"chono" // https://es.wiktionary.org/w/index.php?title=cot&diff=3383057&oldid=3252255
	);
	
	private static final List<Pattern> COMMENT_PATT_LIST;
	private static final List<String> LS_SPLITTER_LIST;
	private static final List<String> BS_SPLITTER_LIST;
	private static final List<String> STANDARD_HEADERS;
	
	private static final String TRANSLATIONS_TEMPLATE;
	private static final String HAS_FLEXIVE_FORM_HEADER_RE = "([Ff]orma|\\{\\{forma) .+";
	
	private static final Predicate<LangSection> FLEXIVE_FORM_CHECK;
	
	private boolean isOldStructure;
	
	static {
		final List<String> templateNsAliases = Arrays.asList("Template", "Plantilla", "msg");
		
		P_PREFIXED_TEMPLATE = Pattern.compile("\\{\\{([ :]*?(?:" + String.join("|", templateNsAliases) + ") *?: *).+?(?:\\|(?:\\{\\{.+?\\}\\}|.*?)+)?\\}\\}", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
		
		final List<String> fileNsAliases = Arrays.asList("File", "Image", "Archivo", "Imagen");
		
		final List<String> categoryNsAliases = Arrays.asList("Category", "Categoría");
		
		final List<String> specialLinksList = new ArrayList<>(Page.INTERWIKI_PREFIXES.length + fileNsAliases.size());
		specialLinksList.addAll(fileNsAliases);
		specialLinksList.addAll(categoryNsAliases);
		specialLinksList.addAll(Arrays.asList(Page.INTERWIKI_PREFIXES));
		
		String specialLinksGroup = String.join("|", specialLinksList);
		
		/* TODO: limited look-behind group length
		 *  multiple whitespaces are treated as single ws (" *?" -> " ?")
		 *  unable to process bundled "special" and page links ("[[File:test]] [[a]]\ntest")
		 */
		// TODO: review headers ("=" signs)
		P_LINE_JOINER = Pattern.compile("(?<![\n>=]|__|-{4}|\\}\\}|\\|\\}|\\[\\[ ?(?:" + specialLinksGroup + ") ?:.{1,300}?\\]\\])\n(?!\\[\\[ *?(?:" + specialLinksGroup + "):(?:\\[\\[.+?\\]\\]|\\[.+?\\]|.*?)+\\]\\]|\\{\\|)(<ref[ >]|[^\n<:;#\\*\\{\\}\\|=!])", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
		
		final List<String> tempListLS = Arrays.asList(
			"t+", "descendiente", "desc", "anotación", "etimología", "etimología2"
		);
		
		LS_SPLITTER_LIST = new ArrayList<>(PRON_TMPLS.size() + TERM_TMPLS.size() + tempListLS.size());
		LS_SPLITTER_LIST.addAll(PRON_TMPLS);
		LS_SPLITTER_LIST.addAll(TERM_TMPLS);
		LS_SPLITTER_LIST.addAll(tempListLS);
		LS_SPLITTER_LIST.remove("audio");
		
		String tempListLSGroup = LS_SPLITTER_LIST.stream()
			.map(Pattern::quote)
			.collect(Collectors.joining("|"));
		
		P_LINE_SPLITTER_LEFT = Pattern.compile("(?<!\n[ :;*#]{0,5})(\\{\\{ *?(?:" + tempListLSGroup + ") *?(?:\\|(?:\\{\\{.+?\\}\\}|.*?)+)*\\}\\})", Pattern.DOTALL);
		
		final List<String> tempListBS = Arrays.asList(
			"desambiguación", "arriba", "centro", "abajo", "escond-arriba", "escond-centro",
			"escond-abajo", "rel-arriba", "rel-centro", "rel-abajo", "trad-arriba",
			"trad-centro", "trad-abajo", "rel4-arriba", "rel4-centro", "clear", "derivados",
			"título referencias", "pron-graf", "imagen", "listaref"
		);
		
		BS_SPLITTER_LIST = new ArrayList<>(AMBOX_TMPLS.size() + tempListBS.size());
		BS_SPLITTER_LIST.addAll(AMBOX_TMPLS);
		BS_SPLITTER_LIST.addAll(tempListBS);
		
		// TODO: ignore comments (https://es.wiktionary.org/w/index.php?title=lombriz&diff=3376825&oldid=3362646)
		P_LINE_SPLITTER_BOTH = Pattern.compile("(\n?)[ :;*#]*?(\\{\\{ *?(?:" + String.join("|", BS_SPLITTER_LIST) + ") *?(?:\\|(?:\\{\\{.+?\\}\\}|.*?)+)*\\}\\}) *(\n?)", Pattern.DOTALL);
		
		P_ADAPT_PRON_TMPL = Pattern.compile("^[ :;*#]*?\\{\\{ *?(" + String.join("|", PRON_TMPLS) + ") *?(?:\\|[^\\{]*?)?\\}\\}[.\\s]*((?:<!--.*?-->|<!--.*)*\\s*)$");
		
		P_AMBOX_TMPLS = Pattern.compile("[ :;*#]*?\\{\\{ *?(" + String.join("|", AMBOX_TMPLS) + ") *?(?:\\|.*)?\\}\\}( *?<!--.+?-->)*", Pattern.CASE_INSENSITIVE);

		P_IMAGES = Pattern.compile("[ :;*#]*?\\[\\[ *?(" + String.join("|", fileNsAliases) + ") *?:(?:\\[\\[.+?\\]\\]|\\[.+?\\]|.*?)+\\]\\]( *?<!--.+?-->)*", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

		STANDARD_HEADERS = new ArrayList<>(Section.HEAD_SECTIONS.size() + Section.BOTTOM_SECTIONS.size());
		STANDARD_HEADERS.addAll(Section.HEAD_SECTIONS);
		STANDARD_HEADERS.addAll(Section.BOTTOM_SECTIONS);
		
		List<String> translationsTemplate = Arrays.asList(
			"{{trad-arriba}}",
			"<!-- formato: {{t+|idioma|<acepción#>|palabra|género}} p. ej. {{t+|fr|1|chose|f}} -->",
			"{{trad-centro}}",
			"{{trad-abajo}}"
		);
		
		TRANSLATIONS_TEMPLATE = String.join("\n", translationsTemplate);
		
		final List<String> pCommentsList = Arrays.asList(
			"<!--( *?|\n*?)-->",
			"<!-- ?si hay términos que se diferencian .*?-->",
			"<!-- ?(en|EN) para inglés,.*?-->",
			"<!---? ?Añádela en el Alfabeto Fonético Internacional.*?-->",
			"<!---? ?Añade la pronunciación en el Alfabeto Fonético Internacional.*?-->",
			"<!-- ?A[ñn]ádela con el siguiente patrón.*?-->",
			"<!-- ?Añade la etimología con el siguiente patrón.*?-->",
			"<!-- ?(y/)?o femenino\\|es\\}\\}.*?-->",
			"<!-- ?o (femenino|masculino)s?(\\]\\])?(===)? ?-->",
			"<!-- ?o \\{\\{adverbio de tiempo\\|es\\}\\}.*?-->",
			"<!-- ?(o )?intransitivo.*?-->",
			"<!-- ?¿flexión\\?: mira en Categoría:.*?-->",
			"(?s)<!-- ?Escoge la plantilla adecuada .*?-->",
			"(?s)<!-- ?Utiliza cualquiera de las siguientes plantillas .*?-->",
			"<!-- ?explicación de lo que significa la palabra -->",
			"<!-- ?(; )?si pertenece a un campo semántico .*?-->",
			"<!-- ?(;2: )?si hay más acepciones.*?-->",
			"<!-- ?si hay más que una acepción,.*?-->",
			"(?s)<!-- ?puedes incluir uno o más de los siguientes campos .*?-->",
			"<!-- ?\\{\\{ámbito(\\|leng=xx)?\\|<ÁMBITO 1>\\|<ÁMBITO2>\\}\\}.*?-->",
			"<!-- ?\\{\\{uso(\\|leng=xx)?\\|\\}\\}.*?-->",
			"<!-- ?\\{\\{sinónimo(\\|leng=xx)?\\|<(SINÓNIMO )?1>\\|<(SINÓNIMO )?2>\\}\\}.*?-->",
			"<!-- ?\\{\\{antónimo(\\|leng=xx)?\\|<(ANTÓNIMO )?1>\\|<(ANTÓNIMO )?2>\\}\\}.*?-->",
			"<!-- ?\\{\\{hipónimo(\\|leng=xx)?\\|<(HIPÓNIMO )?1>\\|<(HIPÓNIMO )?2>\\}\\}.*?-->",
			"<!-- ?\\{\\{hiperónimo(\\|leng=xx)?\\|<(HIPERÓNIMO )?1>\\|<(HIPERÓNIMO )?2>\\}\\}.*?-->",
			"<!-- ?\\{\\{relacionado(\\|leng=xx)?\\|<1>\\|<2>\\}\\}.*?-->",
			"<!-- ?\\{\\{ejemplo\\|<oración.*?-->",
			"<!-- ?\\{\\{ejemplo\\}\\} ?-->",
			"<!-- ?aquí pones una explicaci[óo]n .*?-->",
			"<!-- ?aquí escribes una explicaci[óo]n .*?-->",
			"<!-- ?si tienes información adicional.*?-->",
			"(?s)<!-- ?Puedes también incluir las siguientes secciones.*?-->",
			"<!-- ?\\{\\{etimología\\|IDIOMA.*?-->",
			"<!-- ?posiblemente desees incluir una imagen.*?-->",
			"<!-- ?si se trata de un país.*?-->",
			"(?s)<!-- ?puedes también incluir locuciones.*?-->",
			"(?s)<!-- ?Incluir la plantilla de conjugación aquí.*?-->",
			"(?s)<!-- ?otra sección opcional para enlaces externos.*?-->",
			"<!-- ?¿flexión?: mira en .*?-->",
			"<!-- ?\\{\\{inflect.sust.sg-pl\\|AQUÍ EL SINGULAR.*?-->",
			"<!---? ?\\{\\{pronunciación(\\|leng=.*?)?\\|?(\\[ ?(ˈ|eˈxem.plo) ?\\])?\\}\\}.*?-->",
			"<!-- ?\\{\\{pronunciación\\|(\\[.+?\\]| ˈ )\\}\\} \\|-\\|c=.+?\\|s=.+?(\\}\\}|\\|) *?-->",
			"<!-- ?en general, no se indica la etimología .*?-->",
			"<!-- ?si vas a insertar una nueva sección de etimología o de idioma.*?-->",
			"<!-- ?si se trata de un país,? por favor,? pon.*?-->",
			"<!-- *?apellidos .*?-->",
			"<!-- *?antropónimos .*?-->",
			"<!-- *?apéndice .*?-->",
			"<!-- ?(primera|segunda) locución ?-->"
		);
		
		COMMENT_PATT_LIST = pCommentsList.stream()
			.map(Pattern::compile)
			.collect(Collectors.toList());
		
		FLEXIVE_FORM_CHECK = langSection -> {
			List<Section> allSubsections = AbstractSection.flattenSubSections(langSection);
			allSubsections.remove(langSection);
			
			List<String> allHeaders = allSubsections.stream()
				.map(AbstractSection::getStrippedHeader)
				.collect(Collectors.toList());
			
			allHeaders.removeIf(STANDARD_HEADERS::contains);
			
			if (!allHeaders.removeIf(header -> header.matches(HAS_FLEXIVE_FORM_HEADER_RE))) {
				return true;
			}
			
			return allHeaders.isEmpty();
		};
	}
	
	public Editor(Page page) {
		super(page.getTitle(), page.toString());
		checkOldStructure(text);
	}
	
	public Editor(PageContainer pc) {
		super(pc.getTitle(), pc.getText());
		checkOldStructure(text);
	}
	
	private void checkOldStructure(String text) {
		text = ParseUtils.removeCommentsAndNoWikiText(text);
		Matcher m = P_XX_ES_TEMPLATE.matcher(text);
		
		if (
			m.find() ||
			!ParseUtils.getTemplates("ES", text).isEmpty() ||
			!ParseUtils.getTemplates("TRANSLIT", text).isEmpty() ||
			!ParseUtils.getTemplates("TRANS", text).isEmpty() ||
			!ParseUtils.getTemplates("TAXO", text).isEmpty() ||
			!ParseUtils.getTemplates("carácter oriental", text).isEmpty()
		) {
			isOldStructure = true;
		} else {
			isOldStructure = false;
		}
	}
	
	@Override
	public void check() {
		removeComments();
		
		try {
			failsafeCheck();
		} catch (Error e) {
			throw e;
		}
		
		removeTemplatePrefixes();
		sanitizeTemplates();
		joinLines();
		normalizeTemplateNames();
		splitLines();
		minorSanitizing();
		transformToNewStructure();
		
		// TODO
		if (!checkFlexiveFormHeaders()) {
			throw new UnsupportedOperationException("checkFlexiveFormHeaders()");
		}
		
		normalizeSectionHeaders();
		substituteReferencesTemplate();
		duplicateReferencesSection();
		moveReferencesSection();
		normalizeEtymologyHeaders();
		normalizeSectionLevels();
		removePronGrafSection();
		sortLangSections();
		addMissingSections();
		moveReferencesElements();
		sortSubSections();
		removeInflectionTemplates();
		manageAnnotationTemplates();
		manageDisambigTemplates();
		adaptPronunciationTemplates();
		convertToTemplate();
		addMissingElements();
		removeCategoryLinks();
		checkLangHeaderCodeCase();
		langTemplateParams();
		deleteEmptySections();
		deleteWrongSections();
		manageClearElements();
		applyUcfTemplates();
		strongWhitespaces();
		weakWhitespaces();
	}
	
	private void failsafeCheck() {
		if (getMaximumTemplateDepth(text) > 2) {
			throw new Error("Maximum template depth > 2");
		}
		
		Page page = Page.store(title, text);
		
		if (hasUnpairedBrackets(page.getIntro(), "{{", "}}")) {
			throw new Error("Unpaired curly brackets in Page intro");
		}
		
		if (hasUnpairedBrackets(page.getIntro(), "[[", "]]")) {
			throw new Error("Unpaired square brackets in Page intro");
		}
		
		List<Section> sections = page.getAllSections();
		
		for (int index = 0; index < sections.size(); index++) {
			Section section = sections.get(index);
			
			if (hasUnpairedBrackets(section.getIntro(), "{{", "}}")) {
				throw new Error("Unpaired curly brackets in Section intro (#" + (index + 1) + ")");
			}
			
			if (hasUnpairedBrackets(section.getIntro(), "[[", "]]")) {
				throw new Error("Unpaired square brackets in Section intro (#" + (index + 1) + ")");
			}
		}
	}
	
	private static boolean hasUnpairedBrackets(String text, String open, String close) {
		int left = StringUtils.countMatches(text, open);
		int right = StringUtils.countMatches(text, close);
		
		return left != right;
	}
	
	private static int getMaximumTemplateDepth(String text) {
		List<Range<Integer>> ranges = new ArrayList<>();
		extractTemplateRanges(text, ranges);
		
		Range<Integer> previousRange = null;
		int currentDepth = 1;
		int maxDepth = 0;
		
		for (Range<Integer> range : ranges) {
			if (previousRange != null && range.containsRange(previousRange)) {
				currentDepth++;
			} else {
				currentDepth = 1;
			}
			
			previousRange = range;
			maxDepth = Math.max(currentDepth, maxDepth);
		}
		
		return maxDepth;
	}
	
	private static void extractTemplateRanges(String text, List<Range<Integer>> ranges) {
		Matcher m = P_TMPL_DEPTH.matcher(text);
		StringBuffer sb = new StringBuffer(text.length());
		
		while (m.find()) {
			ranges.add(Range.between(m.start(), m.end()));
			String replacement = StringUtils.repeat('-', m.group().length());
			m.appendReplacement(sb, replacement);
		}
		
		if (sb.length() != 0) {
			m.appendTail(sb);
			extractTemplateRanges(sb.toString(), ranges);
		}
	}
	
	private boolean checkFlexiveFormHeaders() {
		if (isOldStructure) {
			return true;
		}
		
		return Page.store(title, text).getAllLangSections().stream()
			.allMatch(FLEXIVE_FORM_CHECK);
	}

	public void removeComments() {
		@SuppressWarnings("unchecked")
		Range<Integer>[][] tempArray = COMMENT_PATT_LIST.stream()
			.map(patt -> Utils.findRanges(text, patt))
			.toArray(Range[][]::new);
		
		List<Range<Integer>> selectedRanges = Utils.getCombinedRanges(tempArray);
		List<Range<Integer>> ignoredRanges = Arrays.asList(Utils.findRanges(text, "<!--", "-->"));
		Iterator<Range<Integer>> iterator = selectedRanges.iterator();
		
		while (iterator.hasNext()) {
			if (!ignoredRanges.contains(iterator.next())) {
				iterator.remove();
			}
		}
		
		String formatted;
		
		if (!selectedRanges.isEmpty()) {
			StringBuilder sb = new StringBuilder(text.length());
			Range<Integer> lastRange = null;
			
			for (Range<Integer> currentRange : selectedRanges) {
				int start = (lastRange != null) ? lastRange.getMaximum() + 1 : 0;
				int end = currentRange.getMinimum();
				sb.append(text.substring(start, end));
				lastRange = currentRange;
			}
			
			sb.append(text.substring(lastRange.getMaximum() + 1));
			formatted = sb.toString();
		} else {
			formatted = text;
		}
		
		Page page = Page.store(title, formatted);
		
		for (Section section : page.getAllSections()) {
			String header = section.getHeader();
			header = header.replaceAll("<!-- *?tipo de palabra, por ejemplo .*?-->", "");
			header = header.replaceAll("<!-- *?tipo de palabra \\(es=español\\): .*?-->", "");
			header = header.replaceAll(" {2,}", " ");
			section.setHeader(header);
		}
		
		formatted = page.toString();
		checkDifferences(formatted, "removeComments", "eliminando comentarios");
	}
	
	public void removeTemplatePrefixes() {
		Set<String> set = new HashSet<>();
		
		String formatted = Utils.replaceWithStandardIgnoredRanges(text, P_PREFIXED_TEMPLATE,
			(m, sb) -> {
				String template = m.group();
				String prefix = m.group(1);
				
				int startOffset = m.start(1) - m.start();
				int endOffset = startOffset + prefix.length();
				
				template = template.substring(0, startOffset) + template.substring(endOffset);
				
				set.add(prefix.trim());
				m.appendReplacement(sb, Matcher.quoteReplacement(template));
			}
		);
		
		if (set.isEmpty()) {
			return;
		}
		
		String summary = String.format("eliminando %s", String.join(", ", set));
		checkDifferences(formatted, "removeTemplatePrefixes", summary);
	}
	
	public void sanitizeTemplates() {
		MutableBoolean makeSummary = new MutableBoolean(false);
		
		String formatted = Utils.replaceWithStandardIgnoredRanges(text, P_TEMPLATE,
			(m, sb) -> {
				String template = m.group();
				String templateName = m.group(1);
				templateName = templateName.replaceFirst("^\\s*(.+?) *$", "$1");
				
				int startOffset = m.start(1) - m.start();
				int endOffset = startOffset + m.group(1).length();
				
				template = template.substring(0, startOffset) + templateName +
					template.substring(endOffset);
				
				String[] lines = template.split("\n");
				
				if (lines.length == 2 && lines[1].trim().equals("}}")) {
					template = lines[0].trim() + lines[1].trim();
				}
				
				m.appendReplacement(sb, "");
				templateName = templateName.trim();
				
				if (
					templateName.startsWith("inflect.") ||
					LS_SPLITTER_LIST.contains(templateName) ||
					BS_SPLITTER_LIST.contains(templateName)
				) {
					String sbCopy = sb.toString();
					
					while (sb.toString().matches("^(?s:.*\n)?[ :;*#]+?\n?$")) {
						sb.deleteCharAt(sb.length() - 1);
					}
					
					String deletedString = sbCopy.substring(sb.length());
					
					if (!deletedString.trim().isEmpty()) {
						makeSummary.setTrue();
					}
				}
				
				sb.append(template);
			}
		);
		
		String summary = makeSummary.booleanValue()
			? "\"\\n[:;*#]{{\" → \"\\n{{\""
			: null;
		
		checkDifferences(formatted, "sanitizeTemplates", summary);
	}
	
	public void joinLines() {
		Range<Integer>[] tags = Utils.findRanges(text, P_TAGS);
		Range<Integer>[] refs = Utils.findRanges(text, Pattern.compile("<ref[ >].+?</ref *?>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE));
		Range<Integer>[] templates = Utils.findRanges(text, "{{", "}}");
		Range<Integer>[] wikitables = Utils.findRanges(text, "{|", "|}");
		
		List<Range<Integer>> tagRanges = Utils.getCombinedRanges(tags);
		List<Range<Integer>> refRanges = Utils.getCombinedRanges(refs);
		List<Range<Integer>> templateRanges = Utils.getCombinedRanges(templates);
		List<Range<Integer>> wikitableRanges = Utils.getCombinedRanges(wikitables);
		
		String formatted = Utils.replaceWithStandardIgnoredRanges(text, P_LINE_JOINER,
			(m, sb) -> {
				boolean isRefRange = Utils.containedInRanges(refRanges, m.start());
				
				if (
					!isRefRange &&
					// assume <ref> regions cannot contain these elements 
					(
						Utils.containedInRanges(tagRanges, m.start()) ||
						Utils.containedInRanges(templateRanges, m.start()) ||
						Utils.containedInRanges(wikitableRanges, m.start())
					)
				) {
					return;
				}
				
				// TODO: review reference tags
				// https://es.wiktionary.org/w/index.php?title=casa&diff=2912203&oldid=2906951
				
				String replacement = null;
				
				if (ParseUtils.removeCommentsAndNoWikiText(m.group(1)).startsWith(" ")) {
					if (isRefRange) {
						replacement = m.group(1).replaceFirst("^[ \n]+", "");
						replacement = Matcher.quoteReplacement(replacement);
					} else {
						return;
					}
				} else {
					replacement = "$1";
				}
				
				int index = text.substring(0, m.start()).lastIndexOf("\n");
				String previousLine = text.substring(index + 1, m.start());
				previousLine = ParseUtils.removeCommentsAndNoWikiText(previousLine);
				
				final String[] arr = isRefRange
					? new String[]{":", ";", "*", "#"}
					: new String[]{" ", ":", ";", "*", "#"};
				
				if (!previousLine.isEmpty() && StringUtils.startsWithAny(previousLine, arr)) {
					return;
				}
				
				index = text.indexOf("\n", m.start(1));
				String thisLine = text.substring(m.start(1), index != -1 ? index : text.length());
				
				if (thisLine.startsWith(" |")) { // template parameters and wikitable rows
					return;
				}
				
				m.appendReplacement(sb, " " + replacement);
			}
		);
		
		checkDifferences(formatted, "joinLines", "uniendo líneas");
	}
	
	public void normalizeTemplateNames() {
		String formatted = text;
		Map<String, String> map = new HashMap<>(150, 1);
		
		// TODO: expand per [[Especial:TodasLasRedirecciones]]
		// TODO: delete obsolete templates
		
		map.put("Pronunciación", "pronunciación");
		map.put("Etimología", "etimología");
		map.put("etimologia", "etimología");
		map.put("etyl", "etimología");
		map.put("Desambiguación", "desambiguación");
		map.put("Desambiguacion", "desambiguación");
		map.put("desambiguacion", "desambiguación");
		map.put("desamb", "desambiguación");
		map.put("Desambig", "desambiguación");
		map.put("Notadesambiguación", "desambiguación");
		map.put("Desambiguado", "desambiguación");
		map.put("grafías alternativas", "grafía alternativa");
		map.put("ucf", "plm");
		map.put("Anagramas", "anagrama");
		map.put("Parónimos", "parónimo");
		map.put("tit ref", "título referencias");
		
		map.put("DRAE1914", "DLC1914");
		map.put("DUE", "MaríaMoliner");
		map.put("Moliner", "MaríaMoliner");
		map.put("NDLC1866", "Labernia1866");
		map.put("dlc1914", "DLC1914");
		map.put("dme1831", "DME1831");
		map.put("dme1864", "DME1864");
		map.put("dp2002", "DP2002");
		map.put("drae1914", "DLC1914");
		
		// TODO: these are not transcluded, analyze "leng" params instead
		// TODO: move to langTemplateParams()
		
		map.put("aka", "ak");
		map.put("allentiac", "qbt");
		map.put("arg", "an");
		map.put("aus-dar", "0hk");
		map.put("ava", "av");
		map.put("ave", "ae");
		map.put("bak", "ba");
		map.put("bam", "bm");
		map.put("bat-smg", "sgs");
		map.put("be-x-old", "be");
		map.put("bod", "bo");
		map.put("ces", "cs");
		map.put("cha", "ch");
		map.put("che", "ce");
		map.put("chu", "cu");
		map.put("chv", "cv");
		map.put("cor", "kw");
		map.put("cos", "co");
		map.put("cre", "cr");
		map.put("cym", "cy");
		map.put("div", "dv");
		map.put("ewe", "ee");
		map.put("fas", "fa");
		map.put("fiu-vro", "vro");
		map.put("ful", "ff");
		map.put("gkm", "qgk");
		map.put("gla", "gd");
		map.put("gle", "ga");
		map.put("glv", "gv");
		map.put("her", "hz");
		map.put("hmo", "ho");
		map.put("hrv", "hr");
		map.put("hye", "hy");
		map.put("ind", "id");
		map.put("jav", "jv");
		map.put("kal", "kl");
		map.put("kat", "ka");
		map.put("lzh", "zh-classical");
		map.put("mah", "mh");
		map.put("mlt", "mt");
		map.put("mri", "mi");
		map.put("nb", "no");
		map.put("nrm", "nrf");
		map.put("oji", "oj");
		map.put("ood", "nai");
		map.put("protoindoeuropeo", "ine");
		map.put("prv", "oc");
		map.put("roa-oca", "oca");
		map.put("roa-rup", "rup");
		map.put("roa-tara", "roa-tar");
		map.put("roh", "rm");
		map.put("sna", "sn");
		map.put("srd", "sc");
		map.put("srp", "sr");
		map.put("ssw", "ss");
		map.put("tsz", "pua");
		map.put("yue", "zh-yue");
		map.put("zh-cn", "zh");
		map.put("zh-min-nan", "nan");
		map.put("zh-wuu", "wuu");
		
		map.put("AR", "AR-ES");
		map.put("CA", "CA-ES");
		map.put("CHN", "CMN-ES");
		map.put("EN", "EN-ES");
		map.put("EU", "EU-ES");
		map.put("FR", "FR-ES");
		map.put("GL", "GL-ES");
		map.put("IT", "IT-ES");
		map.put("LA", "LA-ES");
		map.put("MN", "MN-ES");
		map.put("PL", "PL-ES");
		map.put("PT", "PT-ES");
		map.put("QU", "QU-ES");
		
		map.put("Allentiac-ES", "QBT-ES");
		map.put("BAT-SMG-ES", "SGS-ES");
		map.put("CYM-ES", "CY-ES");
		map.put("FIU-VRO-ES", "VRO-ES");
		map.put("FRS-ES", "STQ-ES");
		map.put("LZH-ES", "ZH-CLASSICAL-ES");
		map.put("MYA-ES", "MY-ES");
		map.put("NLD-ES", "NL-ES");
		map.put("OFR-ES", "FRO-ES");
		map.put("PROTOPOLINESIO-ES", "POZ-POL-ES");
		map.put("Protopolinesio-ES", "POZ-POL-ES");
		map.put("PRV-ES", "OC-ES");
		map.put("TGL-ES", "TL-ES");
		map.put("TOG-ES", "TO-ES");
		map.put("TSZ-ES", "PUA-ES");
		map.put("YUE-ES", "ZH-YUE-ES");
		map.put("ZH-ES", "CMN-ES");
		
		map.put("Indoeuropeo", "INE-ES");
		map.put("castellanoantiguo", "OSP-ES");
		map.put("Aymará-Español", "AY-ES");
		map.put("Guaraní-Español", "GN-ES");
		map.put("Náhuatl-Español", "NAH-ES");
		map.put("Quechua-Español", "QU-ES");
		
		map.put("Caracter oriental", "Carácter oriental");
		
		List<String> found = new ArrayList<>();
		
		for (Entry<String, String> entry : map.entrySet()) {
			final String target = entry.getKey();
			final String replacement = entry.getValue();
			
			final Pattern patt = Pattern.compile(
				"\\{\\{ *?" + target + " *?(\\|(?:\\{\\{.+?\\}\\}|.*?)+)?\\}\\}",
				Pattern.DOTALL
			);
			
			String temp = Utils.replaceWithStandardIgnoredRanges(formatted, patt,
				(m, sb) -> m.appendReplacement(sb, "{{" + replacement + "$1}}")
			);
			
			if (!temp.equals(formatted)) {
				formatted = temp;
				found.add(target);
			}
		}
		
		if (found.isEmpty()) {
			return;
		}
		
		// Must check again due to {{XX}} -> {{XX-ES}} replacements
		checkOldStructure(formatted);
		
		String summary = found.stream()
			.map(target -> String.format("{{%s}} → {{%s}}", target, map.get(target)))
			.collect(Collectors.joining(", "));
		
		checkDifferences(formatted, "normalizeTemplateNames", summary);
	}
	
	public void splitLines() {
		String formatted = Utils.replaceWithStandardIgnoredRanges(text, P_LINE_SPLITTER_LEFT,
			(m, sb) -> m.appendReplacement(sb, "\n$1")
		);
		
		final int stringLength = formatted.length();
		MutableInt lastTrailingPos = new MutableInt(-1);
		
		formatted = Utils.replaceWithStandardIgnoredRanges(formatted, P_LINE_SPLITTER_BOTH,
			m -> m.start(1),
			(m, sb) -> {
				String pre = m.group(1);
				String target = m.group(2);
				String post = m.group(3);
				
				boolean atStringStart = (m.start(1) == 0);
				boolean atStringEnd = (m.end(3) == stringLength);
				
				if (
					!(atStringStart || pre.equals("\n")) ||
					!(atStringEnd || post.equals("\n"))
				) {
					StringBuilder replacement = new StringBuilder(target.length() + 2);
					
					if (
						!atStringStart &&
						m.start() != lastTrailingPos.intValue()
					) {
						replacement.append('\n');
					}
					
					replacement.append(target);
					
					if (!atStringEnd) {
						replacement.append('\n');
					}
					
					m.appendReplacement(sb, Matcher.quoteReplacement(replacement.toString()));
				}
				
				lastTrailingPos.setValue(m.end());
			}
		);
		
		formatted = Utils.sanitizeWhitespaces(formatted);
		checkDifferences(formatted, "splitLines", "dividiendo líneas");
	}
	
	public void minorSanitizing() {
		// TODO: perrichines (comment close tag)
		// TODO: trailing period after {{etimología}} and {{pron-graf}}
		// TODO: catch open comment tags in arbitrary Sections - [[Especial:PermaLink/2709606]]
		
		final String preferredFileNSAlias = "Archivo";
		Set<String> setFileAlias = new HashSet<>();
		Set<String> setLog = new LinkedHashSet<>();
		
		String formatted = Utils.replaceWithStandardIgnoredRanges(text, P_IMAGES,
			(m, sb) -> {
				int startOffset = m.start(1) - m.start();
				int endOffset = startOffset + m.group(1).length();
				
				String file = m.group();
				String alias = m.group(1);
				
				if (!alias.equals(preferredFileNSAlias)) {
					setFileAlias.add(alias + ":");
				}
				
				file = file.substring(0, startOffset).replaceFirst("\\s*$", "")
					+ preferredFileNSAlias
					+ file.substring(endOffset).replaceFirst("^\\s*", "").replaceFirst("^:\\s*", ":");
				
				m.appendReplacement(sb, Matcher.quoteReplacement(file));
			}
		);
		
		if (!setFileAlias.isEmpty()) {
			String log = String.format("%s → %s:", String.join(", ", setFileAlias), preferredFileNSAlias);
			setLog.add(log);
		}
		
		// TODO: detect comment tags
		//formatted = formatted.replaceAll("(?m)^((?:<!--.*?-->)*)[:;]$", "$1");
		
		formatted = applyReplacementFunction(formatted, setLog, "\"^[.,:;*#]$\" → \"\"", text -> {
			text = Utils.replaceWithStandardIgnoredRanges(text, "(?<=[^\n])\n+?[.,:;*#]+\n", "\n\n");
			text = Utils.replaceWithStandardIgnoredRanges(text, "^\n*?[.,:;*#]+\n", "");
			return text;
		});
		
		formatted = applyReplacementFunction(formatted, setLog, "\"^* [[]]$\" → \"\"", text ->
			Utils.replaceWithStandardIgnoredRanges(text, "(?m)^\\* ?\\[\\[\\]\\]$", "")
		);
		
		formatted = applyReplacementFunction(formatted, setLog, "\"^;[2-9]:$\" → \"\"", text ->
			Utils.replaceWithStandardIgnoredRanges(text, "(?m)^; ?[2-9]: ?\\.?$", "")
		);
		
		formatted = applyReplacementFunction(formatted, setLog, "\"^;\\d.+\" → \";\\d:.+\"", text ->
			Utils.replaceWithStandardIgnoredRanges(text, "(?m)^;\\s*?(\\d+?)\\s*(\\{\\{plm[\\|\\}][^:]+?|(?!\\s*?\\{\\{)[^:]+?)$", ";$1: $2")
		);
		
		String summary = null;
		
		if (!setLog.isEmpty()) {
			summary = String.join(", ", setLog);
		}
		
		checkDifferences(formatted, "minorSanitizing", summary);
	}
	
	private static String applyReplacementFunction(String text, Set<String> set, String log, Function<String, String> func) {
		String testString = func.apply(text);
		
		if (!testString.equals(text)) {
			set.add(log);
			return testString;
		} else {
			return text;
		}
	}
	
	public void transformToNewStructure() {
		Page page = Page.store(title, text);
		
		if (
			!isOldStructure ||
			!ParseUtils.getTemplates("TRANSLIT", text).isEmpty() ||
			!ParseUtils.getTemplates("TRANS", text).isEmpty() ||
			!ParseUtils.getTemplates("TAXO", text).isEmpty() ||
			!ParseUtils.getTemplates("carácter oriental", text).isEmpty()
		) {
			return;
		}
		
		// Process header templates
		
		String formatted = replaceOldStructureTemplates(title, text);
		
		if (formatted.equals(text)) {
			return;
		}
		
		page = Page.store(page.getTitle(), formatted);
		
		// Push down old-structure sections
		
		for (Section section : page.getAllSections()) {
			if (section.getLangSectionParent() == null) {
				section.setLevel(section.getLevel() + 1);
			}
		}
		
		// References section(s)
		
		for (Section section : page.findSectionsWithHeader("(<small *?>)? *?[Rr]eferencias.*?(<small *?/ *?>)?")) {
			section.setHeader("Referencias y notas");
			section.setLevel(2);
		}
		
		// TODO: add a method to reparse all Sections?
		page = Page.store(page.getTitle(), page.toString());
		
		// Process "alt" parameters
		
		for (LangSection langSection : page.getAllLangSections()) {
			Map<String, String> params = langSection.getTemplateParams();
			String alt = params.getOrDefault("alt", "");
			
			if (alt.isEmpty()) {
				continue;
			}
			
			if (!StringUtils.containsAny(alt, '{', '}', '[', ']', '(', ')')) {
				extractAltParameter(langSection, alt);
			}
		}
		
		for (Section section : page.filterSections(s -> s.getHeader().startsWith("ETYM "))) {
			String header = section.getHeader();
			String alt = header.replaceFirst(".+alt-(.*)", "$1");
			
			if (alt.isEmpty()) {
				continue;
			}
			
			if (!StringUtils.containsAny(alt, '{', '}', '[', ']', '(', ')')) {
				extractAltParameter(section, alt);
			} else {
				insertAltComment(section, alt);
			}
		}
		
		// Add or process pre-transform etymology sections
		
		Predicate<Section> pred = s -> s instanceof LangSection || s.getHeader().startsWith("ETYM ");
		
		for (Section section : page.filterSections(pred)) {
			List<Section> etymologySections = section.findSubSectionsWithHeader("[Ee]timolog[íi]a.*");
			
			// Reconstructed words
			
			if (
				etymologySections.isEmpty() &&
				section instanceof LangSection &&
				RECONSTRUCTED_LANGS.contains(((LangSection) section).getLangCode()) &&
				ParseUtils.getTemplates("etimología", section.getIntro()).isEmpty() &&
				ParseUtils.getTemplates("etimología2", section.getIntro()).isEmpty()
			) {
				continue;
			}
			
			if (
				section.getIntro().isEmpty() &&
				section.getChildSections() == null
			) {
				if (!(section instanceof LangSection)) {
					section.detachOnlySelf();
				} else {
					continue;
				}
			} else if (
				section instanceof LangSection &&
				((LangSection) section).hasSubSectionWithHeader(HAS_FLEXIVE_FORM_HEADER_RE)
			) {
				continue;
			} else if (
				etymologySections.isEmpty() ||
				hasAdditionalEtymSections(section.nextSection(), etymologySections)
			) {
				Section nextSibling = section.nextSiblingSection();
				Section etymologySection = Section.create("Etimología", 3);
				etymologySection.setTrailingNewlines(1);
				
				if (
					etymologySections.isEmpty() &&
					section instanceof LangSection &&
					(
						nextSibling == null || 
						!nextSibling.getHeader().startsWith("ETYM ")
					)
				) {
					processIfSingleEtym(section, etymologySection);
				} else {
					processIfMultipleEtym(section, etymologySection);
				}
				
				if (
					etymologySection.getIntro().isEmpty() &&
					// see addMissingElements()
					(
						!ParseUtils.getTemplates("etimología", section.getIntro()).isEmpty() ||
						!ParseUtils.getTemplates("etimología2", section.getIntro()).isEmpty()
					)
				) {
					HashMap<String, String> params = new LinkedHashMap<>();
					params.put("templateName", "etimología");
					
					// this should always be true
					if (section instanceof LangSection) {
						String langCode = ((LangSection) section).getLangCode();
						
						if (!langCode.equals("es")) {
							params.put("leng", langCode);
						}
					}
					
					String template = ParseUtils.templateFromMap(params);
					etymologySection.setIntro(template + ".");
				}
				
				section.prependSections(etymologySection);
				
				if (!(section instanceof LangSection)) {
					section.detachOnlySelf();
				}
			} else if (
				section instanceof LangSection &&
				!etymologySections.get(0).getHeader().matches("[Ee]timolog[íi]a( 1)?")
			) {
				String templateName = "estructura";
				
				if (ParseUtils.getTemplates(templateName, text).isEmpty()) {
					insertStructureTemplate(templateName, page);
					String log = String.format("{{%s}}", templateName);
					checkDifferences(page.toString(), "transformToNewStructure", log);
				}
				
				return;
			} else {
				if (section instanceof LangSection) {
					continue;
				}
				
				Section etymologySection = etymologySections.get(0);
				etymologySection.setLevel(3);
				processIfMultipleEtym(section, etymologySection);
				section.detachOnlySelf();
			}
		}
		
		// Detach empty LangSections
		
		page.getAllLangSections().stream()
			.filter(ls -> ls.getIntro().isEmpty() && ls.getChildSections() == null)
			.forEach(AbstractSection::detachOnlySelf);
		
		page.normalizeChildLevels();
		
		// Check section levels and header numbers
		
		for (LangSection langSection : page.getAllLangSections()) {
			List<Section> etymologySections = langSection.findSubSectionsWithHeader("[Ee]timolog[íi]a.*");
			
			if (etymologySections.isEmpty()) {
				continue;
			}
			
			if (etymologySections.size() == 1) {
				Section etymologySection = etymologySections.get(0);
				List<Section> etymologyChildren = etymologySection.getChildSections();
				
				if (etymologyChildren == null) {
					continue;
				}
				
				for (Section child : etymologyChildren) {
					child.pushLevels(-1);
				}
				
				etymologySection.setHeader("Etimología");
			} else {
				for (Section sibling : langSection.getChildSections()) {
					if (!etymologySections.contains(sibling)) {
						sibling.pushLevels(1);
					}
				}
				
				for (int i = 1; i <= etymologySections.size(); i++) {
					Section etymologySection = etymologySections.get(i - 1);
					String header = String.format("Etimología %d", i);
					etymologySection.setHeader(header);
				}
			}
		}
		
		formatted = page.toString();
		isOldStructure = false;
		
		checkDifferences(formatted, "transformToNewStructure", "conversión a la nueva estructura");
	}

	private static boolean hasAdditionalEtymSections(Section nextSection, List<Section> etymologySections) {
		if (etymologySections.isEmpty() || nextSection == null) {
			return false;
		}
		
		Section etymologySection = etymologySections.get(0);
		
		return (
			etymologySection != nextSection &&
			!etymologySection.getStrippedHeader().matches("[Ee]timolog[íi]a( 1)?") &&
			!nextSection.getHeader().matches("^[Pp]ronunciaci[óo]n.*")
		);
	}

	private static String replaceOldStructureTemplates(String title, String text) {
		StringBuilder currentSectionLang = new StringBuilder(15);
		
		return Utils.replaceWithStandardIgnoredRanges(text, P_OLD_STRUCT_HEADER,
			m -> m.start(2),
			(m, sb) -> {
				String pre = m.group(1);
				String template = m.group(2);
				String post = m.group(3);
				
				HashMap<String, String> params = ParseUtils.getTemplateParametersWithValue(template);
				String name = params.get("templateName");
				
				if (name.equals("lengua") || name.equals("translit")) {
					m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
					currentSectionLang.replace(0, currentSectionLang.length(),
						params.get("ParamWithoutName1").toLowerCase()
					);
					return;
				}
				
				String altGraf = params.getOrDefault("ParamWithoutName1", "");
				
				if (name.equals("TRANSLIT")) {
					name = params.get("ParamWithoutName2");
					params.put("templateName", "translit");
				} else {
					name = name.replace("-ES", "").toLowerCase();
					params.put("templateName", "lengua");
					params.put("ParamWithoutName1", name);
					params.remove("ParamWithoutName2");
					params.remove("num");
					params.remove("núm");
				}
				
				if (
					!altGraf.isEmpty() && !altGraf.equals("{{PAGENAME}}") &&
					!altGraf.replace("ʼ", "'").equals(title.replace("ʼ", "'"))
				) {
					params.put("alt", altGraf);
				} else {
					altGraf = "";
				}
				
				pre = (pre.isEmpty() || pre.matches("[:;*#]+")) ? "" : "$1\n";
				post = (post.isEmpty() || post.matches("<!--.+?-->")) ? "" : "\n$3";
				
				if (currentSectionLang.toString().equals(name)) {
					String replacement = String.format("%s=ETYM alt-%s=%s", pre, altGraf, post);
					m.appendReplacement(sb, replacement);
				} else {
					sortTemplateParamsMap(params);
					String newTemplate = ParseUtils.templateFromMap(params);
					String replacement = String.format("%s=%s=%s", pre, newTemplate, post);
					m.appendReplacement(sb, replacement);
				}
				
				currentSectionLang.replace(0, currentSectionLang.length(), name);
			}
		);
	}
	
	private static void sortTemplateParamsMap(Map<String, String> params) {
		Map<String, String> tempMap = new LinkedHashMap<>(params.size(), 1);
		tempMap.put("templateName", params.remove("templateName"));
		tempMap.put("ParamWithoutName1", params.remove("ParamWithoutName1"));
		tempMap.putAll(params);
		params.clear();
		params.putAll(tempMap);
	}

	private static void extractAltParameter(Section section, String alt) {
		if (
			section instanceof LangSection &&
			RECONSTRUCTED_LANGS.contains(((LangSection) section).getLangCode())
		) {
			if (ParseUtils.removeCommentsAndNoWikiText(alt).isEmpty()) {
				Map<String, String> params = ((LangSection) section).getTemplateParams();
				params.remove("alt");
				((LangSection) section).setTemplateParams(params);
			}
			
			return;
		}
		
		String intro = section.getIntro();
		List<String> pronLaTmpls = ParseUtils.getTemplates("pron.la", intro);
		
		if (pronLaTmpls.size() == 1) {
			String pronLaTmpl = pronLaTmpls.get(0);
			HashMap<String, String> pronLaParams = ParseUtils.getTemplateParametersWithValue(pronLaTmpl);
			
			if (!pronLaParams.containsKey("alt")) {
				pronLaParams.put("alt", alt);
				intro = intro.replace(pronLaTmpl, ParseUtils.templateFromMap(pronLaParams));
				section.setIntro(intro);
			}
		} else if (
			ParseUtils.getTemplates("diacrítico", intro).isEmpty() &&
			!intro.contains("Diacrítico:")
		) {
			HashMap<String, String> params = new LinkedHashMap<>();
			params.put("templateName", "diacrítico");
			params.put("ParamWithoutName1", alt);
			String template = ParseUtils.templateFromMap(params);
			section.setIntro(intro + "\n" + template + ".");
		} else {
			insertAltComment(section, alt);
		}
		
		if (section instanceof LangSection) {
			Map<String, String> params = ((LangSection) section).getTemplateParams();
			params.remove("alt");
			((LangSection) section).setTemplateParams(params);
		}
	}

	private static void insertAltComment(Section section, String alt) {
		String comment = String.format("<!-- NO EDITAR: alt=%s -->", alt);
		section.setIntro(comment + "\n" + section.getIntro());
	}

	private static void insertStructureTemplate(final String templateName, Page page) {
		String pageIntro = page.getIntro();
		pageIntro += String.format("\n{{%s}}", templateName);
		page.setIntro(pageIntro);
	}

	private static void processIfSingleEtym(Section topSection, Section etymologySection) {
		// Move etymology template to the etymology section
		
		List<String> temp = new ArrayList<>();
		
		String topIntro = Utils.replaceWithStandardIgnoredRanges(topSection.getIntro(), P_ETYM_TMPL,
			(m, sb) -> {
				String line = m.group(1);
				String trailingText = m.group(2);
				
				if (!trailingText.isEmpty() && !analyzeEtymLine(trailingText)) {
					return;
				}
				
				temp.add(line);
				m.appendReplacement(sb, "");
			}
		);
		
		if (!temp.isEmpty()) {
			topSection.setIntro(topIntro);
			String etymologyIntro = etymologySection.getIntro();
			etymologyIntro += "\n" + String.join("\n\n", temp);
			etymologySection.setIntro(etymologyIntro);
		}
	}

	private static void processIfMultipleEtym(Section topSection, Section etymologySection) {
		String etymologyIntro = etymologySection.getIntro();
		etymologyIntro = topSection.getIntro() + "\n" + etymologyIntro;
		etymologySection.setIntro(etymologyIntro);
		
		if (topSection instanceof LangSection) {
			topSection.setIntro("");
			topSection.setTrailingNewlines(1);
		}
		
		if (etymologyIntro.split("\n").length < 2) {
			return;
		}
		
		// Search for the etymology template and move it to the last line
		
		List<String> temp = new ArrayList<>();
		
		etymologyIntro = Utils.replaceWithStandardIgnoredRanges(etymologyIntro, P_ETYM_TMPL,
			(m, sb) -> {
				String line = m.group(1);
				String trailingText = m.group(2);
				
				if (!trailingText.isEmpty() && !analyzeEtymLine(trailingText)) {
					return;
				}
				
				temp.add(line);
				m.appendReplacement(sb, "");
			}
		).trim();
		
		if (!temp.isEmpty()) {
			etymologyIntro += "\n" + String.join("\n\n", temp);
			etymologySection.setIntro(etymologyIntro);
		}
	}
	
	private static boolean analyzeEtymLine(String line) {
		// TODO: review
		
		final String[] arr1 = {"{{", "{|", "[", "<ref"};
		final String[] arr2 = {"}}", "|}", "]", "</ref"};
		
		BiFunction<String, String[], Boolean> biFunc = (text, delimiters) -> {
			String open = delimiters[0];
			String close = delimiters[1];
			int start = text.lastIndexOf(open);
			
			return start == -1 || text.substring(start).indexOf(close) != -1;
		};
		
		if (!biFunc.apply(line, new String[]{"<!--", "-->"})) {
			return false;
		}
		
		line = ParseUtils.removeCommentsAndNoWikiText(line);
		line = line.replaceAll("<ref [^>]+?(?<=/ ?)>", "");
		
		for (int i = 0; i < arr1.length; i++) {
			String[] delimiters = {arr1[i], arr2[i]};
			
			if (!biFunc.apply(line, delimiters)) {
				return false;
			}
		}
		
		return true;
	}
	
	public void normalizeSectionHeaders() {
		Page page = Page.store(title, text);
		
		for (Section section : page.getAllSections()) {
			if (
				section instanceof LangSection ||
				!section.getHeader().equals(section.getStrippedHeader())
			) {
				continue;
			}
			
			String header = section.getHeader();
			
			header = StringUtils.strip(header, "=").trim();
			header = header.replaceFirst("(?i)^Etimolog[íi]a", "Etimología");
			// TODO: don't confuse with {{locución}}, {{refrán}}
			header = header.replaceFirst("(?i)^Locuciones", "Locuciones");
			header = header.replaceFirst("(?i)^(?:Refranes|Dichos?)", "Refranes");
			header = header.replaceFirst("(?i)^Conjugaci[óo]n\\b", "Conjugación");
			header = header.replaceFirst("(?i)^Informaci[óo]n (?:adicional|avanzada)", "Información adicional");
			header = header.replaceFirst("(?i)^(?:Ver|V[ée]ase) tambi[ée]n", "Véase también");
			header = header.replaceFirst("(?i)^Proverbio\\b", "Refrán");
			
			header = header.replaceFirst("(?i)^Forma (?:de )?sub?stantiv[oa]$", "Forma sustantiva");
			header = header.replaceFirst("(?i)^Forma (?:de )?verb(?:o|al)$", "Forma verbal");
			header = header.replaceFirst("(?i)^Forma (?:de )?adjetiv[oa]$", "Forma adjetiva");
			header = header.replaceFirst("(?i)^Forma (?:de )?(?:pronombre|pronominal)$", "Forma pronominal");
			header = header.replaceFirst("(?i)^Forma (?:de )?(?:preposición|prepositiv[oa])$", "Forma prepositiva");
			header = header.replaceFirst("(?i)^Forma (?:de )?adverbi(?:o|al)$", "Forma adverbial");
			
			header = header.replaceFirst("(?i)^Forma (?:de )?sub?stantiv[oa] (masculin|femenin|neutr)[oa]$", "Forma sustantiva $1a");
			
			// TODO: https://es.wiktionary.org/w/index.php?title=klei&oldid=2727290
			LangSection langSection = section.getLangSectionParent();
			
			if (langSection != null) {
				if (langSection.getLangName().equals("español")) {
					header = header.replaceFirst("(?i)^Traducci[óo]n(?:es)?$", "Traducciones");
				} else {
					header = header.replaceFirst("(?i)^Traducci[óo]n(?:es)?$", "Traducción");
				}
			}
			
			if (!isOldStructure) {
				header = header.replaceFirst("(?i)^(?:<small *?> *?)?Referencias?\\b.*$", "Referencias y notas");
			}
			
			section.setHeader(header);
		}
		
		String formatted = page.toString();
		checkDifferences(formatted, "normalizeSectionHeaders", "normalizando títulos de encabezamiento");
	}
	
	public void substituteReferencesTemplate() {
		if (isOldStructure) {
			return;
		}
		
		Page page = Page.store(title, text);
		final String templateName = "título referencias";
		List<String> contents = new ArrayList<>();
		boolean found = false;
		
		List<Section> sections = page.getAllSections();
		ListIterator<Section> iterator = sections.listIterator(sections.size());
		
		while (iterator.hasPrevious()) {
			Section section = iterator.previous();
			String intro = section.getIntro();
			Pattern patt = Pattern.compile("\\{\\{ *?" + templateName + " *?\\}\\}");
			Matcher m = patt.matcher(intro);
			StringBuffer sb = new StringBuffer(intro.length());
			List<Range<Integer>> ignoredRanges = Utils.getStandardIgnoredRanges(intro);
			
			while (m.find()) {
				if (Utils.containedInRanges(ignoredRanges, m.start())) {
					continue;
				}
				
				found = true;
				String content = intro.substring(m.end()).trim();
				
				if (!content.isEmpty()) {
					contents.add(content);
				}
				
				m.appendReplacement(sb, "");
				section.setIntro(sb.toString());
				break;
			}
		}
		
		if (!found) {
			return;
		}
		
		Collections.reverse(contents);
		Section references = page.getReferencesSection();
		
		if (references == null) {
			references = Section.create("Referencias y notas", 2);
			contents.add("<references />");
			references.setIntro(String.join("\n", contents));
			page.setReferencesSection(references);
		} else {
			String intro = references.getIntro();
			intro = intro.replaceAll("<references *?/ *?>", "").trim();
			contents.addAll(Arrays.asList(intro.split("\n")));
			contents.add("<references />");
			references.setIntro(String.join("\n", contents));
		}
		
		String formatted = page.toString();
		checkDifferences(formatted, "substituteReferencesTemplate", "sustituyendo {{título referencias}}");
	}

	public void duplicateReferencesSection() {
		Page page = Page.store(title, text);
		List<Section> allReferences = page.findSectionsWithHeader("^[Rr]eferencias.*");
		
		if (isOldStructure || allReferences.size() < 2) {
			return;
		}
		
		Iterator<Section> iterator = allReferences.iterator();
		
		while (iterator.hasNext()) {
			Section section = iterator.next();
			String content = section.getIntro();
			
			content = ParseUtils.removeCommentsAndNoWikiText(content); 
			content = content.replaceAll("<references *?/ *?>", "");
			// TODO: review transclusions of {{listaref}}
			//content = content.replaceAll("\\{\\{ *?listaref *?\\}\\}", "");
			content = content.trim();
			
			// TODO: combine non-empty sections?
			if (content.isEmpty()) {
				section.detachOnlySelf();
				iterator.remove();
			}
		}
		
		if (allReferences.isEmpty()) {
			Section references = Section.create("Referencias y notas", 2);
			references.setIntro("<references />");
			page.setReferencesSection(references);
		}
		
		String formatted = page.toString();
		checkDifferences(formatted, "duplicateReferencesSection", "más de una sección de referencias");
	}
	
	public void moveReferencesSection() {
		Page page = Page.store(title, text);
		List<Section> allReferences = page.findSectionsWithHeader("^[Rr]eferencias.*");
		
		if (isOldStructure || allReferences.size() != 1) {
			return;
		}
		
		Section references = allReferences.get(0);
		
		if (references == page.getReferencesSection()) {
			return;
		}
		
		references.detachOnlySelf();
		page.setReferencesSection(references);
		
		String formatted = page.toString();
		checkDifferences(formatted, "moveReferencesSection", "trasladando sección de referencias");
	}
	
	public void normalizeEtymologyHeaders() {
		if (isOldStructure) {
			return;
		}
		
		Page page = Page.store(title, text);
		
		for (LangSection langSection : page.getAllLangSections()) {
			List<Section> etymologySections = langSection.findSubSectionsWithHeader("Etimología.*");
			
			if (etymologySections.isEmpty()) {
				continue;
			} else if (etymologySections.size() == 1) {
				Section etymologySection = etymologySections.get(0);
				etymologySection.setHeader("Etimología");
			} else {
				for (int i = 0; i < etymologySections.size(); i++) {
					Section etymologySection = etymologySections.get(i);
					String header = String.format("Etimología %d", i + 1);
					etymologySection.setHeader(header);
				}
			}
		}
		
		String formatted = page.toString();
		checkDifferences(formatted, "normalizeEtymologyHeaders", "normalizando encabezamientos de etimología");
	}
	
	public void normalizeSectionLevels() {
		// TODO: handle single- to multiple-etymology sections edits and vice versa
		// TODO: satura, aplomo
		
		Page page = Page.store(title, text);
		
		if (isOldStructure || page.getAllLangSections().isEmpty()) {
			return;
		}
		
		page.normalizeChildLevels();
		
		// TODO: traverse siblings of the first Section?
		// TODO: don't push levels unless it will result in a LangSection child
		for (Section section : page.getAllSections()) {
			if (
				section.getTocLevel() != 1 ||
				section instanceof LangSection ||
				section.getHeader().startsWith("Referencias")
			) {
				continue;
			}
			
			try {
				section.pushLevels(1);
			} catch (IllegalArgumentException e) {}
		}
		
		// TODO: reparse Page
		page = Page.store(title, page.toString());
		Section references = page.getReferencesSection();
		
		if (references != null && references.getLevel() != 2) {
			try {
				references.pushLevels(2 - references.getLevel());
			} catch (IllegalArgumentException e) {}
		}
		
		for (Section etymologySection : page.findSectionsWithHeader("^Etimología.*")) {
			int level = etymologySection.getLevel();
			
			if (level > 3) {
				etymologySection.pushLevels(3 - level);
			}
		}
		
		page.normalizeChildLevels();
		
		for (LangSection langSection : page.getAllLangSections()) {
			if (langSection.hasSubSectionWithHeader(HAS_FLEXIVE_FORM_HEADER_RE)) {
				continue;
			}
			
			List<Section> etymologySections = langSection.findSubSectionsWithHeader("^Etimología.*");
			
			if (etymologySections.isEmpty()) {
				continue;
			}
			
			if (etymologySections.size() == 1) {
				List<Section> etymologyChildren = etymologySections.get(0).getChildSections();
				
				if (etymologyChildren != null) {
					for (Section child : etymologyChildren) {
						child.pushLevels(-1);
					}
				}
				
				pushStandardSections(langSection.getChildSections(), 3);
			} else {
				for (Section sibling : langSection.getChildSections()) {
					if (etymologySections.contains(sibling)) {
						continue;
					}
					
					sibling.pushLevels(1);
				}
				
				for (Section etymologySection : etymologySections) {
					pushStandardSections(etymologySection.getChildSections(), 4);
				}
			}
		}
		
		String formatted = page.toString();
		checkDifferences(formatted, "normalizeSectionLevels", "normalizando niveles de títulos");
	}

	private static void pushStandardSections(List<Section> sections, int level) {
		// TODO: get rid of those null checks, find a better way
		if (sections == null) {
			return;
		}
		
		AbstractSection.flattenSubSections(sections).stream()
			.filter(s -> Section.BOTTOM_SECTIONS.contains(s.getStrippedHeader()))
			.filter(s -> s.getLevel() > level)
			.forEach(s -> s.pushLevels(level - s.getLevel()));
	}

	public void removePronGrafSection() {
		Page page = Page.store(title, text);
		List<Section> sections = page.findSectionsWithHeader("[Pp]ronunciaci[óo]n( y escritura)?");
		
		if (isOldStructure || sections.isEmpty()) {
			return;
		}
		
		for (Section section : sections) {
			Section parentSection = section.getParentSection();
			
			// TODO: pull up child sections or place before normalizeSectionLevels()
			// https://es.wiktionary.org/w/index.php?title=ni_fu_ni_fa&oldid=2899086
			
			if (parentSection == null || section.getChildSections() != null) {
				continue;
			}
			
			String selfIntro = section.getIntro();
			String parentIntro = parentSection.getIntro();
			
			if (
				!(parentSection instanceof LangSection) &&
				!parentSection.getHeader().matches("^Etimología.*")
			) {
				// TODO: ¿?
				continue;
			}
			
			String[] lines = String.join("\n", Arrays.asList(selfIntro, parentIntro)).split("\n");
			
			String newIntro = Stream.of(lines)
				.sorted((line1, line2) -> -Boolean.compare(
					line1.matches(P_AMBOX_TMPLS.toString()),
					line2.matches(P_AMBOX_TMPLS.toString())
				))
				.collect(Collectors.joining("\n"));
			
			parentSection.setIntro(newIntro);
			section.detachOnlySelf();
		}
		
		String formatted = page.toString();
		checkDifferences(formatted, "removePronGrafSection", "eliminando título de pronunciación");
	}

	public void sortLangSections() {
		if (isOldStructure) {
			return;
		}
		
		Page page = Page.store(title, text);
		page.sortSections();
		String formatted = page.toString();
		checkDifferences(formatted, "sortLangSections", "ordenando secciones de idioma");
	}
	
	public void addMissingSections() {
		Page page = Page.store(title, text);
		
		if (isOldStructure || page.getAllSections().isEmpty()) {
			return;
		}
		
		Set<String> set = new LinkedHashSet<>();
		
		// Etymology
		
		for (LangSection langSection : page.getAllLangSections()) {
			if (
				// TODO: discuss with the community
				title.contains(" ") ||
				langSection.getChildSections() == null ||
				!langSection.findSubSectionsWithHeader("Etimología.*").isEmpty() ||
				langSection.hasSubSectionWithHeader(HAS_FLEXIVE_FORM_HEADER_RE) ||
				RECONSTRUCTED_LANGS.contains(langSection.getLangCode())
			) {
				continue;
			}
			
			// TODO: review, catch special cases
			Set<String> headers = langSection.getChildSections().stream()
				.map(AbstractSection::getStrippedHeader)
				.collect(Collectors.toSet());
			
			headers.removeAll(STANDARD_HEADERS);
			
			if (headers.isEmpty()) {
				continue;
			}
			
			Section etymologySection = Section.create("Etimología", 3);
			etymologySection.setTrailingNewlines(1);
			
			HashMap<String, String> params = new LinkedHashMap<>();
			params.put("templateName", "etimología");
			
			if (!langSection.langCodeEqualsTo("es")) {
				params.put("leng", langSection.getLangCode());
			}
			
			String template = ParseUtils.templateFromMap(params);
			etymologySection.setIntro(template + ".");
			langSection.prependSections(etymologySection);
			set.add("Etimología");
		}
		
		// Translations
		
		LangSection spanishSection = page.getLangSection("es");
		
		if (
			spanishSection != null &&
			// TODO: discuss with the community
			ParseUtils.getTemplates("apellido", spanishSection.toString()).isEmpty()
		) {
			List<Section> etymologySections = spanishSection.findSubSectionsWithHeader("Etimología.*");
			
			if (etymologySections.size() == 1) {
				if (
					etymologySections.get(0).getLevel() == 3 &&
					spanishSection.findSubSectionsWithHeader("Traducciones").isEmpty()
				) {
					Section translationsSection = Section.create("Traducciones", 3);
					translationsSection.setIntro(TRANSLATIONS_TEMPLATE);
					translationsSection.setTrailingNewlines(1);
					spanishSection.appendSections(translationsSection);
					set.add("Traducciones");
				}
			} else if (etymologySections.size() > 1) {
				for (Section etymologySection : etymologySections) {
					if (
						etymologySection.getLevel() == 3 &&
						etymologySection.findSubSectionsWithHeader("Traducciones").isEmpty()
					) {
						Section translationsSection = Section.create("Traducciones", 4);
						translationsSection.setIntro(TRANSLATIONS_TEMPLATE);
						translationsSection.setTrailingNewlines(1);
						etymologySection.appendSections(translationsSection);
						set.add("Traducciones");
					}
				}
			}
		}
		
		// References
		
		if (page.findSectionsWithHeader("Referencias y notas").isEmpty()) {
			Section references = Section.create("Referencias y notas", 2);
			references.setIntro("<references />");
			page.setReferencesSection(references);
			set.add("Referencias y notas");
		}
		
		if (set.isEmpty()) {
			return;
		}
		
		String formatted = page.toString();
		String summary = (set.size() == 1)
			? "añadiendo sección: "
			: "añadiendo secciones: ";
		summary += String.join(", ", set);
		
		checkDifferences(formatted, "addMissingSections", summary);
	}
	
	public void moveReferencesElements() {
		Page page = Page.store(title, text);
		Section references = page.getReferencesSection();
		List<Section> referencesSections = page.findSectionsWithHeader("Referencias y notas");
		
		if (
			isOldStructure || references == null ||
			referencesSections.size() > 1
		) {
			return;
		}
		
		Page tempPage = Page.store(title, text);
		tempPage.getReferencesSection().detachOnlySelf();
		String str = tempPage.toString();
		final Pattern pReferenceTags = Pattern.compile("<references *?/? *?>");
		
		if (
			ParseUtils.getTemplates("listaref", str).isEmpty() &&
			str.equals(pReferenceTags.matcher(str).replaceAll(""))
		) {
			return;
		}
		
		Set<String> set = new HashSet<>();
		
		for (Section section : page.getAllSections()) {
			if (section == references) {
				continue;
			}
			
			String intro = section.getIntro();
			boolean isModified = false;
			
			String temp = Utils.replaceTemplates(intro, "listaref", match -> "");
			
			if (!temp.equals(intro)) {
				intro = temp;
				isModified = true;
				set.add("{{listaref}}");
			}
			
			// TODO: handle reference groups
			temp = Utils.replaceWithStandardIgnoredRanges(intro, pReferenceTags,
				(m, sb) -> m.appendReplacement(sb, "\n\n")
			);
			
			
			if (!temp.equals(intro)) {
				intro = temp;
				isModified = true;
				set.add("<references>");
			}
			
			if (isModified) {
				section.setIntro(intro);
			}
		}
		
		if (set.isEmpty()) {
			return;
		}
		
		String summary = null;
		String referencesIntro = references.getIntro();
		
		if (
			!ParseUtils
				.getTemplates("listaref", referencesIntro).isEmpty() ||
			pReferenceTags
				.matcher(ParseUtils.removeCommentsAndNoWikiText(referencesIntro)).find()
		) {
			summary = String.format("eliminando %s", String.join(", ", set));
		} else {
			String added = set.contains("<references>") ? "<references />" : "{{listaref}}";
			referencesIntro += "\n" + added;
			references.setIntro(referencesIntro);
			summary = String.format("trasladando %s", String.join(", ", set));
		}
		
		String formatted = page.toString();
		checkDifferences(formatted, "moveReferencesElements", summary);
	}
	
	public void sortSubSections() {
		if (isOldStructure) {
			return;
		}
		
		Page page = Page.store(title, text);
		
		for (LangSection langSection : page.getAllLangSections()) {
			if (!langSection.hasSubSectionWithHeader(HAS_FLEXIVE_FORM_HEADER_RE)) {
				langSection.sortSections();
			}
		}
		
		String formatted = page.toString();
		checkDifferences(formatted, "sortSubSections", "ordenando subsecciones");
	}
	
	public void removeInflectionTemplates() {
		if (isOldStructure || !text.contains("{{inflect.")) {
			return;
		}
		
		Page page = Page.store(title, text);
		List<Section> flexiveFormSections = page.findSectionsWithHeader(HAS_FLEXIVE_FORM_HEADER_RE);
		
		for (Section section : flexiveFormSections) {
			String intro = section.getIntro();
			
			if (
				!intro.contains("{{inflect.") ||
				!ParseUtils.getTemplates("participio", intro).isEmpty()
			) {
				continue;
			}
			
			intro = Utils.replaceWithStandardIgnoredRanges(intro, "\\{\\{inflect\\..+?\\}\\}", "");
			section.setIntro(intro);
		}
		
		String formatted = page.toString();
		checkDifferences(formatted, "removeInflectionTemplates", "eliminando plantillas de flexión");
	}
	
	public void manageAnnotationTemplates() {
		final String annotationTemplateName = "anotación";
		
		if (isOldStructure || ParseUtils.getTemplates(annotationTemplateName, text).isEmpty()) {
			return;
		}
		
		// Remove empty templates
		
		String formatted = Utils.replaceTemplates(text, annotationTemplateName, match -> {
			Map<String, String> params = ParseUtils.getTemplateParametersWithValue(match);
			
			long count = params.values().stream()
				.filter(value -> value != null && !value.isEmpty())
				.count();
			
			return count > 1 ? match : "";
		});
		
		if (formatted.equals(text)) {
			return;
		}
		
		String summary = String.format("{{%s}} → eliminando plantillas vacías", annotationTemplateName);
		checkDifferences(formatted, "manageAnnotationTemplates", summary);
	}
	
	public void manageDisambigTemplates() {
		final String disambigTemplateName = "desambiguación";
		
		if (
			isOldStructure ||
			ParseUtils.getTemplates(disambigTemplateName, text).isEmpty() ||
			!Page.store(title, text).hasSectionWithHeader(HAS_FLEXIVE_FORM_HEADER_RE)
		) {
			return;
		}
		
		// Remove empty templates
		
		String formatted = Utils.replaceTemplates(text, disambigTemplateName, match -> {
			Map<String, String> params = ParseUtils.getTemplateParametersWithValue(match);
			
			long count = params.values().stream()
				.filter(value -> value != null && !value.isEmpty())
				.count();
			
			return count > 1 ? match : "";
		});
		
		if (formatted.equals(text)) {
			return;
		}
		
		String summary = String.format("{{%s}} → eliminando plantillas vacías", disambigTemplateName);
		checkDifferences(formatted, "manageDisambiguationTemplates", summary);
	}
	
	public void adaptPronunciationTemplates() {
		if (isOldStructure) {
			return;
		}
		
		Page page = Page.store(title, text);
		Set<String> modified = new LinkedHashSet<>();
		
		for (Section section : page.getAllSections()) {
			if (
				!(section instanceof LangSection) &&
				!section.getStrippedHeader().matches("Etimología \\d+")
			) {
				continue;
			}
			
			LangSection langSection = section.getLangSectionParent();
			String content = section.getIntro();
			content = Utils.replaceWithStandardIgnoredRanges(content, "\n{2,}", "\n");
			
			if (
				langSection == null || content.isEmpty() ||
				// TODO: review
				!ParseUtils.getTemplates("pron-graf", content).isEmpty()
			) {
				continue;
			}
			
			Map<String, Map<String, String>> tempMap = new HashMap<>();
			List<String> editedLines = new ArrayList<>();
			List<String> amboxTemplates = new ArrayList<>();
			List<Range<Integer>> ignoredRanges = Utils.getStandardIgnoredRanges(content);
			MutableInt index = new MutableInt(0);
			
			linesLoop:
			for (String line : content.split("\n")) {
				if (Utils.containedInRanges(ignoredRanges, index.intValue())) {
					index.add(line.length() + 1);
					editedLines.add(line);
					continue;
				}
				
				index.add(line.length() + 1);
				
				if (
					line.contains("{{etimología") ||
					P_IMAGES.matcher(line).matches() ||
					P_COMMENTS.matcher(line).matches()
				) {
					editedLines.add(line);
					continue;
				}
				
				if (P_AMBOX_TMPLS.matcher(line).matches()) {
					amboxTemplates.add(line);
					continue;
				}
				
				Matcher m = P_TMPL_LINE.matcher(line);
				String templateFromText = null;
				String origLine = line;
				
				if (m.matches()) {
					// FIXME
					if (langSection.hasSubSectionWithHeader(HAS_FLEXIVE_FORM_HEADER_RE)) {
						throw new UnsupportedOperationException("adaptPronunciationTemplates()");
					}
					
					line = makeTmplLine(m, PRON_TMPLS, PRON_TMPLS_ALIAS);
					
					if (line == null) {
						editedLines.add(origLine);
						continue;
					} else {
						templateFromText = m.group(2).trim();
					}
				}
				
				line = line.replaceFirst(
					"\\{\\{[Pp]ronunciación(?:\\|leng=[^\\|]*?)?\\|(.+?)\\}\\} [oó] \\{\\{AFI\\|(.+?)\\}\\}\\.?",
					"{{pronunciación|$1 o $2}}"
				);
				m = P_ADAPT_PRON_TMPL.matcher(line);
				
				if (!m.matches()) {
					editedLines.add(origLine);
					continue;
				}
				
				String templateName = m.group(1).toLowerCase();
				
				if (tempMap.containsKey(templateName)) {
					editedLines.add(origLine);
					continue;
				}
				
				List<String> templates = ParseUtils.getTemplates(templateName, line);
				
				if (templates.isEmpty() || templates.size() > 1) {
					editedLines.add(origLine);
					continue;
				}
				
				Map<String, String> params = ParseUtils.getTemplateParametersWithValue(templates.get(0));
				Map<String, String> newParams = new LinkedHashMap<>();
				
				switch (templateName) {
					case "pronunciación":
						if (
							params.containsKey("ParamWithoutName2") &&
							!params.get("ParamWithoutName2").contains("AFI")
						) {
							editedLines.add(origLine);
							continue linesLoop;
						}
						
						String param1 = params.get("ParamWithoutName1");
						
						if (param1 != null) {
							if (
								param1.isEmpty() || param1.equals("-") || param1.equals("[]") ||
								param1.equals("//") || param1.equals("...") ||
								param1.equals("[ ˈ ]") || param1.equals("[ˈ]") ||
								param1.equals("&nbsp;") ||
								SPANISH_PRON_TMPL_PARAMS.stream()
									.anyMatch(param -> !params.getOrDefault(param, "").isEmpty())
							) {
								param1 = null;
							}
						}
						
						if (param1 == null) {
							if (!langSection.langCodeEqualsTo("es")) {
								break;
							}
							
							int count = 1;
							
							for (String type : params.keySet()) {
								if (!SPANISH_PRON_TMPL_PARAMS.contains(type)) {
									continue;
								}
								
								String num = (count != 1) ? Integer.toString(count) : "";
								String pron = "", altPron = "";
								
								if (Arrays.asList("s", "c").contains(type)) {
									pron = "seseo";
									altPron = type.equals("s") ? "Seseante" : "No seseante";
								} else if (Arrays.asList("ll", "y").contains(type)) {
									pron = "yeísmo";
									altPron = type.equals("y") ? "Yeísta" : "No yeísta";
								} else {
									pron = "variaciones fonéticas";
									
									switch (type) {
										case "ys":
											altPron = "Yeísta, seseante";
											break;
										case "yc":
											altPron = "Yeísta, no seseante";
											break;
										case "lls":
											altPron = "No yeísta, seseante";
											break;
										case "llc":
											altPron = "No yeísta, no seseante";
											break;
									}
								}
								
								String ipa = params.get(type).replace("'", "ˈ");
								newParams.put(num + "pron", pron);
								newParams.put("alt" + num + "pron", altPron);
								newParams.put(num + "fone", ipa);
								
								count++;
							}
						} else {
							if (
								StringUtils.containsAny(param1, '{', '}', '<', '>') ||
								(
									StringUtils.containsAny(param1, '(', ')') &&
									// only allow single characters inside parens
									Pattern.compile("\\([^\\)]{2,}\\)").matcher(param1).find()
								)
							) {
								editedLines.add(origLine);
								continue linesLoop;
							}
							
							param1 = param1.replace("'", "ˈ");
							param1 = param1.replaceFirst(" *?, *$", "");
							
							if (param1.startsWith("[") && !param1.endsWith("]")) {
								param1 += "]";
							} else if (!param1.startsWith("[") && param1.endsWith("]")) {
								param1 = "[" + param1;
							} else if (param1.startsWith("/") && !param1.endsWith("/")) {
								param1 += "/";
							} else if (!param1.startsWith("/") && param1.endsWith("/")) {
								param1 = "/" + param1;
							}
							
							if (param1.matches("\\[.+\\]")) {
								param1 = param1.substring(1, param1.length() - 1).trim();
								String[] alts = param1.split("\\] *?[o,] *?\\[");
								
								if (alts.length > 1) {
									for (int i = 1; i <= alts.length; i++) {
										String param = alts[i - 1].trim();
										String num = (i != 1) ? Integer.toString(i) : "";
										newParams.put("fone" + num, param);
									}
								} else if (StringUtils.containsAny(param1, '[', ']', '/')) {
									editedLines.add(origLine);
									continue linesLoop;
								} else {
									newParams.put("fone", param1);
								}
							} else if (param1.matches("/.+/")) {
								param1 = param1.substring(1, param1.length() - 1).trim();
								String[] alts = param1.split("/ *?[o,] *?/");
								
								if (alts.length > 1) {
									for (int i = 1; i <= alts.length; i++) {
										String param = alts[i - 1].trim();
										String num = (i != 1) ? Integer.toString(i) : "";
										newParams.put("fono" + num, param);
									}
								} else if (StringUtils.containsAny(param1, '[', ']', '/')) {
									editedLines.add(origLine);
									continue linesLoop;
								} else {
									newParams.put("fono", param1);
								}
							} else if (!StringUtils.containsAny(param1, '[', ']', '/')) {
								newParams.put("fone", param1);
							} else {
								editedLines.add(origLine);
								continue linesLoop;
							}
						}
						
						break;
					case "pron.la":
						String par1 = params.get("ParamWithoutName1");
						String par2 = params.get("ParamWithoutName2");
						String par3 = params.get("alt");
						
						if (par3 != null && !par3.isEmpty()) {
							newParams.put("alt", par3);
						}
						
						newParams.put("pron", "latín clásico");
						
						if (par1 != null && !par1.isEmpty()) {
							newParams.put("fone", par1);
							
							if (par2 != null && !par2.isEmpty()) {
								newParams.put("fone2", par2);
							}
						}
						
						break;
					case "audio":
						if (params.getOrDefault("ParamWithoutName1", "").isEmpty()) {
							break;	
						} else if (
							!params.getOrDefault("nb", "").isEmpty() ||
							(
								params.containsKey("ParamWithoutName2") &&
								!params.get("ParamWithoutName2").isEmpty() &&
								!(
									StringUtils.strip(params.get("ParamWithoutName2"), " ':").matches("(?i)audio") ||
									params.get("ParamWithoutName2").equalsIgnoreCase(title)
								)
							)
						) {
							editedLines.add(origLine);
							continue linesLoop;
						} else {
							newParams.put("audio", params.get("ParamWithoutName1"));
							break;
						}
					case "transliteración":
						makePronGrafParams(params, newParams, "tl");
						break;
					case "homófono":
						makePronGrafParams(params, newParams, "h");
						break;
					case "grafía alternativa":
						makePronGrafParams(params, newParams, "g");
						break;
					case "variantes":
						makePronGrafParams(params, newParams, "v");
						break;
					case "parónimo":
						makePronGrafParams(params, newParams, "p");
						break;
					case "diacrítico":
						param1 = params.get("ParamWithoutName1");
						
						if (StringUtils.containsAny(param1, '[', ']', '{', '}', '(', ')')) {
							editedLines.add(origLine);
							continue linesLoop;
						}
						
						newParams.put("ParamWithoutName1", param1);
						break;
					default:
						editedLines.add(origLine);
						continue linesLoop;
				}
				
				tempMap.put(templateName, newParams);
				
				if (templateName.equals("diacrítico")) {
					continue;
				} else if (templateFromText != null) {
					modified.add(templateFromText);
				} else {
					modified.add(String.format("{{%s}}", templateName));
				}
				
				String trailingComments = m.group(2).trim();
				
				if (!trailingComments.isEmpty()) {
					editedLines.add(trailingComments);
				}
			}
			
			if (tempMap.isEmpty() || (
				tempMap.containsKey("pronunciación") && tempMap.containsKey("pron.la")
			)) {
				continue;
			}
			
			HashMap<String, String> newMap = new LinkedHashMap<>();
			newMap.put("templateName", "pron-graf");
			
			if (!langSection.langCodeEqualsTo("es")) {
				newMap.put("leng", langSection.getLangCode());
			}
			
			Map<String, String> langTemplateParams = langSection.getTemplateParams();
			String altParam = langTemplateParams.get("alt");
			
			if (altParam != null && !StringUtils.containsAny(altParam, '{', '}', '[', ']', '(', ')')) {
				langTemplateParams.remove("alt");
				newMap.put("alt", altParam);
				langSection.setTemplateParams(langTemplateParams);
			} else if (altParam == null && tempMap.containsKey("diacrítico")) {
				Map<String, String> altGrafParams = tempMap.remove("diacrítico");
				newMap.put("alt", altGrafParams.get("ParamWithoutName1"));
			}
			
			if (tempMap.containsKey("pronunciación")) {
				newMap.putAll(tempMap.remove("pronunciación"));
			}
			
			for (Entry<String, Map<String, String>> entry : tempMap.entrySet()) {
				newMap.putAll(entry.getValue());
			}
			
			editedLines.add(0, ParseUtils.templateFromMap(newMap));
			
			if (!amboxTemplates.isEmpty()) {
				editedLines.addAll(0, amboxTemplates);
			}
			
			section.setIntro(String.join("\n", editedLines));
		}
		
		String formatted = page.toString();
		
		String summary = !modified.isEmpty()
			? String.join(", ", modified) + " → {{pron-graf}}"
			: "conversión a {{pron-graf}}";
		
		checkDifferences(formatted, "adaptPronunciationTemplates", summary);
	}
	
	private static void makePronGrafParams(Map<String, String> sourceMap, Map<String, String> targetMap, String prefix) {
		for (int i = 1; i < 21; i++) {
			String num = (i == 1) ? "" : Integer.toString(i);
			targetMap.put(prefix + num, sourceMap.get("ParamWithoutName" + i));
			
			if (i == 1) {
				targetMap.put(prefix + "alt", sourceMap.getOrDefault("alt", sourceMap.get("alt1")));
				targetMap.put(prefix + "num", sourceMap.getOrDefault("num", sourceMap.getOrDefault("núm", sourceMap.getOrDefault("núm1", sourceMap.get("num1")))));
				targetMap.put(prefix + "tr", sourceMap.getOrDefault("tr", sourceMap.get("tr1")));
				targetMap.put(prefix + "nota", sourceMap.getOrDefault("nota", sourceMap.get("nota1")));
			} else {
				targetMap.put(prefix + "alt" + i, sourceMap.get("alt" + i));
				targetMap.put(prefix + "num" + i, sourceMap.getOrDefault("núm" + i, sourceMap.get("num" + i)));
				targetMap.put(prefix + "tr" + i, sourceMap.get("tr" + i));
				targetMap.put(prefix + "nota" + i, sourceMap.get("nota" + i));
			}
		}
		
		Iterator<Entry<String, String>> iterator = targetMap.entrySet().iterator();
		
		while (iterator.hasNext()) {
			Entry<String, String> entry = iterator.next();
			
			if (entry.getValue() == null || entry.getValue().isEmpty()) {
				iterator.remove();
			}
		}
	}
	
	public void convertToTemplate() {
		// TODO: add leng parameter
		// TODO: <sub>/<sup> -> {{subíndice}}/{{superíndice}}
		
		Set<String> modified = new HashSet<>();
		
		String formatted = Utils.replaceWithStandardIgnoredRanges(text, P_TMPL_LINE,
			m -> m.start(2),
			(m, sb) -> {
				String line = Optional
					.ofNullable(makeTmplLine(m, TERM_TMPLS, TERM_TMPLS_ALIAS))
					.orElse(makeTmplLine(m, PRON_TMPLS, PRON_TMPLS_ALIAS));
				
				if (line == null) {
					return;
				}
				
				line = Utils.replaceWithStandardIgnoredRanges(
					line,
					Pattern.quote("{{derivado|"),
					"{{derivad|"
				);
				
				m.appendReplacement(sb, Matcher.quoteReplacement(line));
				modified.add(m.group(2).trim());
			}
		);
		
		String summary = "conversión a plantilla: " + String.join(", ", modified);
		checkDifferences(formatted, "convertToTemplate", summary);
	}

	private static String makeTmplLine(Matcher m, List<String> templates, List<String> aliases) {
		String leadingComments = m.group(1).trim();
		String name = m.group(2).trim().toLowerCase();
		String content = m.group(3).trim();
		String trailingComments = m.group(4).trim();
		
		if (name.isEmpty() || content.isEmpty()) {
			return null;
		}
		
		name = StringUtils.strip(name, " ':");
		
		if (aliases.contains(name)) {
			name = templates.get(aliases.indexOf(name));
		}
		
		// TODO: review
		if (name.equals("ortografía alternativa")) {
			name = "grafía alternativa";
		}
		
		if (!templates.contains(name)) {
			return null;
		}
		
		// TODO: allow plain-text content (no link/template)
		if (
			!StringUtils.containsAny(content, '[', ']', '{', '}') ||
			StringUtils.containsAny(content, '<', '>')
		) {
			return null;
		}
		
		// http://stackoverflow.com/a/2787064
		Matcher msep = P_LIST_ARGS.matcher(content);
		List<String> lterms = new ArrayList<>();
		
		while (msep.find()) {
			lterms.add(msep.group().trim());
		}
		
		HashMap<String, String> map = new LinkedHashMap<>(lterms.size(), 1);
		map.put("templateName", name);
		
		for (int i = 1; i <= lterms.size(); i++) {
			String term = lterms.get(i - 1);
			String param = "ParamWithoutName" + i;
			
			if (StringUtils.containsAny(term, '[', ']')) {
				Matcher m2 = P_LINK.matcher(term);
				
				if (!m2.matches()) {
					return null;
				}
				
				map.put(param, m2.group(1));
				String trail = m2.group(3);
				
				if (!trail.isEmpty() && StringUtils.containsAny(trail, '(', ')')) {
					Matcher m3 = P_PARENS.matcher(trail);
					
					if (!m3.matches() || StringUtils.containsAny(m3.group(1), '[', ']')) {
						return null;
					} else {
						trail = m3.group(1).trim();
						map.put("nota" + i, m3.group(2));
					}
				} else if (StringUtils.containsAny(trail, '[', ']')) {
					return null;
				}
				
				if (!trail.isEmpty() || (m2.group(2) != null && !m2.group(2).equals(m2.group(1)))) {
					map.put("alt" + i, (m2.group(2) != null ? m2.group(2) : m2.group(1)) + trail);
				}
			} else if (StringUtils.containsAny(term, '{', '}')) {
				Matcher m2 = P_LINK_TMPLS.matcher(term);
				
				if (!m2.matches()) {
					return null;
				}
				
				String template = m2.group(1);
				String trail = m2.group(2);
				
				HashMap<String, String> params = ParseUtils.getTemplateParametersWithValue(template);
				
				if (!params.getOrDefault("templateName", "").matches("l\\+?")) {
					return null;
				}
				
				if (params.containsKey("glosa")) {
					return null;
				}
				
				map.put(param, params.get("ParamWithoutName2"));
				
				if (params.containsKey("ParamWithoutName3")) {
					map.put("alt" + i, params.get("ParamWithoutName3"));
				}
				
				if (params.containsKey("num") || params.containsKey("núm")) {
					map.put("num" + i, params.getOrDefault("num", params.get("núm")));
				}
				
				if (params.containsKey("tr")) {
					map.put("tr" + i, params.get("tr"));
				}
				
				if (trail != null && !trail.isEmpty()) {
					map.put("nota" + i, trail.trim());
				}
			} else {
				return null;
			}
		}
		
		leadingComments = leadingComments.replaceAll(" *?(<!--.*?-->) *", "$1");
		
		return leadingComments +
			(!leadingComments.isEmpty() ? "\n" : "") +
			ParseUtils.templateFromMap(map) + "." +
			trailingComments;
	}
	
	public void addMissingElements() {
		if (isOldStructure) {
			return;
		}
		
		Page page = Page.store(title, text);
		Set<String> set = new LinkedHashSet<>();
		
		// {{etimología}} and {{pron-graf}}
		
		for (LangSection langSection : page.getAllLangSections()) {
			if (RECONSTRUCTED_LANGS.contains(langSection.getLangCode())) {
				continue;
			}
			
			List<Section> etymologySections = langSection.findSubSectionsWithHeader("Etimología.*");
			String langCode = langSection.getLangCode();
			
			// FIXME
			if (
				etymologySections.isEmpty() &&
				langSection.hasSubSectionWithHeader(HAS_FLEXIVE_FORM_HEADER_RE) &&
				ParseUtils.getTemplates("pron-graf", langSection.getIntro()).isEmpty()
			) {
				String langSectionIntro = langSection.getIntro();
				
				if (ParseUtils.getTemplates("pron-graf", langSectionIntro).isEmpty()) {
					langSectionIntro = insertTemplate(langSectionIntro, langCode, "pron-graf", "{{%s}}");
					langSection.setIntro(langSectionIntro);
					set.add("{{pron-graf}}");
				}
			} else if (etymologySections.size() == 1) {
				Section etymologySection = etymologySections.get(0);
				String langSectionIntro = langSection.getIntro();
				String etymologyIntro = etymologySection.getIntro();
				
				if (
					ParseUtils.getTemplates("pron-graf", langSectionIntro).isEmpty() &&
					ParseUtils.getTemplates("pron-graf", etymologyIntro).isEmpty()
				) {
					langSectionIntro = insertTemplate(langSectionIntro, langCode, "pron-graf", "{{%s}}");
					langSection.setIntro(langSectionIntro);
					set.add("{{pron-graf}}");
				}
				
				if (
					ParseUtils.getTemplates("etimología", langSection.getIntro()).isEmpty() &&
					ParseUtils.getTemplates("etimología2", langSection.getIntro()).isEmpty() &&
					// TODO: review
					(etymologyIntro.isEmpty() || removeBlockTemplates(etymologyIntro).isEmpty())
				) {
					etymologyIntro = insertTemplate(etymologyIntro, langCode, "etimología", "{{%s}}.");
					etymologySection.setIntro(etymologyIntro);
					set.add("{{etimología}}");
				}
			} else if (etymologySections.size() > 1) {
				for (Section etymologySection : etymologySections) {
					String etymologyIntro = etymologySection.getIntro();
					
					if (
						ParseUtils.getTemplates("etimología", langSection.getIntro()).isEmpty() &&
						ParseUtils.getTemplates("etimología2", langSection.getIntro()).isEmpty() &&
						(etymologyIntro.isEmpty() || removeBlockTemplates(etymologyIntro).isEmpty())
					) {
						// TODO: ensure that it's inserted after {{pron-graf}}
						etymologyIntro = insertTemplate(etymologyIntro, langCode, "etimología", "{{%s}}.");
						etymologySection.setIntro(etymologyIntro);
						set.add("{{etimología}}");
					}
					
					if (
						ParseUtils.getTemplates("pron-graf", langSection.getIntro()).isEmpty() &&
						ParseUtils.getTemplates("pron-graf", etymologyIntro).isEmpty()
					) {
						etymologyIntro = insertTemplate(etymologyIntro, langCode, "pron-graf", "{{%s}}");
						etymologySection.setIntro(etymologyIntro);
						set.add("{{pron-graf}}");
					}
				}
			}
		}
		
		Section spanishSection = page.getLangSection("es");
		
		// Translations
		
		if (spanishSection != null) {
			List<Section> translations = spanishSection.findSubSectionsWithHeader("Traducciones");
			
			if (translations.size() == 1) {
				Section section = translations.get(0);
				String intro = section.getIntro();
				
				if (
					ParseUtils.getTemplates("trad-arriba", intro).isEmpty() &&
					ParseUtils.getTemplates("trad", intro).isEmpty() &&
					ParseUtils.getTemplates("t+", intro).isEmpty() &&
					ParseUtils.getTemplates("véase", intro).isEmpty() &&
					!intro.matches("(?i).*?\\b(v[ée]an?se|ver)\\b.*")
				) {
					if (intro.matches("(\\[\\[(?i:category|categoría):.+?\\]\\]\\s*)+")) {
						intro = TRANSLATIONS_TEMPLATE + "\n\n" + intro;
					} else {
						intro += "\n\n" + TRANSLATIONS_TEMPLATE;
					}
					
					section.setIntro(intro);
					set.add("tabla de traducciones");
				}
			}
		}
		
		Section references = page.getReferencesSection();
		
		// <references />
		
		// TODO: check other elements (templates, manually introduced references...)
		if (references != null && references.getIntro().isEmpty()) {
			references.setIntro("<references />");
			set.add("<references>");
		}
		
		// {{reconstruido}}
		
		final String reconstructedTmpl = "reconstruido";
		
		page.getAllLangSections().stream()
			.filter(langSection -> RECONSTRUCTED_LANGS.contains(langSection.getLangCode()))
			// https://es.wiktionary.org/w/index.php?title=cot&diff=3383057&oldid=3252255
			.filter(langSection -> !langSection.getLangName().equals("chono"))
			.forEach(langSection -> {
				String content = langSection.toString();
				content = ParseUtils.removeCommentsAndNoWikiText(content);
				
				if (!ParseUtils.getTemplates(reconstructedTmpl, content).isEmpty()) {
					return;
				}
				
				String intro = langSection.getIntro();
				intro = String.format("{{%s}}", reconstructedTmpl) + "\n" + intro;
				langSection.setIntro(intro);
				set.add("{{reconstruido}}");
			});
		
		if (set.isEmpty()) {
			return;
		}
		
		String formatted = page.toString();
		String summary = String.format("añadiendo %s", String.join(", ", set));
		
		checkDifferences(formatted, "addMissingElements", summary);
	}
	
	private static String insertTemplate(String content, String langCode, String templateName, String templateFormat) {
		String[] lines = content.split("\n");
		List<String> amboxTemplates = new ArrayList<>();
		List<String> otherLines = new ArrayList<>();
		
		for (String line : lines) {
			if (P_AMBOX_TMPLS.matcher(line).matches()) {
				amboxTemplates.add(line);
			} else {
				otherLines.add(line);
			}
		}
		
		List<String> outputList = new ArrayList<>(amboxTemplates);
		String lengParam = "";
		
		if (!langCode.equals("es")) {
			lengParam = String.format("|leng=%s", langCode);
		}
		
		// TODO: ugly workaround, just insert {{etimología}} at the end
		
		if (templateName.equals("etimología")) {
			outputList.addAll(otherLines);
			outputList.add(String.format(templateFormat, templateName + lengParam));
		} else {
			outputList.add(String.format(templateFormat, templateName + lengParam));
			outputList.addAll(otherLines);
		}
		
		outputList.removeIf(String::isEmpty);
		return String.join("\n", outputList);
	}
	
	private static String removeBlockTemplates(String text) {
		text = ParseUtils.removeCommentsAndNoWikiText(text);
		
		List<String> list = new ArrayList<>(PRON_TMPLS.size() + AMBOX_TMPLS.size() + 1);
		list.addAll(PRON_TMPLS);
		list.addAll(AMBOX_TMPLS);
		list.add("pron-graf");
		
		for (String target : list) {
			for (String template : ParseUtils.getTemplates(target, text)) {
				text = text.replace(template, "");
			}
		}
		
		text = P_IMAGES.matcher(text).replaceAll("");
		text = P_CATEGORY_LINKS.matcher(text).replaceAll("");
		text = text.replaceAll("<ref\\b.*?(?:/ *?>|>.*?</ref *?>)", "");
		text = text.replaceAll("(?m)^[\\s.,:;*#]*$", "");
		
		return text.trim();
	}
	
	public void removeCategoryLinks() {
		//String formatted = text;
		//checkDifferences(formatted, "removeCategoryLinks", "eliminando categorías redundantes");
	}
	
	public void checkLangHeaderCodeCase() {
		if (isOldStructure) {
			return;
		}
		
		Page page = Page.store(title, text);
		
		for (LangSection langSection : page.getAllLangSections()) {
			String langCode = langSection.getLangCode(false);
			
			if (!langCode.equals(langSection.getLangCode(true))) {
				langSection.setLangCode(langCode.toLowerCase());
			}
		}
		
		checkDifferences(page.toString(), "checkLangHeaderCodeCase", null);
	}
	
	public void langTemplateParams() {
		// TODO: lang code as unnamed parameter ({{sustantivo}})
		// TODO: {{Matemáticas}}, {{mamíferos}}, etc.
		
		if (isOldStructure) {
			return;
		}
		
		Page page = Page.store(title, text);
		
		for (LangSection langSection : page.getAllLangSections()) {
			String content = langSection.toString();
			MutableBoolean sectionModified = new MutableBoolean(false);
			
			for (String template : LENG_PARAM_TMPLS) {
				Pattern patt = Pattern.compile(
					"\\{\\{ *?" + template + " *?(\\|(?:\\{\\{.+?\\}\\}|.*?)+)?\\}\\}",
					Pattern.DOTALL
				);
				
				MutableBoolean templateModified = new MutableBoolean(false);
				
				String temp = Utils.replaceWithStandardIgnoredRanges(content, patt,
					(m, sb) -> {
						HashMap<String, String> params = ParseUtils.getTemplateParametersWithValue(m.group());
						String leng = params.get("leng");
						boolean occurrenceModified = false;
						
						if (template.equals("ampliable")) {
							String param1 = params.remove("ParamWithoutName1");
							leng = Optional.ofNullable(leng).orElse(param1);
						}
						
						if (langSection.langCodeEqualsTo("es")) {
							// TODO: is this necessary?
							if (leng != null) {
								params.remove("leng");
								occurrenceModified = true;
							}
						} else if (leng == null) {
							@SuppressWarnings("unchecked")
							Map<String, String> tempMap = (Map<String, String>) params.clone();
							params.clear();
							params.put("templateName", tempMap.remove("templateName"));
							params.put("leng", langSection.getLangCode());
							params.putAll(tempMap);
							occurrenceModified = true;
						} else if (!langSection.langCodeEqualsTo(leng)) {
							params.put("leng", langSection.getLangCode());
							occurrenceModified = true;
						}
						
						if (occurrenceModified) {
							String newTemplate = ParseUtils.templateFromMap(params);
							newTemplate = Matcher.quoteReplacement(newTemplate);
							m.appendReplacement(sb, newTemplate);
							templateModified.setTrue();
						}
					}
				);
				
				if (templateModified.booleanValue()) {
					content = temp;
					sectionModified.setTrue();
				}
			}
			
			if (sectionModified.booleanValue()) {
				LangSection newLangSection = LangSection.parse(content);
				langSection.replaceWith(newLangSection);
			}
		}
		
		String formatted = page.toString();
		checkDifferences(formatted, "langTemplateParams", "códigos de idioma");
	}
	
	public void deleteEmptySections() {
		Page page = Page.store(title, text);
		
		if (page.getAllLangSections().isEmpty()) {
			return;
		}
		
		Set<String> set = new HashSet<>();
		
		for (Section section : page.getAllSections()) {
			String header = section.getStrippedHeader();
			List<Section> childSections = section.getChildSections();
			
			if (
				header.startsWith("Etimología") || // TODO: review
				(childSections != null && !childSections.isEmpty()) ||
				!STANDARD_HEADERS.contains(header)
			) {
				continue;
			}
			
			String intro = section.getIntro();
			intro = ParseUtils.removeCommentsAndNoWikiText(intro);
			intro = intro.replaceAll("<br.*?>", "");
			intro = intro.replace("{{clear}}", "");
			intro = P_CATEGORY_LINKS.matcher(intro).replaceAll("");
			intro = intro.trim();
			
			if (!intro.isEmpty()) {
				continue;
			}
			
			if (!section.getIntro().isEmpty()) {
				Section previousSection = section.previousSection();
				
				if (previousSection == null) {
					continue;
				}
				
				String previousIntro = previousSection.getIntro();
				previousIntro += "\n" + section.getIntro();
				previousSection.setIntro(previousIntro);
			}
			
			section.detachOnlySelf();
			set.add(header);
		}
		
		if (set.isEmpty()) {
			return;
		}
		
		String formatted = page.toString();
		String summary = (set.size() == 1)
			? "eliminando sección vacía: "
			: "eliminando secciones vacías: ";
		summary += String.join(", ", set);
		
		checkDifferences(formatted, "deleteEmptySections", summary);
	}
	
	public void deleteWrongSections() {
		// TODO: move elements (category links, {{clear}}, maybe images too?) to the preceding section
		
		Page page = Page.store(title, text);
		List<LangSection> langSections = page.getAllLangSections();
		
		if (isOldStructure || langSections.isEmpty()) {
			return;
		}
		
		Set<String> set = new HashSet<>();
		
		for (LangSection langSection : langSections) {
			List<Section> childSections = langSection.getChildSections();
			
			if (childSections == null) {
				continue;
			}
			
			long flex = childSections.stream()
				.map(AbstractSection::getStrippedHeader)
				.filter(header -> header.matches(HAS_FLEXIVE_FORM_HEADER_RE))
				.count();
			
			long nonFlex = childSections.stream()
				.map(AbstractSection::getStrippedHeader)
				.filter(header -> !STANDARD_HEADERS.contains(header))
				.filter(header -> !header.matches(HAS_FLEXIVE_FORM_HEADER_RE))
				.count();
			
			if (langSection.langCodeEqualsTo("es") && (nonFlex != 0 || flex == 0)) {
				continue;
			}
			
			// delete empty translations Section in non-Spanish LangSections or flexive forms
			
			langSection.findSubSectionsWithHeader("Traducciones").forEach(section -> {
				String intro = section.getIntro();
				intro = ParseUtils.removeCommentsAndNoWikiText(intro);
				intro = intro.replaceAll("\\{\\{trad-(arriba|centro|abajo)\\}\\}", "");
				intro = intro.trim();
				
				if (intro.isEmpty() && section.getChildSections() == null) {
					section.detachOnlySelf();
					set.add(section.getStrippedHeader());
				}
			});
			
			// delete empty etymology Section in flexive forms
			
			if (nonFlex != 0 || flex == 0) {
				continue;
			}
			
			langSection.findSubSectionsWithHeader("Etimología").forEach(section -> {
				String intro = section.getIntro();
				intro = intro.replaceAll("\\{\\{etimología2?(\\|leng=[\\w-]+?)?\\}\\}\\.?", "");
				intro = intro.trim();
				
				if (intro.isEmpty() && section.getChildSections() == null) {
					section.detachOnlySelf();
					set.add(section.getStrippedHeader());
				}
			});
		}
		
		if (set.isEmpty()) {
			return;
		}
		
		String formatted = page.toString();
		String summary = (set.size() == 1)
			? "eliminando sección: "
			: "eliminando secciones: ";
		summary += String.join(", ", set);
		
		checkDifferences(formatted, "deleteWrongSections", summary);
	}
	
	public void manageClearElements() {
		if (isOldStructure || Page.store(title, text).getAllLangSections().isEmpty()) {
			return;
		}
		
		String initial = removeBrTags(text);
		Page page = Page.store(title, initial);
		
		// TODO: sanitize templates to avoid inner spaces like in "{{ arriba..."
		final String[] arr = {"{{arriba", "{{trad-arriba", "{{rel-arriba", "{{derivados"};
		
		for (Section section : page.getAllSections()) {
			Section nextSection = section.nextSection();
			
			if (nextSection == null) {
				break;
			}
			
			String sectionIntro = section.getIntro();
			String sanitizedNextSectionIntro = ParseUtils.removeCommentsAndNoWikiText(nextSection.getIntro());
			
			if (
				StringUtils.startsWithAny(sanitizedNextSectionIntro, arr) ||
				(
					nextSection.getStrippedHeader().matches("Etimología \\d+") &&
					!nextSection.getStrippedHeader().equals("Etimología 1")
				)
			) {
				List<String> templates = ParseUtils.getTemplates("clear", sectionIntro);
				String sanitizedSectionIntro = ParseUtils.removeCommentsAndNoWikiText(sectionIntro);
				
				if (templates.size() == 1 && sanitizedSectionIntro.endsWith("{{clear}}")) {
					continue;
				}
				
				if (!templates.isEmpty()) {
					sectionIntro = removeClearTemplates(section);
				}
				
				sectionIntro += "\n\n{{clear}}";
				section.setIntro(sectionIntro);
			} else if (sectionIntro.contains("{{clear}}")) {
				removeClearTemplates(section);
			}
		}
		
		String formatted = page.toString();
		checkDifferences(formatted, "manageClearElements", "elementos \"clear\"");
	}
	
	private static String removeBrTags(String text) {
		return Utils.replaceWithStandardIgnoredRanges(text, P_BR_TAGS,
			(m, sb) -> {
				String pre = m.group(1);
				String content = m.group(2);
				String post = m.group(3);
				
				if (
					!P_BR_CLEAR.matcher(content).find() &&
					!P_BR_STYLE.matcher(content).find()
				) {
					return;
				}
				
				StringBuilder buff = new StringBuilder(pre.length() + post.length());
				
				if (pre.trim().isEmpty() && post.trim().isEmpty()) {
					if (!pre.isEmpty()) {
						buff.append('\n');
						
						if (!post.isEmpty()) {
							buff.append('\n');
						}
					}
					
					m.appendReplacement(sb, Matcher.quoteReplacement(buff.toString()));
				} else {
					post = post.replaceFirst("^ *", "");
					
					if (!pre.trim().isEmpty() && !post.trim().isEmpty()) {
						buff.append(pre);
						buff.append('\n').append('\n');
						buff.append(post);
					} else {
						buff.append(pre);
						buff.append(post);
					}
					
					m.appendReplacement(sb, Matcher.quoteReplacement(buff.toString()));
				}
			}
		);
	}
	
	private static String removeClearTemplates(Section section) {
		String intro = Utils.replaceWithStandardIgnoredRanges(section.getIntro(), P_CLEAR_TMPLS,
			(m, sb) -> m.appendReplacement(sb, "\n\n")
		);
		
		section.setIntro(intro);
		return intro;
	}
	
	public void applyUcfTemplates() {
		Page page = Page.store(title, text);
		
		if (page.getAllLangSections().isEmpty()) {
			return;
		}
		
		Section references = page.getReferencesSection();
		
		List<Section> sections = page.filterSections(section ->
			section != references &&
			!(section instanceof LangSection) &&
			!STANDARD_HEADERS.contains(section)
		);
		
		MutableInt count = new MutableInt(0);
				
		for (Section section : sections) {
			String intro = section.getIntro();
			
			String temp = Utils.replaceWithStandardIgnoredRanges(intro, P_UCF,
				(m, sb) -> {
					String target = m.group(2);
					String pipe = m.group(3);
					String trail = m.group(4);
					
					if (target.substring(0, 1).equals(target.substring(0, 1).toUpperCase())) {
						return;
					}
					
					if (pipe != null && (
						pipe.isEmpty() ||
						!(pipe.substring(0, 1).toLowerCase() + pipe.substring(1)).equals(target)
					)) {
						return;
					}
					
					if (trail.matches("^[\\wáéíóúüñÁÉÍÓÚÜÑ]+.*")) {
						return;
					}
					
					String template = String.format("{{plm|%s}}", target);
					String replacement = intro.substring(m.start(), m.start(1)) + template + trail;
					
					m.appendReplacement(sb, replacement);
					count.increment();
				}
			);
			
			if (!temp.equals(intro)) {
				section.setIntro(temp);
			}
		}
		
		if (count.intValue() == 0) {
			return;
		}
		
		String formatted = page.toString();
		String summary = (count.intValue() == 1)
			? "convirtiendo enlace a {{plm}}"
			: "convirtiendo enlaces a {{plm}}";
		
		checkDifferences(formatted, "applyUcfTemplates", summary);
	}
	
	public void strongWhitespaces() {
		// TODO: don't collide with removeComments() 
		String initial = text;
		
		// &nbsp; replacements
		
		initial = Utils.replaceWithStandardIgnoredRanges(initial, "( |&nbsp;)*\n", "\n");
		initial = Utils.replaceWithStandardIgnoredRanges(initial, " &nbsp;", " ");
		initial = Utils.replaceWithStandardIgnoredRanges(initial, "&nbsp; ", " ");
		
		Page page = Page.store(title, initial);
		
		// leading and trailing newlines
		
		if (page.getLeadingNewlines() > 1) {
			page.setLeadingNewlines(0);
		}
		
		if (page.getTrailingNewlines() > 1) {
			page.setTrailingNewlines(1);
		}
		
		String pageIntro = page.getIntro();
		String strippedPageIntro = ParseUtils.removeCommentsAndNoWikiText(pageIntro);
		
		if (
			page.getTrailingNewlines() == 1 &&
			!page.getIntro().isEmpty() &&
			(
				strippedPageIntro.isEmpty() ||
				strippedPageIntro.matches("(?s).*\\{\\{[Dd]esambiguación\\|*?\\}\\}$")
			)
		) {
			page.setTrailingNewlines(0);
		}
		
		for (Section section : page.getAllSections()) {
			if (section.getLeadingNewlines() > 1) {
				section.setLeadingNewlines(1);
			}
			
			if (section.getTrailingNewlines() > 1) {
				section.setTrailingNewlines(1);
			}
		}
		
		// term whitespaces (;1 {{foo}}: bar)
		
		Section references = page.getReferencesSection();
		
		page.filterSections(section ->
			section != references &&
			!(section instanceof LangSection) &&
			!STANDARD_HEADERS.contains(section.getStrippedHeader())
		).forEach(section -> {
			String intro = section.getIntro();
			
			String temp = Utils.replaceWithStandardIgnoredRanges(intro, P_TERM,
				(m, sb) -> {
					String template = m.group(2);
					
					if (template == null || template.startsWith(" ")) {
						return;
					}
					
					String replacement =
						intro.substring(m.start(), m.start(2)) +
						" " + template +
						intro.substring(m.end(2), m.end());
					
					m.appendReplacement(sb, replacement);
				}
			);
			
			if (!temp.equals(intro)) {
				section.setIntro(temp);
			}
		});
		
		String formatted = page.toString();
		
		// miscellaneous
		
		formatted = Utils.replaceWithStandardIgnoredRanges(formatted, "\n{3,}", "\n\n");
		formatted = Utils.replaceWithStandardIgnoredRanges(formatted, "\n\n<!--", "\n<!--");
		// TODO: trim whitespaces inside <ref>
		formatted = Utils.replaceWithStandardIgnoredRanges(formatted, "(\\.|\\]\\]|\\}\\}|\\)) <ref(>| )", "$1<ref$2");
		
		checkDifferences(formatted, "strongWhitespaces", "espacios en blanco");
	}

	public void weakWhitespaces() {
		Page page = Page.store(title, text);
		
		if (page.getLeadingNewlines() == 1) {
			page.setLeadingNewlines(0);
		}
		
		String pageIntro = page.getIntro();
		
		// leading and trailing newlines (+ Section's headerFormat)
		
		if (pageIntro.isEmpty() && page.getTrailingNewlines() == 1) {
			page.setTrailingNewlines(0);
		}
		
		String strippedPageIntro = ParseUtils.removeCommentsAndNoWikiText(pageIntro);
		
		if (
			!pageIntro.isEmpty() && page.getTrailingNewlines() == 0 &&
			!strippedPageIntro.isEmpty() &&
			!strippedPageIntro.matches("(?s).*\\{\\{[Dd]esambiguación\\|*?\\}\\}$")
		) {
			page.setTrailingNewlines(1);
		}
		
		for (Section section : page.getAllSections()) {
			String sectionIntro = section.getIntro();
			
			if (
				(
					!sectionIntro.isEmpty() &&
					!sectionIntro.endsWith("<br clear=\"all\">")
				) ||
				(
					sectionIntro.isEmpty() &&
					section.getTrailingNewlines() == 0
				)
			) {
				section.setTrailingNewlines(1);
			}
			
			if (!section.getHeader().isEmpty()) {
				section.setHeaderFormat("%1$s %2$s %1$s");
			} else {
				section.setHeaderFormat("%1$s %1$s");
			}
			
			if (
				section instanceof LangSection &&
				!sectionIntro.isEmpty() &&
				section.getLeadingNewlines() == 1
			) {
				section.setLeadingNewlines(0);
			}
		}
		
		// term whitespaces (;1 {{foo}}: bar)
		
		Section references = page.getReferencesSection();
		
		page.filterSections(section ->
			section != references &&
			!(section instanceof LangSection) &&
			!STANDARD_HEADERS.contains(section.getStrippedHeader())
		).forEach(section -> {
			String intro = section.getIntro();
			
			String temp = Utils.replaceWithStandardIgnoredRanges(intro, P_TERM,
				(m, sb) -> {
					String number = m.group(1);
					String colon = m.group(3);
					String definition = m.group(4);
					
					if (!number.startsWith(" ") && !colon.startsWith(" ") && definition.startsWith(" ")) {
						return;
					}
					
					String replacement =
						intro.substring(m.start(), m.start(1)) +
						number.trim() +
						intro.substring(m.end(1), m.start(3)) +
						colon.trim() + " " + definition.trim();
					
					m.appendReplacement(sb, replacement);
				}
			);
			
			if (!temp.equals(intro)) {
				section.setIntro(temp);
			}
		});
		
		String formatted = page.toString();
		
		// miscellaneous
		
		formatted = Utils.replaceWithStandardIgnoredRanges(formatted, "<references *?/ *?>", "<references />");
		formatted = Utils.replaceWithStandardIgnoredRanges(formatted, " </ref>", "</ref>");
		formatted = Utils.replaceWithStandardIgnoredRanges(formatted, "([^\n])\n{0,1}\\{\\{clear\\}\\}", "$1\n\n{{clear}}");
		formatted = Utils.replaceWithStandardIgnoredRanges(formatted, "\n *?\\}\\}", "\n}}");
		
		checkDifferences(formatted, "weakWhitespaces", null);
	}

	public static void main(String[] args) throws FileNotFoundException, IOException, LoginException {
		ESWikt wb = Login.retrieveSession(Domains.ESWIKT, Users.User2);
		
		String text = null;
		String title = "alcahuetería";
		//String title = "mole"; TODO
		//String title = "אביב"; // TODO: delete old section template
		//String title = "das"; // TODO: attempt to fix broken headers (missing "=")
		
		text = wb.getPageText(title);
		//text = String.join("\n", IOUtils.loadFromFile("./data/eswikt.txt", "", "UTF8"));
		
		Page page = Page.store(title, text);
		AbstractEditor editor = new Editor(page);
		editor.check();
		
		wb.edit(title, editor.getPageText(), editor.getSummary(), false, true, -2, null);
		System.out.println(editor.getLogs());
		
		wb.logout();
	}
}
