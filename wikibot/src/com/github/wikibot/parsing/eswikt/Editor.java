package com.github.wikibot.parsing.eswikt;

import static org.apache.commons.lang3.StringUtils.capitalize;
import static org.apache.commons.lang3.StringUtils.containsAny;
import static org.apache.commons.lang3.StringUtils.countMatches;
import static org.apache.commons.lang3.StringUtils.repeat;
import static org.apache.commons.lang3.StringUtils.startsWithAny;
import static org.apache.commons.lang3.StringUtils.strip;
import static org.wikiutils.ParseUtils.getTemplateParametersWithValue;
import static org.wikiutils.ParseUtils.getTemplates;
import static org.wikiutils.ParseUtils.removeCommentsAndNoWikiText;
import static org.wikiutils.ParseUtils.templateFromMap;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.LoginException;

import org.apache.commons.lang3.Range;
import org.apache.commons.lang3.mutable.MutableBoolean;
import org.apache.commons.lang3.mutable.MutableInt;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

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
	private static final Pattern P_REFS = Pattern.compile("<ref\\b([^>]*?)(?<!/ ?)>.*?</ref *?>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
	private static final Pattern P_LINE_JOINER;
	private static final Pattern P_LINE_SPLITTER_LEFT;
	private static final Pattern P_LINE_SPLITTER_BOTH;
	private static final Pattern P_TEMPLATE = Pattern.compile("\\{\\{(.+?)(\\|(?:\\{\\{.+?\\}\\}|.*?)+)?\\}\\}", Pattern.DOTALL);
	private static final Pattern P_XX_ES_TEMPLATE = Pattern.compile("\\{\\{ *?.+?-ES( *?\\| *?(\\{\\{.+?\\}\\}|.*?)+)*?\\}\\}", Pattern.DOTALL);
	private static final Pattern P_OLD_STRUCT_HEADER = Pattern.compile("^(.*?)(\\{\\{ *?(?:ES|[\\w-]+?-ES|TRANS|TRANSLIT|lengua|translit)(?: *?\\| *?(?:\\{\\{.+?\\}\\}|.*?)+)*?\\}\\}) *(.*)$", Pattern.MULTILINE);
	private static final Pattern P_INFLECT_TMPLS = Pattern.compile("\\{\\{(inflect\\..+?)[\\|\\}]");
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
	private static final Pattern P_CATEGORY_LINKS = Pattern.compile("\\[\\[ *?(?i:category|categoría) *?: *([^\\[\\{\\}]+?) *\\]\\]");
	private static final Pattern P_FLEX_ETYM = Pattern.compile("([Dd]e|[Vv]éase|[Dd]el verbo|[Ff]lexión de) (?<quot>''|\"|)(\\[{2}[^\\]]+\\]{2}|\\{{2}l\\|[\\w-]+\\|[^|}]+\\}{2})\\k<quot>( y (de )?\\[{2}(-ed|-ing)\\]{2})?");
	private static final Pattern P_CLEAR_TMPLS = Pattern.compile("\n?\\{\\{ *?clear *?\\}\\}\n?");
	private static final Pattern P_UCF = Pattern.compile("^; *?\\d+?(?: *?\\{\\{[^\\{]+?\\}\\})? *?: *?(\\[\\[:?([^\\]\\|]+)(?:\\|((?:\\]?[^\\]\\|])*+))*\\]\\])(.*)$", Pattern.MULTILINE);
	private static final Pattern P_TERM = Pattern.compile("^;( *?\\d+?)( *?\\{\\{[^\\{]+?\\}\\})?( *?:)(.*)$", Pattern.MULTILINE);
	
	private static final List<String> LENG_PARAM_TMPLS = Arrays.asList(
		"etimología", "etimología2", "transliteración", "homófono", "grafía alternativa", "variantes",
		"parónimo", "sinónimo", "antónimo", "hiperónimo", "hipónimo", "uso", "ámbito", "apellido",
		"doble conjugación", "derivad", "grafía", "pron-graf", "rima", "relacionado", "pronunciación",
		"diacrítico", "ampliable", "variante", "variante obsoleta", "grafía obsoleta", "grafía rara",
		"sustantivo de verbo", "sustantivo de adjetivo", "antropónimo masculino", "antropónimo femenino",
		"adjetivo de padecimiento", "adjetivo de sustantivo", "adjetivo de verbo", "adverbio de adjetivo",
		"adverbio de sustantivo"
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
	
	private static final List<String> SOFT_REDIR_TMPLS = Arrays.asList(
		"grafía", "grafía obsoleta", "grafía rara", "variante", "variante obsoleta", "contracción",
		"redirección suave"
	);
	
	private static final List<String> SECTION_TMPLS = Arrays.asList(
		"abreviatura", "adjetivo", "adjetivo cardinal", "adjetivo numeral", "adjetivo ordinal",
		"adjetivo posesivo", "adverbio", "adverbio de afirmación", "adverbio de cantidad",
		"adverbio de duda", "adverbio de lugar", "adverbio de modo", "adverbio de negación",
		"adverbio de orden", "adverbio de tiempo", "adverbio interrogativo", "adverbio relativo",
		"afijo", "artículo", "artículo determinado", "artículo indeterminado", "conjunción", "dígrafo",
		/*"forma verbal",*/ "interjección", "letra", "locución", "locución adjetiva", "locución adverbial",
		"locución conjuntiva", "locución interjectiva", "locución prepositiva", "locución sustantiva",
		"locución verbal", "numeral", "onomatopeya", "partícula", "postposición", "prefijo", "preposición",
		"preposición de ablativo", "preposición de acusativo", "preposición de acusativo o ablativo",
		"preposición de genitivo", "pronombre", "pronombre interrogativo", "pronombre personal",
		"pronombre relativo", "refrán", "sigla", "sufijo", "sufijo flexivo", "sustantivo",
		"sustantivo ambiguo", "sustantivo animado", "sustantivo femenino", "sustantivo femenino y masculino",
		"sustantivo inanimado", "sustantivo masculino", "sustantivo neutro", "sustantivo propio",
		"sustantivo común", /*"símbolo",*/ "verbo", "verbo impersonal", "verbo intransitivo",
		"verbo pronominal", "verbo transitivo"
	);
	
	private static final List<Pattern> COMMENT_PATT_LIST;
	private static final List<String> LS_SPLITTER_LIST;
	private static final List<String> BS_SPLITTER_LIST;
	private static final List<String> STANDARD_HEADERS;
	
	private static final Map<String, String> TMPL_ALIAS_MAP;
	private static final Map<String, List<Catgram.Data>> SECTION_DATA_MAP;
	
	private static final String TRANSLATIONS_TEMPLATE;
	private static final String HAS_FLEXIVE_FORM_HEADER_RE = "([Ff]orma|\\{\\{forma) .+";
	
	private static final Predicate<LangSection> FLEXIVE_FORM_CHECK;
	
	private static final Predicate<Section> REDUCED_SECTION_CHECK;
	
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
		LS_SPLITTER_LIST.add("ejemplo");
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
	}
	
	static {
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
			"<!-- ?\\{\\{pronunciación\\|(\\[?.+?\\]?| ˈ )\\}\\} \\|-\\|c=.+?\\|s=.+?(\\}\\}|\\|) *?-->",
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
	}
	
	static {
		// TODO: expand per [[Especial:TodasLasRedirecciones]]
		// TODO: delete obsolete templates
		
		TMPL_ALIAS_MAP = new HashMap<>(150, 1);
		
		TMPL_ALIAS_MAP.put("Pronunciación", "pronunciación");
		TMPL_ALIAS_MAP.put("Etimología", "etimología");
		TMPL_ALIAS_MAP.put("etimologia", "etimología");
		TMPL_ALIAS_MAP.put("etyl", "etimología");
		TMPL_ALIAS_MAP.put("Desambiguación", "desambiguación");
		TMPL_ALIAS_MAP.put("Desambiguacion", "desambiguación");
		TMPL_ALIAS_MAP.put("desambiguacion", "desambiguación");
		TMPL_ALIAS_MAP.put("desamb", "desambiguación");
		TMPL_ALIAS_MAP.put("Desambig", "desambiguación");
		TMPL_ALIAS_MAP.put("Notadesambiguación", "desambiguación");
		TMPL_ALIAS_MAP.put("Desambiguado", "desambiguación");
		TMPL_ALIAS_MAP.put("grafías alternativas", "grafía alternativa");
		TMPL_ALIAS_MAP.put("ucf", "plm");
		TMPL_ALIAS_MAP.put("Anagramas", "anagrama");
		TMPL_ALIAS_MAP.put("Parónimos", "parónimo");
		TMPL_ALIAS_MAP.put("tit ref", "título referencias");
		TMPL_ALIAS_MAP.put("sustantivo masculino y femenino", "sustantivo femenino y masculino");
		TMPL_ALIAS_MAP.put("acrónimo", "sigla");
		TMPL_ALIAS_MAP.put("redireccion suave", "redirección suave");
		
		TMPL_ALIAS_MAP.put("DRAE1914", "DLC1914");
		TMPL_ALIAS_MAP.put("DUE", "MaríaMoliner");
		TMPL_ALIAS_MAP.put("Moliner", "MaríaMoliner");
		TMPL_ALIAS_MAP.put("NDLC1866", "Labernia1866");
		TMPL_ALIAS_MAP.put("dlc1914", "DLC1914");
		TMPL_ALIAS_MAP.put("dme1831", "DME1831");
		TMPL_ALIAS_MAP.put("dme1864", "DME1864");
		TMPL_ALIAS_MAP.put("dp2002", "DP2002");
		TMPL_ALIAS_MAP.put("drae1914", "DLC1914");
		
		// TODO: these are not transcluded, analyze "leng" params instead
		// TODO: move to langTemplateParams()
		
		TMPL_ALIAS_MAP.put("aka", "ak");
		TMPL_ALIAS_MAP.put("allentiac", "qbt");
		TMPL_ALIAS_MAP.put("arg", "an");
		TMPL_ALIAS_MAP.put("aus-dar", "0hk");
		TMPL_ALIAS_MAP.put("ava", "av");
		TMPL_ALIAS_MAP.put("ave", "ae");
		TMPL_ALIAS_MAP.put("bak", "ba");
		TMPL_ALIAS_MAP.put("bam", "bm");
		TMPL_ALIAS_MAP.put("bat-smg", "sgs");
		TMPL_ALIAS_MAP.put("be-x-old", "be");
		TMPL_ALIAS_MAP.put("bod", "bo");
		TMPL_ALIAS_MAP.put("ces", "cs");
		TMPL_ALIAS_MAP.put("cha", "ch");
		TMPL_ALIAS_MAP.put("che", "ce");
		TMPL_ALIAS_MAP.put("chu", "cu");
		TMPL_ALIAS_MAP.put("chv", "cv");
		TMPL_ALIAS_MAP.put("cor", "kw");
		TMPL_ALIAS_MAP.put("cos", "co");
		TMPL_ALIAS_MAP.put("cre", "cr");
		TMPL_ALIAS_MAP.put("cym", "cy");
		TMPL_ALIAS_MAP.put("div", "dv");
		TMPL_ALIAS_MAP.put("ewe", "ee");
		TMPL_ALIAS_MAP.put("fas", "fa");
		TMPL_ALIAS_MAP.put("fiu-vro", "vro");
		TMPL_ALIAS_MAP.put("ful", "ff");
		TMPL_ALIAS_MAP.put("gkm", "qgk");
		TMPL_ALIAS_MAP.put("gla", "gd");
		TMPL_ALIAS_MAP.put("gle", "ga");
		TMPL_ALIAS_MAP.put("glv", "gv");
		TMPL_ALIAS_MAP.put("her", "hz");
		TMPL_ALIAS_MAP.put("hmo", "ho");
		TMPL_ALIAS_MAP.put("hrv", "hr");
		TMPL_ALIAS_MAP.put("hye", "hy");
		TMPL_ALIAS_MAP.put("ind", "id");
		TMPL_ALIAS_MAP.put("jav", "jv");
		TMPL_ALIAS_MAP.put("kal", "kl");
		TMPL_ALIAS_MAP.put("kat", "ka");
		TMPL_ALIAS_MAP.put("lzh", "zh-classical");
		TMPL_ALIAS_MAP.put("mah", "mh");
		TMPL_ALIAS_MAP.put("mlt", "mt");
		TMPL_ALIAS_MAP.put("mri", "mi");
		TMPL_ALIAS_MAP.put("nb", "no");
		TMPL_ALIAS_MAP.put("nrm", "nrf");
		TMPL_ALIAS_MAP.put("oji", "oj");
		TMPL_ALIAS_MAP.put("ood", "nai");
		TMPL_ALIAS_MAP.put("protoindoeuropeo", "ine");
		TMPL_ALIAS_MAP.put("prv", "oc");
		TMPL_ALIAS_MAP.put("roa-oca", "oca");
		TMPL_ALIAS_MAP.put("roa-rup", "rup");
		TMPL_ALIAS_MAP.put("roa-tara", "roa-tar");
		TMPL_ALIAS_MAP.put("roh", "rm");
		TMPL_ALIAS_MAP.put("sna", "sn");
		TMPL_ALIAS_MAP.put("srd", "sc");
		TMPL_ALIAS_MAP.put("srp", "sr");
		TMPL_ALIAS_MAP.put("ssw", "ss");
		TMPL_ALIAS_MAP.put("tsz", "pua");
		TMPL_ALIAS_MAP.put("yue", "zh-yue");
		TMPL_ALIAS_MAP.put("zh-cn", "zh");
		TMPL_ALIAS_MAP.put("zh-min-nan", "nan");
		TMPL_ALIAS_MAP.put("zh-wuu", "wuu");
		
		TMPL_ALIAS_MAP.put("AR", "AR-ES");
		TMPL_ALIAS_MAP.put("CA", "CA-ES");
		TMPL_ALIAS_MAP.put("CHN", "CMN-ES");
		TMPL_ALIAS_MAP.put("EN", "EN-ES");
		TMPL_ALIAS_MAP.put("EU", "EU-ES");
		TMPL_ALIAS_MAP.put("FR", "FR-ES");
		TMPL_ALIAS_MAP.put("GL", "GL-ES");
		TMPL_ALIAS_MAP.put("IT", "IT-ES");
		TMPL_ALIAS_MAP.put("LA", "LA-ES");
		TMPL_ALIAS_MAP.put("MN", "MN-ES");
		TMPL_ALIAS_MAP.put("PL", "PL-ES");
		TMPL_ALIAS_MAP.put("PT", "PT-ES");
		TMPL_ALIAS_MAP.put("QU", "QU-ES");
		
		TMPL_ALIAS_MAP.put("Allentiac-ES", "QBT-ES");
		TMPL_ALIAS_MAP.put("BAT-SMG-ES", "SGS-ES");
		TMPL_ALIAS_MAP.put("CYM-ES", "CY-ES");
		TMPL_ALIAS_MAP.put("FIU-VRO-ES", "VRO-ES");
		TMPL_ALIAS_MAP.put("FRS-ES", "STQ-ES");
		TMPL_ALIAS_MAP.put("LZH-ES", "ZH-CLASSICAL-ES");
		TMPL_ALIAS_MAP.put("MYA-ES", "MY-ES");
		TMPL_ALIAS_MAP.put("NLD-ES", "NL-ES");
		TMPL_ALIAS_MAP.put("OFR-ES", "FRO-ES");
		TMPL_ALIAS_MAP.put("PROTOPOLINESIO-ES", "POZ-POL-ES");
		TMPL_ALIAS_MAP.put("Protopolinesio-ES", "POZ-POL-ES");
		TMPL_ALIAS_MAP.put("PRV-ES", "OC-ES");
		TMPL_ALIAS_MAP.put("TGL-ES", "TL-ES");
		TMPL_ALIAS_MAP.put("TOG-ES", "TO-ES");
		TMPL_ALIAS_MAP.put("TSZ-ES", "PUA-ES");
		TMPL_ALIAS_MAP.put("YUE-ES", "ZH-YUE-ES");
		TMPL_ALIAS_MAP.put("ZH-ES", "CMN-ES");
		
		TMPL_ALIAS_MAP.put("Indoeuropeo", "INE-ES");
		TMPL_ALIAS_MAP.put("castellanoantiguo", "OSP-ES");
		TMPL_ALIAS_MAP.put("Aymará-Español", "AY-ES");
		TMPL_ALIAS_MAP.put("Guaraní-Español", "GN-ES");
		TMPL_ALIAS_MAP.put("Náhuatl-Español", "NAH-ES");
		TMPL_ALIAS_MAP.put("Quechua-Español", "QU-ES");
		
		TMPL_ALIAS_MAP.put("Caracter oriental", "Carácter oriental");
	}
	
	static {
		SECTION_DATA_MAP = new HashMap<>(50, 1);
		
		SECTION_DATA_MAP.put("adjetivo cardinal",        Arrays.asList(Catgram.Data.ADJECTIVE, Catgram.Data.CARDINAL));
		SECTION_DATA_MAP.put("adjetivo numeral",         Arrays.asList(Catgram.Data.ADJECTIVE, Catgram.Data.NUMERAL));
		SECTION_DATA_MAP.put("adjetivo ordinal",         Arrays.asList(Catgram.Data.ADJECTIVE, Catgram.Data.ORDINAL));
		SECTION_DATA_MAP.put("adjetivo posesivo",        Arrays.asList(Catgram.Data.ADJECTIVE, Catgram.Data.POSSESSIVE));
		SECTION_DATA_MAP.put("adverbio de afirmación",   Arrays.asList(Catgram.Data.ADVERB, Catgram.Data.OF_AFFIRMATION));
		SECTION_DATA_MAP.put("adverbio de cantidad",     Arrays.asList(Catgram.Data.ADVERB, Catgram.Data.OF_QUANTITY));
		SECTION_DATA_MAP.put("adverbio de duda",         Arrays.asList(Catgram.Data.ADVERB, Catgram.Data.OF_DOUBT));
		SECTION_DATA_MAP.put("adverbio de lugar",        Arrays.asList(Catgram.Data.ADVERB, Catgram.Data.OF_PLACE));
		SECTION_DATA_MAP.put("adverbio de modo",         Arrays.asList(Catgram.Data.ADVERB, Catgram.Data.OF_MOOD));
		SECTION_DATA_MAP.put("adverbio de negación",     Arrays.asList(Catgram.Data.ADVERB, Catgram.Data.OF_NEGATION));
		SECTION_DATA_MAP.put("adverbio de orden",        Arrays.asList(Catgram.Data.ADVERB, Catgram.Data.OF_SEQUENCE));
		SECTION_DATA_MAP.put("adverbio de tiempo",       Arrays.asList(Catgram.Data.ADVERB, Catgram.Data.OF_TIME));
		SECTION_DATA_MAP.put("adverbio interrogativo",   Arrays.asList(Catgram.Data.ADVERB, Catgram.Data.INTERROGATIVE));
		SECTION_DATA_MAP.put("adverbio relativo",        Arrays.asList(Catgram.Data.ADVERB, Catgram.Data.RELATIVE));
		SECTION_DATA_MAP.put("artículo determinado",     Arrays.asList(Catgram.Data.ARTICLE, Catgram.Data.DETERMINATE));
		SECTION_DATA_MAP.put("artículo indeterminado",   Arrays.asList(Catgram.Data.ARTICLE, Catgram.Data.INDETERMINATE));
		SECTION_DATA_MAP.put("locución adjetiva",        Arrays.asList(Catgram.Data.PHRASE, Catgram.Data.ADJECTIVE));
		SECTION_DATA_MAP.put("locución adverbial",       Arrays.asList(Catgram.Data.PHRASE, Catgram.Data.ADVERB));
		SECTION_DATA_MAP.put("locución conjuntiva",      Arrays.asList(Catgram.Data.PHRASE, Catgram.Data.CONJUNCTION));
		SECTION_DATA_MAP.put("locución interjectiva",    Arrays.asList(Catgram.Data.PHRASE, Catgram.Data.INTERJECTION));
		SECTION_DATA_MAP.put("locución preposicional",   Arrays.asList(Catgram.Data.PHRASE, Catgram.Data.PREPOSITION));
		SECTION_DATA_MAP.put("locución sustantiva",      Arrays.asList(Catgram.Data.PHRASE, Catgram.Data.NOUN));
		SECTION_DATA_MAP.put("locución verbal",          Arrays.asList(Catgram.Data.PHRASE, Catgram.Data.VERB));
		SECTION_DATA_MAP.put("preposición de ablativo",  Arrays.asList(Catgram.Data.PREPOSITION, Catgram.Data.OF_ABLATIVE));
		SECTION_DATA_MAP.put("preposición de acusativo", Arrays.asList(Catgram.Data.PREPOSITION, Catgram.Data.OF_ACCUSATIVE));
		SECTION_DATA_MAP.put("preposición de acusativo o ablativo", Arrays.asList(Catgram.Data.PREPOSITION, Catgram.Data.OF_ACCUSATIVE_OR_ABLATIVE));
		SECTION_DATA_MAP.put("preposición de genitivo",  Arrays.asList(Catgram.Data.PREPOSITION, Catgram.Data.OF_GENITIVE));
		SECTION_DATA_MAP.put("pronombre interrogativo",  Arrays.asList(Catgram.Data.PRONOUN, Catgram.Data.INTERROGATIVE));
		SECTION_DATA_MAP.put("pronombre personal",       Arrays.asList(Catgram.Data.PRONOUN, Catgram.Data.PERSONAL));
		SECTION_DATA_MAP.put("pronombre relativo",       Arrays.asList(Catgram.Data.PRONOUN, Catgram.Data.RELATIVE));
		SECTION_DATA_MAP.put("sustantivo ambiguo",       Arrays.asList(Catgram.Data.NOUN, Catgram.Data.AMBIGUOUS));
		SECTION_DATA_MAP.put("sustantivo animado",       Arrays.asList(Catgram.Data.NOUN, Catgram.Data.ANIMATE));
		SECTION_DATA_MAP.put("sustantivo femenino",      Arrays.asList(Catgram.Data.NOUN, Catgram.Data.FEMININE));
		SECTION_DATA_MAP.put("sustantivo inanimado",     Arrays.asList(Catgram.Data.NOUN, Catgram.Data.INANIMATE));
		SECTION_DATA_MAP.put("sustantivo masculino",     Arrays.asList(Catgram.Data.NOUN, Catgram.Data.MASCULINE));
		SECTION_DATA_MAP.put("sustantivo neutro",        Arrays.asList(Catgram.Data.NOUN, Catgram.Data.NEUTER));
		SECTION_DATA_MAP.put("sustantivo propio",        Arrays.asList(Catgram.Data.NOUN, Catgram.Data.PROPER));
		SECTION_DATA_MAP.put("sustantivo común",         Arrays.asList(Catgram.Data.NOUN, Catgram.Data.COMMON));
		SECTION_DATA_MAP.put("verbo impersonal",         Arrays.asList(Catgram.Data.VERB, Catgram.Data.IMPERSONAL));
		SECTION_DATA_MAP.put("verbo intransitivo",       Arrays.asList(Catgram.Data.VERB, Catgram.Data.INTRANSITIVE));
		SECTION_DATA_MAP.put("verbo pronominal",         Arrays.asList(Catgram.Data.VERB, Catgram.Data.PRONOUN));
		SECTION_DATA_MAP.put("verbo transitivo",         Arrays.asList(Catgram.Data.VERB, Catgram.Data.TRANSITIVE));
	}
	
	static {
		REDUCED_SECTION_CHECK = section -> {
			if (
				section instanceof LangSection &&
				((LangSection) section).langCodeEqualsTo("trans")
			) {
				return true;
			}
			
			List<Section> targetSections = section.getChildSections().stream()
				.filter(s -> !STANDARD_HEADERS.contains(s.getStrippedHeader()))
				.collect(Collectors.toList());
			
			if (targetSections.isEmpty()) {
				return false;
			}
			
			boolean hasStandardTerm = false;
			boolean hasSpecialTerm = false;
			
			for (Section targetSection : targetSections) {
				String text = removeCommentsAndNoWikiText(targetSection.getIntro());
				Matcher m = P_TERM.matcher(text);
				
				while (m.find()) {
					String term = m.group();
					boolean anyMatch = SOFT_REDIR_TMPLS.stream()
						.anyMatch(template -> !getTemplates(template, term).isEmpty());
					
					hasStandardTerm |= !anyMatch;
					hasSpecialTerm |= anyMatch;
					
					if (hasStandardTerm && hasSpecialTerm) {
						return false;
					}
				}
			}
			
			return !hasStandardTerm && hasSpecialTerm;
		};
		
		FLEXIVE_FORM_CHECK = langSection -> {
			List<Section> allSubsections = AbstractSection.flattenSubSections(langSection);
			allSubsections.remove(langSection);
			
			List<String> allHeaders = allSubsections.stream()
				.map(AbstractSection::getStrippedHeader)
				.collect(Collectors.toList());
			
			// TODO: https://es.wiktionary.org/w/index.php?title=edere&oldid=3774065
			// TODO: https://es.wiktionary.org/w/index.php?title=consultum&diff=3774086&oldid=3773900
						
			if (!allHeaders.removeIf(header -> header.matches(HAS_FLEXIVE_FORM_HEADER_RE))) {
				return true;
			}
			
			allHeaders.removeIf(STANDARD_HEADERS::contains);
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
		text = removeCommentsAndNoWikiText(text);
		Matcher m = P_XX_ES_TEMPLATE.matcher(text);
		
		if (
			m.find() ||
			!getTemplates("ES", text).isEmpty() ||
			!getTemplates("TRANSLIT", text).isEmpty() ||
			!getTemplates("TRANS", text).isEmpty() ||
			!getTemplates("TAXO", text).isEmpty() ||
			!getTemplates("carácter oriental", text).isEmpty()
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
		insertLangSectionTemplates();
		normalizeSectionHeaders();
		substituteReferencesTemplate();
		duplicateReferencesSection();
		moveReferencesSection();
		
		// TODO
		if (!checkFlexiveFormHeaders()) {
			throw new UnsupportedOperationException("checkFlexiveFormHeaders()");
		}
		
		normalizeEtymologyHeaders();
		pullUpForeignTranslationsSections();
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
		checkLangCodeCase();
		langTemplateParams();
		manageSectionTemplates();
		addSectionTemplates();
		removeCategoryLinks();
		deleteEmptySections();
		deleteWrongSections();
		removeEtymologyTemplates();
		manageClearElements();
		applyUcfTemplates();
		sanitizeReferences();
		groupReferences();
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
		int left = countMatches(text, open);
		int right = countMatches(text, close);
		
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
			String replacement = repeat('-', m.group().length());
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
		Range<Integer>[] refs = Utils.findRanges(text, P_REFS);
		Range<Integer>[] templates = Utils.findRanges(text, "{{", "}}");
		Range<Integer>[] wikitables = Utils.findRanges(text, "{|", "|}");
		
		List<Range<Integer>> tagRanges = Utils.getCombinedRanges(tags);
		List<Range<Integer>> refRanges = Utils.getCombinedRanges(refs);
		List<Range<Integer>> templateRanges = Utils.getCombinedRanges(templates);
		List<Range<Integer>> wikitableRanges = Utils.getCombinedRanges(wikitables);
		
		// P_LINE_JOINER
		
		String temp = Utils.replaceWithStandardIgnoredRanges(text, P_LINE_JOINER, (m, sb) -> {
			boolean isRefRange = Utils.containedInRanges(refRanges, m.start());
			
			if (!isRefRange && (
				// assume <ref> regions cannot contain these elements 
				Utils.containedInRanges(tagRanges, m.start()) ||
				Utils.containedInRanges(templateRanges, m.start()) ||
				Utils.containedInRanges(wikitableRanges, m.start())
			)) {
				return;
			}
			
			// TODO: review reference tags
			// https://es.wiktionary.org/w/index.php?title=casa&diff=2912203&oldid=2906951
			
			String replacement = null;
			
			if (removeCommentsAndNoWikiText(m.group(1)).startsWith(" ")) {
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
			previousLine = removeCommentsAndNoWikiText(previousLine);
			
			final String[] arr = isRefRange
				? new String[]{":", ";", "*", "#"}
				: new String[]{" ", ":", ";", "*", "#"};
			
			if (!previousLine.isEmpty() && startsWithAny(previousLine, arr)) {
				return;
			}
			
			index = text.indexOf("\n", m.start(1));
			String thisLine = text.substring(m.start(1), index != -1 ? index : text.length());
			
			if (thisLine.startsWith(" |")) { // template parameters and wikitable rows
				return;
			}
			
			m.appendReplacement(sb, " " + replacement);
		});
		
		// P_TERM 
		
		final String token = "<<<P_TERM_REPLACEMENT>>>";
		
		String formatted = Utils.replaceWithStandardIgnoredRanges(temp, P_TERM, (m, sb) -> {
			String term = m.group(4);
			
			if (term.isEmpty() && temp.substring(m.end(4)).startsWith("\n:")) {
				String replacement = Matcher.quoteReplacement(m.group());
				replacement += token;
				m.appendReplacement(sb, replacement);
			}
		});
		
		// couldn't find an easier way to use Matcher outside its .find() regions
		if (!formatted.equals(temp)) {
			formatted = formatted.replace(token + "\n:", "");
		}
		
		checkDifferences(formatted, "joinLines", "uniendo líneas");
	}
	
	public void normalizeTemplateNames() {
		String formatted = text;
		List<String> found = new ArrayList<>();
		
		for (Map.Entry<String, String> entry : TMPL_ALIAS_MAP.entrySet()) {
			String temp = Utils.replaceTemplates(formatted, entry.getKey(), template -> {
				HashMap<String, String> params = getTemplateParametersWithValue(template);
				
				// ParseUtils's template methods are case-insensitive
				if (params.get("templateName").equals(entry.getKey())) {
					params.put("templateName", entry.getValue());
					return templateFromMap(params);
				} else {
					return template;
				}
			});
			
			if (!temp.equals(formatted)) {
				formatted = temp;
				found.add(String.format("{{%s}} → {{%s}}", entry.getKey(), entry.getValue()));
			}
		}
		
		if (found.isEmpty()) {
			return;
		}
		
		// Must check again due to {{XX}} -> {{XX-ES}} replacements
		checkOldStructure(formatted);
		
		checkDifferences(formatted, "normalizeTemplateNames", String.join(", ", found));
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
		// TODO: trailing period after {{etimología}}
		// TODO: catch open comment tags in arbitrary Sections - [[Especial:PermaLink/2709606]]
		
		final String preferredFileNSAlias = "Archivo";
		Set<String> setFileAlias = new HashSet<>();
		Set<String> setLog = new LinkedHashSet<>();
		
		// sanitize image links
		
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
		
		formatted = applyReplacementFunction(formatted, setLog, "\"^[(){}\\[\\]]$\" → \"\"", text ->
			Utils.replaceWithStandardIgnoredRanges(text, "(?m)^[(){}\\[\\]]$", "")
		);
		
		formatted = applyReplacementFunction(formatted, setLog, "\"^;[2-9]:$\" → \"\"", text ->
			Utils.replaceWithStandardIgnoredRanges(text, "(?m)^; ?[2-9]: ?\\.?$", "")
		);
		
		formatted = applyReplacementFunction(formatted, setLog, "\"^;\\d.+\" → \";\\d:.+\"", text ->
			Utils.replaceWithStandardIgnoredRanges(text, "(?m)^; ?(\\d++)((?: ?\\{{2}plm[\\|\\}]|(?! ?\\{{2}))[^:\n]+)$", ";$1:$2")
		);
		
		// TODO: use Jsoup?
		formatted = Utils.replaceWithStandardIgnoredRanges(formatted, "(?i)<(/?)references\\b([^>=]+?)>", "<$1references$2>");
		
		// Jsoup fails miserably on <ref name=a/> tags (no space before '/', no quotes around 'a'),
		// automatically converting them to non-self-closing <ref name="a/">
		formatted = Utils.replaceWithStandardIgnoredRanges(formatted, "(?i)<ref ([^>].+?)(?<! )/>", "<ref $1 />");
		
		// trim contents of <ref> tags
		
		Document doc = Jsoup.parseBodyFragment(formatted);
		doc.outputSettings().prettyPrint(false);
		
		doc.getElementsByTag("ref").stream()
			.filter(ref -> !ref.tag().isSelfClosing())
			.forEach(ref -> ref.html(ref.html().trim()));
		
		Page page = Page.store(title, Utils.decodeHtmlDocument(doc));
		
		// remove trailing newlines from the last Section
		
		try {
			List<Section> sections = page.getAllSections();
			Section lastSection = sections.get(sections.size() - 1);
			
			if (lastSection.getTrailingNewlines() > 1) {
				lastSection.setTrailingNewlines(1);
			}
		} catch (IndexOutOfBoundsException e) {}
		
		String summary = null;
		
		if (!setLog.isEmpty()) {
			summary = String.join(", ", setLog);
		}
		
		checkDifferences(page.toString(), "minorSanitizing", summary);
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
			!getTemplates("TRANSLIT", text).isEmpty() ||
			!getTemplates("TAXO", text).isEmpty() ||
			!getTemplates("carácter oriental", text).isEmpty()
		) {
			return;
		}
		
		// Process header templates
		
		String formatted = replaceOldStructureTemplates(title, text);
		
		if (formatted.equals(text)) {
			return;
		}
		
		page = Page.store(page.getTitle(), formatted);
		
		// References section(s)
		
		List<Section> references = page.findSectionsWithHeader("(<small *?>)? *?[Rr]eferencias.*?(<small *?/ *?>)?");
		references.forEach(AbstractSection::detachOnlySelf);
		references.forEach(section -> section.setHeader("Referencias y notas"));
		references.forEach(section -> section.setLevel(2));
		
		// Push down old-structure sections
		// TODO: use pushLevels()
		
		for (Section section : page.getAllSections()) {
			// workaround that benefits from the lack of Section reparsing 
			if (section.getLangSectionParent() == null) {
				try {
					section.setLevel(section.getLevel() + 1);
				} catch (IllegalArgumentException e) {}
			}
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
			
			if (!containsAny(alt, '{', '}', '[', ']', '(', ')')) {
				extractAltParameter(langSection, alt);
			}
		}
		
		for (Section section : page.filterSections(s -> s.getHeader().startsWith("ETYM "))) {
			String header = section.getHeader();
			String alt = header.replaceFirst(".+alt-(.*)", "$1");
			
			if (alt.isEmpty()) {
				continue;
			}
			
			if (!containsAny(alt, '{', '}', '[', ']', '(', ')')) {
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
				getTemplates("etimología", section.getIntro()).isEmpty() &&
				getTemplates("etimología2", section.getIntro()).isEmpty()
			) {
				continue;
			}
			
			if (
				section.getIntro().isEmpty() &&
				section.getChildSections().isEmpty()
			) {
				if (!(section instanceof LangSection)) {
					section.detachOnlySelf();
				} else {
					continue;
				}
			} else if (
				section instanceof LangSection &&
				((LangSection) section).hasSubSectionWithHeader(HAS_FLEXIVE_FORM_HEADER_RE) &&
				!Optional.ofNullable(section.nextSiblingSection())
					.filter(s -> s.getHeader().startsWith("ETYM "))
					.isPresent()
			) {
				continue;
			} else if (
				section instanceof LangSection &&
				REDUCED_SECTION_CHECK.test((LangSection) section)
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
						!getTemplates("etimología", section.getIntro()).isEmpty() ||
						!getTemplates("etimología2", section.getIntro()).isEmpty()
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
					
					String template = templateFromMap(params);
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
				
				if (getTemplates(templateName, text).isEmpty()) {
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
			.filter(ls -> ls.getIntro().isEmpty() && ls.getChildSections().isEmpty())
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
				etymologySection.getChildSections().forEach(s -> s.pushLevels(-1));
				etymologySection.setHeader("Etimología");
			} else {
				langSection.getChildSections().stream()
					.filter(s -> !etymologySections.contains(s))
					.forEach(s -> s.pushLevels(1));
				
				for (int i = 1; i <= etymologySections.size(); i++) {
					Section etymologySection = etymologySections.get(i - 1);
					String header = String.format("Etimología %d", i);
					etymologySection.setHeader(header);
				}
			}
		}
		
		// Reattach references Sections
		page.appendSections(references.toArray(new Section[references.size()]));
		
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
				
				HashMap<String, String> params = getTemplateParametersWithValue(template);
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
				} else if (name.equals("TRANS")) {
					name = "trans";
					params.put("templateName", "lengua");
					params.put("ParamWithoutName1", name);
					params.remove("ParamWithoutName2");
					params.remove("num");
					params.remove("núm");
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
					String newTemplate = templateFromMap(params);
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
			if (removeCommentsAndNoWikiText(alt).isEmpty()) {
				Map<String, String> params = ((LangSection) section).getTemplateParams();
				params.remove("alt");
				((LangSection) section).setTemplateParams(params);
			}
			
			return;
		}
		
		String intro = section.getIntro();
		List<String> pronLaTmpls = getTemplates("pron.la", intro);
		
		if (pronLaTmpls.size() == 1) {
			String pronLaTmpl = pronLaTmpls.get(0);
			HashMap<String, String> pronLaParams = getTemplateParametersWithValue(pronLaTmpl);
			
			if (!pronLaParams.containsKey("alt")) {
				pronLaParams.put("alt", alt);
				intro = intro.replace(pronLaTmpl, templateFromMap(pronLaParams));
				section.setIntro(intro);
			}
		} else if (
			getTemplates("diacrítico", intro).isEmpty() &&
			!intro.contains("Diacrítico:")
		) {
			HashMap<String, String> params = new LinkedHashMap<>();
			params.put("templateName", "diacrítico");
			params.put("ParamWithoutName1", alt);
			String template = templateFromMap(params);
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
		
		line = removeCommentsAndNoWikiText(line);
		line = line.replaceAll("<ref [^>]+?(?<=/ ?)>", "");
		
		for (int i = 0; i < arr1.length; i++) {
			String[] delimiters = {arr1[i], arr2[i]};
			
			if (!biFunc.apply(line, delimiters)) {
				return false;
			}
		}
		
		return true;
	}
	
	public void insertLangSectionTemplates() {
		if (isOldStructure) {
			return;
		}
		
		Page page = Page.store(title, text);
		Section references = page.getReferencesSection();
		
		page.getAllSections().stream()
			.filter(section -> section.getLevel() == 2 && section.getTocLevel() == 1)
			.filter(section -> !(section instanceof LangSection) && section != references)
			.forEach(section -> Page.CODE_TO_LANG.entrySet().stream()
				.filter(entry -> entry.getValue().equalsIgnoreCase(section.getHeader()))
				.findAny()
				.ifPresent(entry -> section.setHeader(String.format("{{lengua|%s}}", entry.getKey())))
			);
		
		String formatted = page.toString();
		checkDifferences(formatted, "insertLangSectionTemplates", "insertando plantillas de encabezamiento");
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
			
			header = strip(header, "=").trim();
			header = header.replaceFirst("(?i)^Etimolog[íi]a", "Etimología");
			header = header.replaceFirst("(?i)^Pronunciaci[óo]n\\b", "Pronunciación");
			// TODO: don't confuse with {{locución}}, {{refrán}}
			header = header.replaceFirst("(?i)^Locuciones", "Locuciones");
			header = header.replaceFirst("(?i)^(?:Refranes|Dichos?)", "Refranes");
			header = header.replaceFirst("(?i)^Conjugaci[óo]n\\b", "Conjugación");
			header = header.replaceFirst("(?i)^Informaci[óo]n (?:adicional|avanzada)", "Información adicional");
			header = header.replaceFirst("(?i)^(?:Ver|V[ée]ase) tambi[ée]n", "Véase también");
			header = header.replaceFirst("(?i)^Proverbio\\b", "Refrán");
			
			header = header.replaceFirst("(?i)^Acr[óo]nimo\\b$", "Sigla");
			header = header.replaceFirst("(?i)^Sub?stantivo\\b$", "Sustantivo");
			header = header.replaceFirst("(?i)^Contracci[óo]n\\b", "Contracción");
			
			header = header.replaceFirst("(?i)^Formas? flexivas?$", "Forma flexiva");
			header = header.replaceFirst("(?i)^Formas? (?:de )?sub?stantiv[oa]s?$", "Forma sustantiva");
			header = header.replaceFirst("(?i)^Formas? (?:de )?verb(?:os?|al(?:es)?)$", "Forma verbal");
			header = header.replaceFirst("(?i)^Formas? (?:de )?adjetiv[oa]s?$", "Forma adjetiva");
			header = header.replaceFirst("(?i)^Formas? (?:de )?(?:pronombres?|pronominal(?:es)?)$", "Forma pronominal");
			header = header.replaceFirst("(?i)^Formas? (?:de )?(?:preposici(?:ón|ones)|prepositiv(?:o|as?))$", "Forma prepositiva");
			header = header.replaceFirst("(?i)^Formas? (?:de )?adverbi(?:os?|al(?:es)?)$", "Forma adverbial");
			header = header.replaceFirst("(?i)^Formas? (?:de )?sub?stantiv[oa]s? (masculin|femenin|neutr)[oa]s?$", "Forma sustantiva $1a");
			
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
			
			content = removeCommentsAndNoWikiText(content); 
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
	
	public void pullUpForeignTranslationsSections() {
		Page page = Page.store(title, text);
		
		page.getAllLangSections().stream()
			.filter(langSection -> !langSection.langCodeEqualsTo("es"))
			.map(langSection -> langSection.findSubSectionsWithHeader("Traducción"))
			.flatMap(Collection::stream)
			.filter(Editor::isEmptyOrInvisible)
			.filter(section -> !section.getChildSections().isEmpty())
			.forEach(section -> {
				if (!section.getIntro().isEmpty()) {
					Section previousSection = section.previousSection();
					String previousIntro = previousSection.getIntro();
					previousIntro += "\n" + section.getIntro();
					previousSection.setIntro(previousIntro);
				}
				
				section.pushLevels(-1);
				section.detachOnlySelf();
			});
		
		String formatted = page.toString();
		checkDifferences(formatted, "pullUpForeignTranslationsSections", "subiendo subsecciones de \"Traducción\"");
	}
	
	private static boolean isEmptyOrInvisible(Section section) {
		String intro = section.getIntro();
		
		if (intro.isEmpty()) {
			return true;
		}
		
		intro = removeCommentsAndNoWikiText(intro);
		intro = intro.replace("{{clear}}", "");
		intro = intro.replaceAll("(?i)<br\\b.*?>", "");
		intro = P_IMAGES.matcher(intro).replaceAll("");
		intro = P_CATEGORY_LINKS.matcher(intro).replaceAll("");
		
		return intro.trim().isEmpty();
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
		
		for (Section pronGrafSection : page.findSectionsWithHeader("Pronunciación y escritura")) {
			pronGrafSection.getChildSections().forEach(s -> s.pushLevels(-1));
		}
		
		for (LangSection langSection : page.getAllLangSections()) {
			if (langSection.hasSubSectionWithHeader(HAS_FLEXIVE_FORM_HEADER_RE)) {
				continue;
			}
			
			List<Section> etymologySections = langSection.findSubSectionsWithHeader("^Etimología.*");
			
			if (etymologySections.isEmpty()) {
				continue;
			}
			
			if (etymologySections.size() == 1) {
				etymologySections.get(0).getChildSections().forEach(s -> s.pushLevels(-1));
				pushStandardSections(langSection.getChildSections(), 3);
			} else {
				langSection.getChildSections().stream()
					.filter(s -> !etymologySections.contains(s))
					.forEach(s -> s.pushLevels(1));
				
				etymologySections.forEach(s -> pushStandardSections(s.getChildSections(), 4));
			}
		}
		
		String formatted = page.toString();
		checkDifferences(formatted, "normalizeSectionLevels", "normalizando niveles de títulos");
	}

	private static void pushStandardSections(List<Section> sections, int level) {
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
			
			if (parentSection == null || !section.getChildSections().isEmpty()) {
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
		checkDifferences(formatted, "removePronGrafSection", "eliminando títulos de pronunciación");
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
				langSection.getChildSections().isEmpty() ||
				langSection.hasSubSectionWithHeader("Etimología.*") ||
				langSection.hasSubSectionWithHeader(HAS_FLEXIVE_FORM_HEADER_RE) ||
				RECONSTRUCTED_LANGS.contains(langSection.getLangCode()) ||
				REDUCED_SECTION_CHECK.test(langSection)
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
			
			String template = templateFromMap(params);
			etymologySection.setIntro(template + ".");
			langSection.prependSections(etymologySection);
			set.add("Etimología");
		}
		
		// Translations
		
		LangSection spanishSection = page.getLangSection("es");
		
		if (
			spanishSection != null &&
			// TODO: discuss with the community
			getTemplates("apellido", spanishSection.toString()).isEmpty() &&
			!REDUCED_SECTION_CHECK.test(spanishSection)
		) {
			List<Section> etymologySections = spanishSection.findSubSectionsWithHeader("Etimología.*");
			
			if (etymologySections.size() == 1) {
				if (
					etymologySections.get(0).getLevel() == 3 &&
					!spanishSection.hasSubSectionWithHeader("Traducciones") &&
					!spanishSection.hasSubSectionWithHeader(HAS_FLEXIVE_FORM_HEADER_RE)
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
						!etymologySection.hasSubSectionWithHeader("Traducciones") &&
						// TODO: this won't happen with the current restrictions (see checkFlexiveFormHeaders())
						!etymologySection.hasSubSectionWithHeader(HAS_FLEXIVE_FORM_HEADER_RE)
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
		String summary = "añadiendo secciones: " + String.join(", ", set);
		
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
			getTemplates("listaref", str).isEmpty() &&
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
			!getTemplates("listaref", referencesIntro).isEmpty() ||
			pReferenceTags.matcher(removeCommentsAndNoWikiText(referencesIntro)).find()
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
				!getTemplates("participio", intro).isEmpty() ||
				getTemplates("forma", intro).stream()
					.map(template -> getTemplateParametersWithValue(template))
					.map(params -> params.get("ParamWithoutName2"))
					.filter(Objects::nonNull)
					.anyMatch(param -> param.matches("(?i).*?participio.*"))
			) {
				continue;
			}
			
			List<String> list = new ArrayList<>();
			
			Utils.replaceWithStandardIgnoredRanges(intro, P_INFLECT_TMPLS,
				(m, sb) -> list.add(m.group(1))
			);
			
			String temp = intro;
			
			for (String templateName : list) {
				temp = Utils.replaceTemplates(temp, templateName, match -> "");
			}
			
			if (!temp.equals(intro)) {
				section.setIntro(temp);
			}
		}
		
		String formatted = page.toString();
		checkDifferences(formatted, "removeInflectionTemplates", "eliminando plantillas de flexión");
	}
	
	public void manageAnnotationTemplates() {
		final String annotationTemplateName = "anotación";
		
		if (isOldStructure || getTemplates(annotationTemplateName, text).isEmpty()) {
			return;
		}
		
		// Remove empty templates
		
		String formatted = Utils.replaceTemplates(text, annotationTemplateName, match -> {
			Map<String, String> params = getTemplateParametersWithValue(match);
			params.values().removeIf(String::isEmpty);
			return params.size() > 1 ? match : "";
		});
		
		// Convert annotations to their corresponding templates
		
		Page page = Page.store(title, formatted);
		Set<String> set = new LinkedHashSet<>();
		
		page.getAllLangSections().stream()
			.map(langSection -> langSection.findSubSectionsWithHeader(HAS_FLEXIVE_FORM_HEADER_RE))
			.flatMap(Collection::stream)
			.forEach(section -> processAnnotationTemplates(section, set, annotationTemplateName));
		
		if (!formatted.equals(text)) {
			set.add("eliminando plantillas vacías");
		} else if (set.isEmpty()) {
			return;
		}
		
		formatted = page.toString();
		String summary = String.format("{{%s}} → %s", annotationTemplateName, String.join(", ", set));
		
		checkDifferences(formatted, "manageAnnotationTemplates", summary);
	}
	
	private static void processAnnotationTemplates(Section section, Set<String> set, String templateName) {
		String temp = Utils.replaceTemplates(section.getIntro(), templateName, template -> {
			HashMap<String, String> params = getTemplateParametersWithValue(template);
			
			if (
				// TODO: usually {{uso}} annotations; include as first parameter? ~1160 entries
				// https://es.wiktionary.org/w/index.php?title=suspended&oldid=2630232
				!params.getOrDefault("link", "").isEmpty() ||
				// TODO: disable categorization? ({{uso|anticuado}}); ~60k entries
				params.getOrDefault("tit", params.getOrDefault("tít", "")).equalsIgnoreCase("uso") ||
				!convertAnnotationTemplateParams(params)
			) {
				return template;
			}
			
			String newTemplateName = params.get("templateName");
			String newTemplate = templateFromMap(params) + ".";
			set.add(String.format("{{%s}}", newTemplateName));
			
			if (PRON_TMPLS.contains(newTemplateName)) {
				LangSection langSection = section.getLangSectionParent();
				String langSectionIntro = langSection.getIntro();
				langSectionIntro += "\n" + newTemplate;
				langSection.setIntro(langSectionIntro);
				return "";
			} else {
				return newTemplate;
			}
		});
		
		if (!temp.equals(section.getIntro())) {
			section.setIntro(temp);
		}
	}
	
	private static boolean convertAnnotationTemplateParams(HashMap<String, String> params) {
		String templateType = params.getOrDefault("tit", params.getOrDefault("tít", "relacionado"));
		templateType = templateType.toLowerCase();
		
		if (PRON_TMPLS_ALIAS.contains(templateType)) {
			templateType = PRON_TMPLS.get(PRON_TMPLS_ALIAS.indexOf(templateType));
		} else if (TERM_TMPLS_ALIAS.contains(templateType)) {
			templateType = TERM_TMPLS.get(TERM_TMPLS_ALIAS.indexOf(templateType));
		} else if (
			!PRON_TMPLS.contains(templateType) &&
			!TERM_TMPLS.contains(templateType)
		) {
			return false;
		}
		
		params.put("templateName", templateType);
		params.remove("tit");
		params.remove("tít");
		
		params.values().removeIf(Objects::isNull);
		params.values().removeIf(String::isEmpty);
		
		return true;
	}
	
	public void manageDisambigTemplates() {
		final String disambigTemplateName = "desambiguación";
		
		if (
			isOldStructure ||
			getTemplates(disambigTemplateName, text).isEmpty() ||
			!Page.store(title, text).hasSectionWithHeader(HAS_FLEXIVE_FORM_HEADER_RE)
		) {
			return;
		}
		
		// Remove empty templates
		
		String formatted = Utils.replaceTemplates(text, disambigTemplateName, match -> {
			Map<String, String> params = getTemplateParametersWithValue(match);
			
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
				!getTemplates("pron-graf", content).isEmpty()
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
				
				List<String> templates = getTemplates(templateName, line);
				
				if (templates.isEmpty() || templates.size() > 1) {
					editedLines.add(origLine);
					continue;
				}
				
				Map<String, String> params = getTemplateParametersWithValue(templates.get(0));
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
								containsAny(param1, '{', '}', '<', '>') ||
								(
									containsAny(param1, '(', ')') &&
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
								} else if (containsAny(param1, '[', ']', '/')) {
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
								} else if (containsAny(param1, '[', ']', '/')) {
									editedLines.add(origLine);
									continue linesLoop;
								} else {
									newParams.put("fono", param1);
								}
							} else if (!containsAny(param1, '[', ']', '/')) {
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
						
						newParams.put("pron", "clásico");
						
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
									strip(params.get("ParamWithoutName2"), " ':").matches("(?i)audio") ||
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
						
						if (containsAny(param1, '[', ']', '{', '}', '(', ')')) {
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
			
			if (altParam != null && !containsAny(altParam, '{', '}', '[', ']', '(', ')')) {
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
			
			for (Map.Entry<String, Map<String, String>> entry : tempMap.entrySet()) {
				newMap.putAll(entry.getValue());
			}
			
			editedLines.add(0, templateFromMap(newMap));
			
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
		
		Iterator<Map.Entry<String, String>> iterator = targetMap.entrySet().iterator();
		
		while (iterator.hasNext()) {
			Map.Entry<String, String> entry = iterator.next();
			
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
		
		name = strip(name, " ':");
		
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
			!containsAny(content, '[', ']', '{', '}') ||
			containsAny(content, '<', '>')
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
			
			if (containsAny(term, '[', ']')) {
				Matcher m2 = P_LINK.matcher(term);
				
				if (!m2.matches()) {
					return null;
				}
				
				map.put(param, m2.group(1));
				String trail = m2.group(3);
				
				if (!trail.isEmpty() && containsAny(trail, '(', ')')) {
					Matcher m3 = P_PARENS.matcher(trail);
					
					if (!m3.matches() || containsAny(m3.group(1), '[', ']')) {
						return null;
					} else {
						trail = m3.group(1).trim();
						map.put("nota" + i, m3.group(2));
					}
				} else if (containsAny(trail, '[', ']')) {
					return null;
				}
				
				if (!trail.isEmpty() || (m2.group(2) != null && !m2.group(2).equals(m2.group(1)))) {
					map.put("alt" + i, (m2.group(2) != null ? m2.group(2) : m2.group(1)) + trail);
				}
			} else if (containsAny(term, '{', '}')) {
				Matcher m2 = P_LINK_TMPLS.matcher(term);
				
				if (!m2.matches()) {
					return null;
				}
				
				String template = m2.group(1);
				String trail = m2.group(2);
				
				HashMap<String, String> params = getTemplateParametersWithValue(template);
				
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
			templateFromMap(map) + "." +
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
				getTemplates("pron-graf", langSection.getIntro()).isEmpty()
				// TODO: https://es.wiktionary.org/w/index.php?title=edere&diff=3774065&oldid=3774057
				/*AbstractSection.flattenSubSections(langSection.getChildSections()).stream()
					.allMatch(section -> section.getChildSections().isEmpty())*/
			) {
				String langSectionIntro = langSection.getIntro();
				
				if (getTemplates("pron-graf", langSectionIntro).isEmpty()) {
					langSectionIntro = insertTemplate(langSectionIntro, langCode, "pron-graf", "{{%s}}");
					langSection.setIntro(langSectionIntro);
					set.add("{{pron-graf}}");
				}
			} else if (etymologySections.size() == 1) {
				Section etymologySection = etymologySections.get(0);
				String langSectionIntro = langSection.getIntro();
				String etymologyIntro = etymologySection.getIntro();
				
				if (
					getTemplates("pron-graf", langSectionIntro).isEmpty() &&
					getTemplates("pron-graf", etymologyIntro).isEmpty()
				) {
					langSectionIntro = insertTemplate(langSectionIntro, langCode, "pron-graf", "{{%s}}");
					langSection.setIntro(langSectionIntro);
					set.add("{{pron-graf}}");
				}
				
				if (
					getTemplates("etimología", langSection.getIntro()).isEmpty() &&
					getTemplates("etimología2", langSection.getIntro()).isEmpty() &&
					// TODO: review
					(etymologyIntro.isEmpty() || removeBlockTemplates(etymologyIntro).isEmpty()) &&
					!REDUCED_SECTION_CHECK.test(etymologySection)
				) {
					etymologyIntro = insertTemplate(etymologyIntro, langCode, "etimología", "{{%s}}.");
					etymologySection.setIntro(etymologyIntro);
					set.add("{{etimología}}");
				}
			} else if (etymologySections.size() > 1) {
				for (Section etymologySection : etymologySections) {
					String etymologyIntro = etymologySection.getIntro();
					
					if (
						getTemplates("etimología", langSection.getIntro()).isEmpty() &&
						getTemplates("etimología2", langSection.getIntro()).isEmpty() &&
						(etymologyIntro.isEmpty() || removeBlockTemplates(etymologyIntro).isEmpty()) &&
						!REDUCED_SECTION_CHECK.test(etymologySection)
					) {
						// TODO: ensure that it's inserted after {{pron-graf}}
						etymologyIntro = insertTemplate(etymologyIntro, langCode, "etimología", "{{%s}}.");
						etymologySection.setIntro(etymologyIntro);
						set.add("{{etimología}}");
					}
					
					if (
						getTemplates("pron-graf", langSection.getIntro()).isEmpty() &&
						getTemplates("pron-graf", etymologyIntro).isEmpty()
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
		
		if (
			spanishSection != null &&
			!REDUCED_SECTION_CHECK.test(spanishSection)
		) {
			List<Section> translations = spanishSection.findSubSectionsWithHeader("Traducciones");
			
			if (translations.size() == 1) {
				Section section = translations.get(0);
				String intro = section.getIntro();
				
				if (
					getTemplates("trad-arriba", intro).isEmpty() &&
					getTemplates("trad", intro).isEmpty() &&
					getTemplates("t+", intro).isEmpty() &&
					getTemplates("véase", intro).isEmpty() &&
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
				content = removeCommentsAndNoWikiText(content);
				
				if (!getTemplates(reconstructedTmpl, content).isEmpty()) {
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
		text = removeCommentsAndNoWikiText(text);
		
		List<String> list = new ArrayList<>(PRON_TMPLS.size() + AMBOX_TMPLS.size() + 1);
		list.addAll(PRON_TMPLS);
		list.addAll(AMBOX_TMPLS);
		list.add("pron-graf");
		
		for (String target : list) {
			for (String template : getTemplates(target, text)) {
				text = text.replace(template, "");
			}
		}
		
		text = P_IMAGES.matcher(text).replaceAll("");
		text = P_CATEGORY_LINKS.matcher(text).replaceAll("");
		text = text.replaceAll("<ref\\b.*?(?:/ *?>|>.*?</ref *?>)", "");
		text = text.replaceAll("(?m)^[\\s.,:;*#]*$", "");
		text = text.replace("{{clear}}", "");
		
		return text.trim();
	}
	
	public void checkLangCodeCase() {
		Page page = Page.store(title, text);
		
		// language section templates: {{lengua|xx}}
		
		page.getAllLangSections().stream()
			.filter(langSection -> !langSection.getLangCode(false).equals(langSection.getLangCode(true)))
			.forEach(langSection -> langSection.setLangCode(langSection.getLangCode(true)));
		
		// term section templates: {{sustantivo|xx}}
		
		page.getAllLangSections().stream()
			.map(AbstractSection::getChildSections)
			.map(AbstractSection::flattenSubSections)
			.flatMap(Collection::stream)
			.filter(section -> !section.getHeader().isEmpty())
			.forEach(section -> SECTION_TMPLS.stream()
				.map(template -> getTemplates(template, section.getHeader()))
				.flatMap(Collection::stream)
				.map(template -> getTemplateParametersWithValue(template))
				.filter(params -> !params.getOrDefault("ParamWithoutName1", "").isEmpty())
				.filter(params -> !params.get("ParamWithoutName1").toLowerCase()
					.equals(params.get("ParamWithoutName1"))
				)
				.forEach(params -> {
					params.compute("ParamWithoutName1", (k, v) -> v.toLowerCase());
					String header = Utils.replaceTemplates(
						section.getHeader(),
						params.get("templateName"),
						template -> templateFromMap(params)
					);
					section.setHeader(header);
				})
			);
		
		checkDifferences(page.toString(), "checkLangCodeCase", null);
	}
	
	public void langTemplateParams() {
		// TODO: {{Matemáticas}}, {{mamíferos}}, etc.
		
		Page page = Page.store(title, text);
		
		// {{sinónimo}}, {{derivad}}...
		
		for (LangSection langSection : page.getAllLangSections()) {
			String content = langSection.toString();
			
			for (String templateName : LENG_PARAM_TMPLS) {
				content = Utils.replaceTemplates(content, templateName, template -> {
					HashMap<String, String> params = getTemplateParametersWithValue(template);
					String leng = params.get("leng");
					
					if (templateName.equals("ampliable")) {
						String param1 = params.remove("ParamWithoutName1");
						leng = Optional.ofNullable(leng).orElse(param1);
					}
					
					if (langSection.langCodeEqualsTo("es")) {
						if (leng != null) {
							params.remove("leng");
							return templateFromMap(params);
						} else {
							return template;
						}
					} else if (leng == null) {
						@SuppressWarnings("unchecked")
						Map<String, String> tempMap = (Map<String, String>) params.clone();
						params.clear();
						params.put("templateName", tempMap.remove("templateName"));
						params.put("leng", langSection.getLangCode());
						params.putAll(tempMap);
						return templateFromMap(params);
					} else if (!langSection.langCodeEqualsTo(leng)) {
						params.put("leng", langSection.getLangCode());
						return templateFromMap(params);
					} else {
						return template;
					}
				});
			}
			
			if (!content.equals(langSection.toString())) {
				LangSection newLangSection = LangSection.parse(content);
				langSection.replaceWith(newLangSection);
			}
		}
		
		// section templates: {{sustantivo|xx}}
		
		// TODO: reparse Page?
		page = Page.store(title, page.toString());
		
		page.getAllLangSections().stream()
			.map(AbstractSection::getChildSections)
			.map(AbstractSection::flattenSubSections)
			.flatMap(Collection::stream)
			.filter(section -> !section.getHeader().isEmpty())
			.forEach(section -> SECTION_TMPLS.stream()
				.map(template -> getTemplates(template, section.getHeader()))
				.flatMap(Collection::stream)
				.map(template -> getTemplateParametersWithValue(template))
				.filter(params -> !params.getOrDefault("ParamWithoutName1", "")
					.equalsIgnoreCase(section.getLangSectionParent().getLangCode())
				)
				.forEach(params -> {
					params.put("ParamWithoutName1", section.getLangSectionParent().getLangCode());
					String header = Utils.replaceTemplates(
						section.getHeader(),
						params.get("templateName"),
						template -> templateFromMap(params)
					);
					section.setHeader(header);
				})
			);
		
		String formatted = page.toString();
		checkDifferences(formatted, "langTemplateParams", "códigos de idioma");
	}
	
	public void manageSectionTemplates() {
		Page page = Page.store(title, text);
		
		page.getAllLangSections().stream()
			.map(AbstractSection::getChildSections)
			.map(AbstractSection::flattenSubSections)
			.flatMap(Collection::stream)
			.filter(section -> !section.getHeader().isEmpty())
			.filter(section -> !containsAny(section.getHeader(), '[', ']', '<', '>'))
			.forEach(section -> SECTION_TMPLS.stream()
				.map(template -> getTemplates(template, section.getHeader()))
				.flatMap(Collection::stream)
				.filter(section.getHeader()::startsWith)
				.map(template -> getTemplateParametersWithValue(template))
				// TODO: this line assumes that compound section templates (like {{locución sustantiva}})
				// never accept a first parameter, but there could be some exceptions
				// (see edit history of {{adjetivo numeral}}, {{adjetivo posesivo}} and {{sustantivo propio}}).
				// Once processHeaderTemplates() and addSectionTemplates are properly adapted,
				// add proper parameter handling for these and more future cases.
				// See https://es.wiktionary.org/w/index.php?diff=3772161
				// See TODO in removeCategoryLinks()
				.filter(params -> !SECTION_DATA_MAP.containsKey(params.get("templateName")))
				.forEach(params -> processHeaderTemplates(section, params))
			);
		
		String formatted = page.toString();
		checkDifferences(formatted, "manageSectionTemplates", "revisando plantillas de sección");
	}
	
	private static void processHeaderTemplates(Section section, HashMap<String, String> params) {
		String header = section.getHeader();
		String template = templateFromMap(params);
		String templateName = params.get("templateName");
		
		// {{sustantivo|xx}} masculino -> {{sustantivo|xx|masculino}}
		
		if (
			params.containsKey("ParamWithoutName1") &&
			params.size() == 2 && // ParamWithoutName2 was not set
			!header.equals(template) &&
			header.charAt(template.length()) == ' '
		) {
			String temp = templateName + header.substring(template.length());
			Catgram.Data firstMember = Catgram.Data.queryData(templateName);
			
			String temp2 = Stream.of(Catgram.Data.values())
				.map(secondMember -> Catgram.make(firstMember, secondMember))
				.filter(Objects::nonNull)
				.filter(catgram -> temp.startsWith(catgram.getSingular()))
				.map(catgram -> temp.replaceFirst(catgram.getSingular(), String.format(
					"{{%s|%s|%s}}",
					templateName,
					section.getLangSectionParent().getLangCode(),
					catgram.getSecondMember().getSingular()
				)))
				.findAny()
				.orElse(null);
			
			if (temp2 != null) {
				header = temp2;
				template = getTemplates(templateName, header).get(0);
				params = getTemplateParametersWithValue(template);
			}
		}
		
		// {{sustantivo|xx|masculino}} -> {{sustantivo masculino|xx}}
		
		if (
			params.containsKey("ParamWithoutName2") &&
			!params.containsKey("ParamWithoutName3")
		) {
			Catgram.Data firstMember = Catgram.Data.queryData(templateName);
			Catgram.Data secondMember = Catgram.Data.queryData(params.get("ParamWithoutName2"));
			List<Catgram.Data> list = Arrays.asList(firstMember, secondMember);
			
			String compoundTemplate = SECTION_DATA_MAP.entrySet().stream()
				.filter(entry -> entry.getValue().equals(list))
				.map(Map.Entry::getKey)
				.findAny()
				.orElse(null);
			
			if (compoundTemplate != null) {
				params.put("templateName", compoundTemplate);
				params.remove("ParamWithoutName2");
				header = Pattern.compile(template, Pattern.LITERAL).matcher(header)
					.replaceFirst(templateFromMap(params));
			}
		}
		
		if (!section.getHeader().equals(header)) {
			section.setHeader(header);
		}
	}
	
	public void addSectionTemplates() {
		Page page = Page.store(title, text);
		
		page.getAllLangSections().stream()
			.map(AbstractSection::getChildSections)
			.map(AbstractSection::flattenSubSections)
			.flatMap(Collection::stream)
			.filter(section -> !section.getHeader().isEmpty())
			.filter(section -> !containsAny(section.getHeader(), '{', '}', '[', ']', '<', '>'))
			.forEach(section -> {
				String header = section.getHeader().toLowerCase();
				String langCode = section.getLangSectionParent().getLangCode();
				
				String newHeader = SECTION_DATA_MAP.keySet().stream()
					.filter(header::startsWith)
					.map(key -> header.replaceFirst(key, String.format("{{%s|%s}}", key, langCode)))
					.findAny()
					.orElseGet(() -> SECTION_TMPLS.stream()
						.filter(tmpl -> !SECTION_DATA_MAP.containsKey(tmpl))
						.filter(header::startsWith)
						.map(tmpl -> sectionTemplateMapper(header, langCode, tmpl))
						.filter(Objects::nonNull)
						.findAny()
						.orElse(null)
					);
				
				if (newHeader != null) {
					section.setHeader(newHeader);
				}
			});
		
		String formatted = page.toString();
		checkDifferences(formatted, "addSectionTemplates", "añadiendo plantillas de sección");
	}

	private static String sectionTemplateMapper(String header, String langCode, String template) {
		final String standardTemplate = String.format("{{%s|%s}}", template, langCode);
		
		if (header.equals(template)) {
			return standardTemplate;
		} else if (!header.contains(" ")) {
			return null;
		} else {
			String[] tokens = header.split(" +");
			Catgram.Data firstMember = Catgram.Data.queryData(tokens[0]);
			
			if (firstMember == null) { // should not happen
				return header.replaceFirst(
					tokens[0],
					standardTemplate
				);
			} else {
				return Stream.of(Catgram.Data.values())
					.map(secondMember -> Catgram.make(firstMember, secondMember))
					.filter(Objects::nonNull)
					.filter(catgram -> header.startsWith(catgram.getSingular()))
					.map(catgram -> header.replaceFirst(catgram.getSingular(), String.format(
						"{{%s|%s|%s}}",
						template, langCode, catgram.getSecondMember().getSingular()
					)))
					.findAny()
					.orElse(header.replaceFirst(template, standardTemplate));
			}
		}
	}
	
	public void removeCategoryLinks() {
		// TODO: remove categories inserted for {{Matemáticas}}-like templates
		
		Set<String> targetCategories = new HashSet<>();
		
		BiConsumer<String, String> addString = (code, plural) -> targetCategories
			.add(String.format("%s:%s", code.toUpperCase(), capitalize(plural)));
		
		for (String templateName : SECTION_TMPLS) {
			for (String template : getTemplates(templateName, text)) {
				Map<String, String> map = getTemplateParametersWithValue(template);
				String langCode = map.get("ParamWithoutName1");
				
				if (langCode == null || langCode.isEmpty()) {
					continue;
				}
				
				final Catgram catgram;
				
				// See TODO in manageSectionTemplates()
				if (SECTION_DATA_MAP.containsKey(map.get("templateName"))) {
					List<Catgram.Data> data = SECTION_DATA_MAP.get(map.get("templateName"));
					catgram = Catgram.make(data.get(0), data.get(1));
				} else {
					catgram = Catgram.make(map.get("templateName"), map.get("ParamWithoutName2"));
				}
				
				if (catgram == null) {
					continue;
				}
				
				addString.accept(langCode, catgram.getPlural());
				
				if (catgram.getSecondMember() != null) {
					addString.accept(langCode, catgram.getFirstMember().getPlural());
					
					if (catgram.getFirstMember() == Catgram.Data.PHRASE) {
						addString.accept(langCode, catgram.getSecondMember().getPlural());
					}
				}
			}
		}
		
		if (targetCategories.isEmpty()) {
			return;
		}
		
		String formatted = Utils.replaceWithStandardIgnoredRanges(text, P_CATEGORY_LINKS, (m, sb) -> {
			String content = m.group(1);
			String[] pipeSeparator = content.split("\\|", 0);
			
			if (pipeSeparator.length > 1) {
				String pipe = pipeSeparator[1];
				
				if (pipe.equals(title) || pipe.equals("{{PAGENAME}}")) {
					content = pipeSeparator[0].trim();
				} else {
					return;
				}
			}
			
			if (targetCategories.contains(content)) {
				m.appendReplacement(sb, "");
			}
		});
		
		checkDifferences(formatted, "removeCategoryLinks", "eliminando categorías redundantes");
	}
	
	public void deleteEmptySections() {
		Page page = Page.store(title, text);
		
		if (page.getAllLangSections().isEmpty()) {
			return;
		}
		
		Set<String> set = new HashSet<>();
		
		page.getAllSections().stream()
			.filter(section -> !section.getStrippedHeader().startsWith("Etimología")) // TODO: review
			.filter(section -> section.getChildSections().isEmpty())
			.filter(section -> STANDARD_HEADERS.contains(section.getStrippedHeader()))
			.filter(Editor::isEmptyOrInvisible)
			.forEach(section -> {
				if (!section.getIntro().isEmpty()) {
					Section previousSection = section.previousSection();
					
					if (previousSection == null) {
						return;
					}
					
					String previousIntro = previousSection.getIntro();
					previousIntro += "\n" + section.getIntro();
					previousSection.setIntro(previousIntro);
				}
				
				section.detachOnlySelf();
				set.add(section.getStrippedHeader());
			});
		
		if (set.isEmpty()) {
			return;
		}
		
		String formatted = page.toString();
		String summary = "eliminando secciones vacías: " + String.join(", ", set);
		
		checkDifferences(formatted, "deleteEmptySections", summary);
	}
	
	public void deleteWrongSections() {
		Page page = Page.store(title, text);
		List<LangSection> langSections = page.getAllLangSections();
		
		if (isOldStructure || langSections.isEmpty()) {
			return;
		}
		
		Set<String> set = new HashSet<>();
		
		// empty translations Sections
		
		langSections.stream()
			.filter(langSection -> !langSection.getChildSections().isEmpty())
			.filter(langSection ->
				!langSection.langCodeEqualsTo("es") ||
				(!hasNonFlexiveHeaders(langSection) && hasFlexiveHeaders(langSection)) ||
				REDUCED_SECTION_CHECK.test(langSection)
			)
			.map(langSection -> langSection.findSubSectionsWithHeader("Traducci(ón|ones)"))
			.flatMap(Collection::stream)
			.filter(section -> section.getChildSections().isEmpty())
			.filter(Editor::isEmptyTranslationsSection)
			.peek(dummy -> set.add("Traducciones"))
			.forEach(AbstractSection::detachOnlySelf);
		
		// empty etymology Sections
		
		langSections.stream()
			.filter(langSection -> !langSection.getChildSections().isEmpty())
			.filter(langSection ->
				(!hasNonFlexiveHeaders(langSection) && hasFlexiveHeaders(langSection)) ||
				REDUCED_SECTION_CHECK.test(langSection)
			)
			// TODO: move image and category links to the previous section
			.map(langSection -> langSection.findSubSectionsWithHeader("Etimología"))
			.flatMap(Collection::stream)
			.filter(section -> section.getChildSections().isEmpty())
			.filter(Editor::isEmptyEtymologySection)
			.peek(dummy -> set.add("Etimología"))
			.forEach(AbstractSection::detachOnlySelf);
		
		if (set.isEmpty()) {
			return;
		}
		
		String formatted = page.toString();
		String summary = "eliminando secciones: " + String.join(", ", set);
		
		checkDifferences(formatted, "deleteWrongSections", summary);
	}
	
	private static boolean hasFlexiveHeaders(Section section) {
		return section.getChildSections().stream()
			.map(AbstractSection::getStrippedHeader)
			.anyMatch(header -> header.matches(HAS_FLEXIVE_FORM_HEADER_RE));
	}
	
	private static boolean hasNonFlexiveHeaders(Section section) {
		return section.getChildSections().stream()
			.map(AbstractSection::getStrippedHeader)
			.anyMatch(header ->
				!STANDARD_HEADERS.contains(header) &&
				!header.matches(HAS_FLEXIVE_FORM_HEADER_RE)
			);
	}
	
	private static boolean isEmptyTranslationsSection(Section section) {
		String intro = section.getIntro();
		intro = removeCommentsAndNoWikiText(intro);
		intro = intro.replaceAll("\\{\\{trad-(arriba|centro|abajo)\\}\\}", "");
		intro = intro.replace("{{clear}}", "");
		intro = intro.trim();
		return intro.isEmpty();
	}
	
	private static boolean isEmptyEtymologySection(Section section) {
		String intro = section.getIntro();
		intro = removeCommentsAndNoWikiText(intro);
		intro = intro.replaceAll("\\{\\{etimología2?(\\|leng=[\\w-]+?)?\\}\\}\\.?", "");
		intro = intro.replace("{{clear}}", "");
		intro = intro.trim();
		return intro.isEmpty();
	}

	public void removeEtymologyTemplates() {
		Page page = Page.store(title, text);
		List<LangSection> langSections = page.getAllLangSections();
		
		if (isOldStructure || langSections.isEmpty()) {
			return;
		}
		
		Stream.concat(
			// no etymology Section
			langSections.stream()
				.filter(langSection -> !langSection.getChildSections().isEmpty())
				.filter(langSection -> langSection.findSubSectionsWithHeader("Etimología.*").isEmpty()),
			// two or more etymology Sections
			langSections.stream()
				.map(langSection -> langSection.findSubSectionsWithHeader("Etimología \\d+"))
				.flatMap(Collection::stream)
		)
		.filter(section ->
			(!hasNonFlexiveHeaders(section) && hasFlexiveHeaders(section)) ||
			REDUCED_SECTION_CHECK.test(section)
		)
		.filter(section ->
			!getTemplates("etimología", section.getIntro()).isEmpty() ||
			!getTemplates("etimología2", section.getIntro()).isEmpty()
		)
		.forEach(Editor::processEtymologyTemplates);
		
		String formatted = page.toString();
		checkDifferences(formatted, "removeEtymologyTemplates", "eliminando plantillas de etimología");
	}
	
	private static void processEtymologyTemplates(Section section) {
		String temp = Utils.replaceWithStandardIgnoredRanges(section.getIntro(), P_ETYM_TMPL, (m, sb) -> {
			String line = m.group(1);
			String trailingText = m.group(2);
			String template = line;
			
			if (!trailingText.isEmpty()) {
				if (trailingText.equals(".")) {
					template = line.substring(0, line.lastIndexOf(trailingText));
				} else {
					return;
				}
			}
			
			Map<String, String> params = getTemplateParametersWithValue(template);
			String templateName = params.remove("templateName");
			params.remove("leng");
			params.values().removeIf(String::isEmpty);
			String param1 = params.get("ParamWithoutName1");
			
			if (params.isEmpty()) {
				m.appendReplacement(sb, "");
			} else if (
				templateName.equals("etimología") &&
				param1 != null &&
				(param1.equals("plural") || param1.equals("femenino") || param1.equals("sufijo"))
			) {
				m.appendReplacement(sb, "");
			} else if (
				templateName.equals("etimología2") &&
				param1 != null &&
				P_FLEX_ETYM.matcher(param1).matches()
			) {
				m.appendReplacement(sb, "");
			} else {
				return;
			}
		});
		
		if (!temp.equals(section.getIntro())) {
			section.setIntro(temp);
		}
	}
	
	public void manageClearElements() {
		if (isOldStructure || Page.store(title, text).getAllLangSections().isEmpty()) {
			return;
		}
		
		String initial = removeBrTags(text);
		Page page = Page.store(title, initial);
		
		final String[] arr = {"{{arriba", "{{trad-arriba", "{{rel-arriba", "{{derivados"};
		
		for (Section section : page.getAllSections()) {
			Section nextSection = section.nextSection();
			
			if (nextSection == null) {
				break;
			}
			
			String sectionIntro = section.getIntro();
			String sanitizedNextSectionIntro = removeCommentsAndNoWikiText(nextSection.getIntro());
			
			if (
				startsWithAny(sanitizedNextSectionIntro, arr) ||
				(
					nextSection.getStrippedHeader().matches("Etimología \\d+") &&
					!nextSection.getStrippedHeader().equals("Etimología 1")
				)
			) {
				List<String> templates = getTemplates("clear", sectionIntro);
				String sanitizedSectionIntro = removeCommentsAndNoWikiText(sectionIntro);
				
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
		
		for (Section section : sections) {
			String intro = section.getIntro();
			
			String temp = Utils.replaceWithStandardIgnoredRanges(intro, P_UCF, (m, sb) -> {
				String target = m.group(2).trim().replaceFirst("#Español$", "");
				String pipe = m.group(3);
				String trail = m.group(4);
				
				if (target.substring(0, 1).equals(target.substring(0, 1).toUpperCase())) {
					return;
				}
				
				if (pipe != null) {
					pipe = pipe.trim();
					
					if (
						pipe.isEmpty() ||
						!(pipe.substring(0, 1).toLowerCase() + pipe.substring(1)).equals(target)
					) {
						return;
					}
				}
				
				if (trail.matches("^[\\wáéíóúüñÁÉÍÓÚÜÑ]+.*")) {
					return;
				}
				
				String template = target.equals(title)
					? "{{plm}}"
					: String.format("{{plm|%s}}", target);
				
				String replacement = intro.substring(m.start(), m.start(1)) + template + trail;
				
				m.appendReplacement(sb, replacement);
			});
			
			if (!temp.equals(intro)) {
				section.setIntro(temp);
			}
		}
		
		String formatted = page.toString();
		checkDifferences(formatted, "applyUcfTemplates", "convirtiendo enlaces a {{plm}}");
	}
	
	public void sanitizeReferences() {
		Document doc = Jsoup.parseBodyFragment(text);
		doc.outputSettings().prettyPrint(false);
		Elements refs = doc.select("ref[name]").not("[group]");
		
		if (refs.isEmpty() || (
			doc.getElementsByTag("references").size() +
			getTemplates("título referencias", text).size() > 1
		)) {
			return;
		}
		
		refs.stream()
			.collect(Collectors.groupingBy(
				ref -> ref.attr("name"),
				LinkedHashMap::new,
				Collectors.toCollection(Elements::new)
			))
			.values().stream()
			.filter(elements -> elements.size() > 1)
			.filter(elements -> elements.stream().allMatch(el -> !el.tag().isSelfClosing()))
			.filter(elements -> elements.stream().map(Element::html).distinct().count() > 1)
			.forEach(elements -> elements.stream()
				.collect(Collectors.groupingBy(
					Element::html,
					LinkedHashMap::new,
					Collectors.toCollection(Elements::new)
				))
				.values().stream()
				.filter(els -> els.size() == 1) // let groupReferences() handle the rest
				.forEach(els -> els.removeAttr("name"))
			);
		
		String formatted = Utils.decodeHtmlDocument(doc);
		checkDifferences(formatted, "sanitizeReferences", "corrigiendo referencias");
	}
	
	public void groupReferences() {
		Document doc = Jsoup.parseBodyFragment(text);
		doc.outputSettings().prettyPrint(false);
		
		Elements refs = doc.getElementsByTag("ref").stream()
			.filter(ref -> !ref.tag().isSelfClosing())
			.filter(ref -> !ref.hasAttr("group"))
			.filter(ref -> !ref.html().isEmpty())
			.collect(Collectors.toCollection(Elements::new));
		
		if (refs.isEmpty() || (
			doc.getElementsByTag("references").size() +
			getTemplates("título referencias", text).size() > 1
		)) {
			return;
		}
		
		MutableInt refId = new MutableInt(1);
		
		// multiple non-empty <ref> tags with the same content
		refs.stream()
			.collect(Collectors.groupingBy(
				Element::html,
				LinkedHashMap::new,
				Collectors.toCollection(Elements::new)
			))
			.values().stream()
			.filter(elements -> elements.size() > 1)
			.forEach(elements -> {
				final String name;
				String temp = elements.attr("name");
				
				if (!temp.isEmpty()) {
					// pick the first non-empty "name" attribute as a candidate
					Elements els = doc.select(String.format("ref[name=%s]", temp));
					els.removeIf(elements::contains);
					
					if (els.removeIf(el -> el.tag().isSelfClosing()) && !els.isEmpty()) {
						// there were already some self-closing <ref>s with that "name" attribute
						return;
					} else if (!els.isEmpty()) {
						// other non-self-closing <refs> present, generate a new attribute
						name = String.format("auto_ref_id_%d", refId.intValue());
						refId.increment();
					} else {
						name = temp;
					}
				} else {
					// generate a new "name" attribute
					name = String.format("auto_ref_id_%d", refId.intValue());
					refId.increment();
				}
				
				// <ref>s with a differente "name" attribute to be discarded
				boolean abort = elements.stream()
					.filter(el -> el.hasAttr("name") && !el.attr("name").equals(name))
					// quotes are mandatory since el.attr("name") might be empty
					.map(el -> doc.select(String.format("ref[name=\"%s\"]", el.attr("name"))))
					.anyMatch(els -> {
						// select those with a different content
						els.removeIf(elements::contains);
						
						if (els.removeIf(el -> !el.tag().isSelfClosing()) && !els.isEmpty()) {
							// ambiguous, cannot convert to the new name
							return true;
						} else {
							// only self-closing tags
							els.forEach(el -> el.attr("name", name));
							return false;
						}
					});
				
				if (abort) {
					return;
				}
				
				elements.attr("name", name);
				elements.remove(0);
				
				// hacky, convert all <refs> but the first one to self-closing tags
				elements.forEach(el -> el.after(String.format("<ref%s />", el.attributes())));
				elements.forEach(Element::remove);
			});
		
		String formatted = Utils.decodeHtmlDocument(doc);
		checkDifferences(formatted, "groupReferences", "agrupando referencias");
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
		String strippedPageIntro = removeCommentsAndNoWikiText(pageIntro);
		
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
		
		String strippedPageIntro = removeCommentsAndNoWikiText(pageIntro);
		
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
			
			if (section.getLeadingNewlines() == 1) {
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
		ESWikt wb = Login.retrieveSession(Domains.ESWIKT, Users.USER2);
		
		String text;
		String title = "v";
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
