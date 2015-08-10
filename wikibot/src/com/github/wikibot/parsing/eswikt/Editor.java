package com.github.wikibot.parsing.eswikt;

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
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.LoginException;

import org.apache.commons.lang3.StringUtils;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.ESWikt;
import com.github.wikibot.parsing.EditorBase;
import com.github.wikibot.parsing.SectionBase;
import com.github.wikibot.parsing.Utils;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public class Editor extends EditorBase {
	private static final Pattern P_XX_ES_TEMPLATE = Pattern.compile("\\{\\{ *?.+?-ES( *?\\| *?(\\{\\{.+?\\}\\}|.*?)+)*?\\}\\}", Pattern.DOTALL);
	private static final Pattern P_OLD_STRUCT_HEADER = Pattern.compile("^(.*?)(\\{\\{ *?(?:ES|.+?-ES|TRANSLIT)(?: *?\\| *?(?:\\{\\{.+?\\}\\}|.*?)+)*?\\}\\}) *(.*)$", Pattern.MULTILINE);
	private static final Pattern P_ADAPT_PRON_TMPL;
	private static final Pattern P_AMBOX_TMPLS;
	private static final Pattern P_TMPL_LINE = Pattern.compile("^:*?\\* *?('{0,3}.+?:'{0,3})(.+?)(?: *?\\.)?$", Pattern.MULTILINE);
	private static final Pattern P_IMAGES = Pattern.compile(" *?\\[\\[ *?(File|Image|Archivo|Imagen) *?:.+\\]\\]", Pattern.CASE_INSENSITIVE);
	private static final Pattern P_COMMENTS = Pattern.compile(" *?<!--.+-->");
	
	private static final List<String> LENG_PARAM_TMPLS = Arrays.asList(
		"etimología", "etimología2", "transliteración", "homófono", "grafía alternativa", "variantes",
		"parónimo", "sinónimo", "antónimo", "hiperónimo", "hipónimo", "uso", "ámbito", "apellido",
		"doble conjugación", "derivad", "grafía", "pron-graf", "rima", "relacionado", "pronunciación",
		"diacrítico", "ampliable"
	);
	
	private static final List<String> PRON_TMPLS = Arrays.asList(
		"pronunciación", "pron.la",  "audio", "transliteración", "homófono",
		"grafía alternativa", "variantes", "parónimo", "diacrítico"
	);
	
	private static final List<String> PRON_TMPLS_ALIAS = Arrays.asList(
		null, null, null, "transliteraciones", "homófonos", "grafías alternativas", "variante", "parónimos", null
	);
	
	private static final List<String> TERM_TMPLS = Arrays.asList(
		"ámbito", "uso", "sinónimo", "antónimo", "hipónimo", "hiperónimo", "relacionado", "anagrama", "derivado"
	);
	
	private static final List<String> TERM_TMPLS_ALIAS = Arrays.asList(
		null, null, "sinónimos", "antónimos", "hipónimos", "hiperónimos", "relacionados", "anagramas", "derivados"
	);
	
	private static final List<String> SPANISH_PRON_TMPL_PARAMS = Arrays.asList(
		"y", "ll", "s", "c", "ys", "yc", "lls", "llc"
	);
	
	private static final List<String> STANDARD_HEADERS = new ArrayList<String>();
	
	private static final String TRANSLATIONS_TEMPLATE;
	
	private boolean isOldStructure;
	
	static {
		P_ADAPT_PRON_TMPL = Pattern.compile("^[:\\*]*? *?\\{\\{ *?(" + String.join("|", PRON_TMPLS) + ") *?(?:\\|[^\\{]*?)?\\}\\}\\.?$");
		
		// https://es.wiktionary.org/wiki/Categor%C3%ADa:Wikcionario:Plantillas_de_mantenimiento
		final List<String> amboxTemplates = Arrays.asList(
			"ampliable", "creado por bot", "definición", "discutido", "esbozo", "stub", "estructura", "formato",
			"falta", "revisión", "revisar"
		);
		
		P_AMBOX_TMPLS = Pattern.compile(" *?\\{\\{ *?(" + String.join("|", amboxTemplates) + ") *?(?:\\|.*)?\\}\\}( *?<!--.+?-->)*", Pattern.CASE_INSENSITIVE);
		
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
		normalizeTemplateNames();
		transformToNewStructure();
		normalizeSectionHeaders();
		substituteReferencesTemplate();
		duplicateReferencesSection();
		moveReferencesSection();
		normalizeSectionLevels();
		removePronGrafSection();
		sortLangSections();
		addMissingSections();
		sortSubSections();
		removeInflectionTemplates();
		adaptPronunciationTemplates();
		convertToTemplate();
		addMissingElements();
		langTemplateParams();
		manageClearElements();
		strongWhitespaces();
		weakWhitespaces();
	}
	
	public void removeComments() {
		String formatted = this.text;
		
		formatted = formatted.replaceAll("<!--( *?|\n*?)-->", "");
		formatted = formatted.replaceAll("<!-- ?si hay términos que se diferencian .+?-->", "");
		formatted = formatted.replaceAll("<!---? ?Añádela en el Alfabeto Fonético Internacional.+?-->", "");
		formatted = formatted.replaceAll("<!---? ?Añade la pronunciación en el Alfabeto Fonético Internacional.+?-->", "");
		formatted = formatted.replaceAll("<!-- ?A(ñ|n)ádela con el siguiente patrón.+?-->", "");
		formatted = formatted.replaceAll("<!-- ?Añade la etimología con el siguiente patrón.+?-->", "");
		formatted = formatted.replaceAll("(?s)<!-- ?Escoge la plantilla adecuada .+?-->", "");
		formatted = formatted.replaceAll("(?s)<!-- ?Utiliza cualquiera de las siguientes plantillas .+?-->", "");
		formatted = formatted.replaceAll("<!-- ?explicación de lo que significa la palabra -->", "");
		formatted = formatted.replaceAll("<!-- ?(; )?si pertenece a un campo semántico .+?-->", "");
		formatted = formatted.replaceAll("<!-- ?(;2: )?si hay más acepciones.+?-->", "");
		formatted = formatted.replaceAll("(?s)<!-- ?puedes incluir uno o más de los siguientes campos .+?-->", "");
		formatted = formatted.replaceAll("<!-- ?\\{\\{ámbito(\\|leng=xx)?\\|<ÁMBITO 1>\\|<ÁMBITO2>\\}\\}.*?-->", "");
		formatted = formatted.replaceAll("<!-- ?\\{\\{uso(\\|leng=xx)?\\|\\}\\}.+?-->", "");
		formatted = formatted.replaceAll("<!-- ?\\{\\{sinónimo(\\|leng=xx)?\\|<(SINÓNIMO )?1>\\|<(SINÓNIMO )?2>\\}\\}.*?-->", "");
		formatted = formatted.replaceAll("<!-- ?\\{\\{antónimo(\\|leng=xx)?\\|<(ANTÓNIMO )?1>\\|<(ANTÓNIMO )?2>\\}\\}.*?-->", "");
		formatted = formatted.replaceAll("<!-- ?\\{\\{hipónimo(\\|leng=xx)?\\|<(HIPÓNIMO )?1>\\|<(HIPÓNIMO )?2>\\}\\}.*?-->", "");
		formatted = formatted.replaceAll("<!-- ?\\{\\{hiperónimo(\\|leng=xx)?\\|<(HIPERÓNIMO )?1>\\|<(HIPERÓNIMO )?2>\\}\\}.*?-->", "");
		formatted = formatted.replaceAll("<!-- ?\\{\\{relacionado(\\|leng=xx)?\\|<1>\\|<2>\\}\\}.*?-->", "");
		formatted = formatted.replaceAll("<!-- ?\\{\\{ejemplo\\|<oración.+?-->", "");
		formatted = formatted.replaceAll("<!-- ?\\{\\{ejemplo\\}\\} ?-->", "");
		formatted = formatted.replaceAll("<!-- ?aquí pones una explicaci(ó|o)n .+?-->", "");
		formatted = formatted.replaceAll("<!-- ?aquí escribes una explicaci(ó|o)n .+?-->", "");
		formatted = formatted.replaceAll("<!-- ?si tienes información adicional.+?-->", "");
		formatted = formatted.replaceAll("(?s)<!-- ?Puedes también incluir las siguientes secciones.+?-->", "");
		formatted = formatted.replaceAll("<!-- ?\\{\\{etimología\\|IDIOMA.+?-->", "");
		formatted = formatted.replaceAll("<!-- ?posiblemente desees incluir una imagen.+?-->", "");
		formatted = formatted.replaceAll("<!-- ?si se trata de un país.+?-->", "");
		formatted = formatted.replaceAll("(?s)<!-- ?puedes también incluir locuciones.+?-->", "");
		formatted = formatted.replaceAll("(?s)<!-- ?Incluir la plantilla de conjugación aquí.+?-->", "");
		formatted = formatted.replaceAll("(?s)<!-- ?otra sección opcional para enlaces externos.+?-->", "");
		formatted = formatted.replaceAll("<!-- ?¿flexión?: mira en .+?-->", "");
		formatted = formatted.replaceAll("<!-- ?\\{\\{inflect.sust.sg-pl\\|AQUÍ EL SINGULAR.+?-->", "");
		formatted = formatted.replaceAll("<!-- ?\\{\\{pronunciación\\|\\[ (ˈ|eˈxem.plo) \\]\\}\\}.+?-->", "");
		formatted = formatted.replaceAll("<!-- ?\\{\\{pronunciación\\|\\[.+?\\]\\}\\} \\|-\\|c=.+?\\|s=.+?\\}\\} *?-->", "");
		formatted = formatted.replaceAll("<!-- ?en general, no se indica la etimología .+?-->", "");
		formatted = formatted.replaceAll("<!-- ?\\{\\{pronunciación\\|\\}\\} ?-->", "");
		formatted = formatted.replaceAll("<!-- ?si vas a insertar una nueva sección de etimología o de idioma.+?-->", "");
		formatted = formatted.replaceAll("<!-- ?si se trata de un país,? por favor,? pon.+?-->", "");
		formatted = formatted.replaceAll("<!-- *?apellidos .+?-->", "");
		formatted = formatted.replaceAll("<!-- *?antropónimos .+?-->", "");
		formatted = formatted.replaceAll("<!-- *?tipo de palabra, por ejemplo .+?-->", " "); // whitespace here is mandatory!
		formatted = formatted.replaceAll("<!-- *?o femeninos]].*?-->", "");
		// TODO: catch open comment tags in arbitrary Sections - [[Especial:PermaLink/2709606]]
		formatted = formatted.replaceAll("<!--\\s*$", "");
		
		formatted = Utils.sanitizeWhitespaces(formatted);
		
		checkDifferences(formatted, "removeComments", "eliminando comentarios");
	}
	
	public void minorSanitizing() {
		// TODO: perrichines (comment close tag)
		// TODO: trailing period after {{etimología}}
	}
	
	public void normalizeTemplateNames() {
		String formatted = this.text;
		Map<String, String> map = new HashMap<String, String>(100);
		
		// TODO: expand per [[Especial:TodasLasRedirecciones]]
		// TODO: delete obsolete templates
		
		map.put("Pronunciación", "pronunciación");
		map.put("Etimología", "etimología");
		map.put("etimologia", "etimología");
		map.put("Desambiguación", "desambiguación");
		map.put("grafías alternativas", "grafía alternativa");
		//map.put("ucf", "plm");
		map.put("Anagramas", "anagrama");
		map.put("Parónimos", "parónimo");
		
		map.put("DRAE1914", "DLC1914");
		map.put("DUE", "MaríaMoliner");
		map.put("Moliner", "MaríaMoliner");
		map.put("NDLC1866", "Labernia1866");
		map.put("dlc1914", "DLC1914");
		map.put("dme1831", "DME1831");
		map.put("dme1864", "DME1864");
		map.put("dp2002", "DP2002");
		map.put("drae1914", "DLC1914");
		
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
		map.put("PROTOPOLINESIO-ES", "Protopolinesio-ES");
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
		
		List<String> found = new ArrayList<String>();
		
		for (Entry<String, String> entry : map.entrySet()) {
			String target = entry.getKey();
			String replacement = entry.getValue();
			StringBuffer sb = new StringBuffer();
			Pattern patt = Pattern.compile("\\{\\{ *?" + target + " *?(\\|(?:\\{\\{.+?\\}\\}|.*?)+)?\\}\\}", Pattern.DOTALL);
			Matcher m = patt.matcher(formatted);
			
			while (m.find()) {
				m.appendReplacement(sb, "{{" + replacement + "$1}}");
			}
			
			if (sb.length() != 0) {
				m.appendTail(sb);
				formatted = sb.toString();
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

	public void transformToNewStructure() {
		String formatted = this.text;
		Page page = Page.store(title, formatted);
		
		if (!isOldStructure || page.hasSectionWithHeader("^(F|f)orma .+")) {
			return;
		}
		
		// Remove {{transic}} template
		
		formatted = formatted.replaceAll("\\{\\{ *?transic *?\\}\\}\n?", "");
		
		// Process headers
		
		Matcher m = P_OLD_STRUCT_HEADER.matcher(formatted);
		StringBuffer sb = new StringBuffer();
		String currentSectionLang = "";
		
		while (m.find()) {
			currentSectionLang = findOldStructureMatch(m, sb, currentSectionLang);
		}
		
		m.appendTail(sb);
		formatted = sb.toString();
		
		if (formatted.equals(this.text)) {
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
		
		for (Section section : page.findSectionsWithHeader("(<small *?>)? *?Referencias.*?(<small *?/ *?>)?")) {
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
		
		for (Section section : page.filterSections(s -> s.getHeader().startsWith("ETYM"))) {
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
		
		Predicate<Section> pred = s -> s instanceof LangSection || s.getHeader().startsWith("ETYM");
		
		for (Section section : page.filterSections(pred)) {
			Section nextSibling = section.nextSiblingSection();
			boolean isSingleEtym = section instanceof LangSection && (
				nextSibling == null || 
				!nextSibling.getHeader().startsWith("ETYM")
			);
			
			List<Section> etymologySections = section.findSubSectionsWithHeader("(E|e)timolog(í|i)a.*");
			
			if (etymologySections.isEmpty()) {
				Section etymologySection = Section.create("Etimología", 3);
				etymologySection.setTrailingNewlines(1);
				
				if (isSingleEtym) {
					singleEtym(section, etymologySection);
				} else {
					multipleEtym(section, etymologySection);
				}
				
				section.prependSections(etymologySection);
				
				if (!(section instanceof LangSection)) {
					section.detachOnlySelf();
				}
			} else if (
				etymologySections.size() == 1 &&
				etymologySections.get(0) == section.nextSection() && !(
					section.getIntro().contains("etimología") &&
					etymologySections.get(0).getIntro().contains("etimología")
				)
			) {
				Section etymologySection = etymologySections.get(0);
				etymologySection.setHeader("Etimología");
				etymologySection.setLevel(3);
				
				if (isSingleEtym) {
					singleEtym(section, etymologySection);	
				} else {
					multipleEtym(section, etymologySection);
				}
				
				if (!(section instanceof LangSection)) {
					section.detachOnlySelf();
				}
			} else {
				insertStructureComment(page);
				return;
			}
		}
		
		page.normalizeChildLevels();
		
		// Check section levels and header numbers
		
		for (LangSection langSection : page.getAllLangSections()) {
			List<Section> etymologySections = langSection.findSubSectionsWithHeader("Etimología.*");
			
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
				
				for (int i = 1; i < etymologySections.size(); i++) {
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

	private String findOldStructureMatch(Matcher m, StringBuffer sb, String currentSectionLang) {
		String pre = m.group(1);
		String template = m.group(2);
		String post = m.group(3);
		
		HashMap<String, String> params = ParseUtils.getTemplateParametersWithValue(template);
		String name = params.get("templateName");
		
		if (name.equals("lengua") || name.equals("translit")) {
			m.appendReplacement(sb, m.group());
			return params.get("ParamWithoutName1");
		}
		
		String altGraf = params.getOrDefault("ParamWithoutName1", "");
		
		if (name.equals("TRANSLIT")) {
			params.put("templateName", "translit");
			name = params.get("ParamWithoutName2");
		} else {
			name = name.replace("-ES", "");
			params.put("templateName", "lengua");
			params.put("ParamWithoutName1", name);
		}
		
		String etym = "";
		etym = params.getOrDefault("ParamWithoutName2", etym);
		etym = params.getOrDefault("num", etym);
		etym = params.getOrDefault("núm", etym);
		
		if (!etym.isEmpty()) {
			params.remove("ParamWithoutName2");
			params.remove("num");
			params.remove("núm");
		}
		
		if (!currentSectionLang.equals(name) && !etym.equals("1")) {
			etym = "";
		}
		
		if (
			!altGraf.isEmpty() && !altGraf.equals("{{PAGENAME}}") &&
			!altGraf.replace("ʼ", "'").equals(title.replace("ʼ", "'"))
		) {
			params.put("alt", altGraf);
		} else {
			altGraf = "";
		}
		
		pre = pre.isEmpty() ? "" : "$1\n";
		post = (post.isEmpty() || post.matches("<!--.+?-->")) ? "" : "\n$3";
		
		if (!etym.isEmpty() && !etym.equals("1")) {
			String replacement = String.format("%s=ETYM%s alt-%s=%s", pre, etym, altGraf, post);
			m.appendReplacement(sb, replacement);
		} else {
			String newTemplate = ParseUtils.templateFromMap(params);
			String replacement = String.format("%s=%s=%s", pre, newTemplate, post);
			m.appendReplacement(sb, replacement);
		}
		
		return name;
	}

	private void extractAltParameter(Section section, String alt) {
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
			HashMap<String, String> params = new LinkedHashMap<String, String>();
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

	private void insertAltComment(Section section, String alt) {
		String comment = String.format("<!-- NO EDITAR: alt=%s -->", alt);
		section.setIntro(comment + "\n" + section.getIntro());
	}

	private void insertStructureComment(Page page) {
		String pageIntro = page.getIntro();
		pageIntro += "\n{{estructura}}";
		page.setIntro(pageIntro);
		checkDifferences(page.toString(), "transformToNewStructure", "{{estructura}}");
	}

	private void singleEtym(Section topSection, Section etymologySection) {
		// Move etymology template to the etymology section
		
		Pattern patt = Pattern.compile("\n?((?:.*?\\{\\{|:*?\\* *?'{0,3})(?:e|E)timología[^\n]+)");
		String topIntro = topSection.getIntro();
		Matcher m = patt.matcher(topIntro);
		List<String> temp = new ArrayList<String>();
		
		while (m.find()) {
			temp.add(m.group(1).trim());
			topIntro = topIntro.substring(0, m.start()) + topIntro.substring(m.end());
			m = patt.matcher(topIntro);
		}
		
		if (!temp.isEmpty()) {
			topSection.setIntro(topIntro);
			String etymologyIntro = etymologySection.getIntro();
			etymologyIntro += String.join("\n\n", temp);
			etymologySection.setIntro(etymologyIntro);
		}
	}

	private void multipleEtym(Section topSection, Section etymologySection) {
		String etymologyIntro = etymologySection.getIntro();
		etymologyIntro += topSection.getIntro() + "\n" + etymologyIntro;
		etymologySection.setIntro(etymologyIntro);
		
		if (topSection instanceof LangSection) {
			topSection.setIntro("");
			topSection.setTrailingNewlines(1);
		}
		
		// Search for the etymology template and move it to the last line
		
		String[] lines = etymologyIntro.split("\n");
		
		if (lines.length < 2) {
			return;
		}
		
		int lineNumber = 0;
		boolean found = false;
		
		for (; lineNumber < lines.length; lineNumber++) {
			String line = lines[lineNumber];
			
			if (
				line.matches(".*?\\{\\{(e|E)timología.+") ||
				line.matches(":*?\\* *?'{0,3}(E|e)timolog(í|i)a:'{0,3}.+")
			) {
				found = true;
				break;
			}
		}
		
		if (found && lineNumber != lines.length - 1) {
			String line = lines[lineNumber];
			lines[lineNumber] = null;
			List<String> list = Stream.of(lines)
				.filter(Objects::nonNull)
				.collect(Collectors.toList());
			list.add(line);
			etymologySection.setIntro(String.join("\n", list));
		}
	}
	
	public void normalizeSectionHeaders() {
		Page page = Page.store(title, this.text);
		
		for (Section section : page.getAllSections()) {
			if (section instanceof LangSection) {
				continue;
			}
			
			String header = section.getHeader();
			
			header = StringUtils.strip(header, "=").trim();
			
			header = header.replaceAll("^(?:e|E)timolog(?:i|í)a ?(\\d)?$", "Etimología $1").trim();
			// TODO: don't confuse with {{locución}}, {{refrán}}
			header = header.replaceAll("^(?:L|l)ocuciones$", "Locuciones");
			header = header.replaceAll("^(?:(?:R|r)efranes|(?:D|d)ichos?)$", "Refranes");
			header = header.replaceAll("^(?:c|C)onjugaci(?:ó|o)n$", "Conjugación");
			header = header.replaceAll("^(?:I|i)nformaci(?:ó|o)n (?:adicional|avanzada)$", "Información adicional");
			header = header.replaceAll("^(?:V|v)er tambi(?:é|e)n$", "Véase también");
			header = header.replaceAll("^(?:V|v)(?:é|e)ase tambi(?:é|e)n$", "Véase también");
			
			header = header.replaceAll("^Proverbio$", "Refrán");
			
			// TODO: https://es.wiktionary.org/w/index.php?title=klei&oldid=2727290
			LangSection langSection = section.getLangSectionParent();
			
			if (langSection != null) {
				if (langSection.getLangName().equals("español")) {
					header = header.replaceAll("^(?:T|t)raducci(?:ó|o)n(es)?$", "Traducciones");
				} else {
					header = header.replaceAll("^(?:T|t)raducci(?:ó|o)n(es)?$", "Traducción");
				}
			}
			
			if (!isOldStructure) {
				header = header.replaceAll("^(?:<small> *?)?(?:R|r)eferencias?.*?$", "Referencias y notas");
			}
			
			section.setHeader(header);
		}
		
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
					etymologySection.setHeader(String.format("Etimología %d", i + 1));
				}
			}
		}
		
		String formatted = page.toString();
		
		checkDifferences(formatted, "normalizeSectionHeaders", "normalizando títulos de encabezamiento");
	}
	
	public void substituteReferencesTemplate() {
		if (isOldStructure) {
			return;
		}
		
		Page page = Page.store(title, this.text);
		String template = "{{título referencias}}";
		List<String> contents = new ArrayList<String>();
		boolean found = false;
		
		List<Section> sections = page.getAllSections();
		ListIterator<Section> iterator = sections.listIterator(sections.size());
		
		while (iterator.hasPrevious()) {
			Section section = iterator.previous();
			String intro = section.getIntro();
			intro = intro.replace("{{tit ref}}", String.format("{{%s}}", template));
			int index = intro.indexOf(template);
			
			if (index != -1) {
				found = true;
				String content = intro.substring(index + template.length()).trim();
				
				if (!content.isEmpty()) {
					contents.add(content);
				}
				
				intro = intro.substring(0, index).trim();
				section.setIntro(intro);
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
		Page page = Page.store(title, this.text);
		
		Section bottomReferences = page.getReferencesSection();
		List<Section> allReferences = page.findSectionsWithHeader("^(R|r)eferencias.*");
		
		if (isOldStructure || allReferences.size() < 2) {
			return;
		}
		
		Iterator<Section> iterator = allReferences.iterator();
		
		while (iterator.hasNext()) {
			Section section = iterator.next();
			List<Section> childSections = section.getChildSections();
			List<String> subSectionHeaders;
			
			if (childSections != null) {
				List<Section> flattenedSubSections = SectionBase.flattenSubSections(childSections);
				subSectionHeaders = flattenedSubSections.stream()
					.map(SectionBase::getHeader)
					.collect(Collectors.toList());
			} else {
				subSectionHeaders = new ArrayList<String>();
			}
			
			if (
				childSections == null ||
				STANDARD_HEADERS.containsAll(subSectionHeaders)
			) {
				String content = section.getIntro();
				content = content.replaceAll("(?s)<!--.*?-->", ""); 
				content = content.replaceAll("<references *?/ *?>", "");
				content = content.trim();
				
				// TODO: handle non-empty sections, too
				if (content.isEmpty()) {
					section.detachOnlySelf();
					iterator.remove();
				}
			}
		}
		
		if (allReferences.isEmpty()) {
			bottomReferences = Section.create("Referencias y notas", 2);
			bottomReferences.setIntro("<references />");
			page.setReferencesSection(bottomReferences);
		}
		
		String formatted = page.toString();
		
		checkDifferences(formatted, "duplicateReferencesSection", "más de una sección de referencias");
	}
	
	public void moveReferencesSection() {
		Page page = Page.store(title, this.text);
		
		List<Section> allReferences = page.findSectionsWithHeader("^(R|r)eferencias.*");
		
		if (isOldStructure || allReferences.size() != 1) {
			return;
		}
		
		Section references = allReferences.get(0);
		
		if (references == page.getReferencesSection()) {
			return;
		}
		
		List<Section> childSections = references.getChildSections();
		
		if (childSections != null) {
			List<Section> flattenedSubSections = SectionBase.flattenSubSections(childSections);
			List<String> subSectionHeaders = flattenedSubSections.stream()
				.map(SectionBase::getHeader)
				.collect(Collectors.toList());
			
			if (STANDARD_HEADERS.containsAll(subSectionHeaders)) {
				references.detachOnlySelf();
				references.setLevel(2);
				page.setReferencesSection(references);
			} else if (references.nextSiblingSection() != null) {
				references.detach();
				references.pushLevels(2 - references.getLevel());
				page.setReferencesSection(references);
			} else {
				return;
			}
		} else {
			references.detachOnlySelf();
			references.setLevel(2);
			page.setReferencesSection(references);
		}
		
		String formatted = page.toString();
		checkDifferences(formatted, "moveReferencesSection", "trasladando sección de referencias");
	}

	public void normalizeSectionLevels() {
		// TODO: handle single- to multiple-etymology sections edits and vice versa
		// TODO: satura
		
		if (isOldStructure) {
			return;
		}
		
		Page page = Page.store(title, this.text);
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
			List<Section> etymologySections = langSection.findSubSectionsWithHeader("^Etimología.*");
			
			if (etymologySections.isEmpty()) {
				continue;
			}
			
			if (etymologySections.size() == 1) {
				Collection<Section> etymologyChildren = etymologySections.get(0).getChildSections();
				
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

	private void pushStandardSections(List<Section> sections, int level) {
		// TODO: get rid of those null checks, find a better way
		if (sections == null) {
			return;
		}
		
		SectionBase.flattenSubSections(sections).stream()
			.filter(s -> Section.BOTTOM_SECTIONS.contains(s.getHeader()))
			.filter(s -> s.getLevel() > level)
			.forEach(s -> s.pushLevels(level - s.getLevel()));
	}

	public void removePronGrafSection() {
		Page page = Page.store(title, this.text);
		
		List<Section> sections = page.findSectionsWithHeader("(P|p)ronunciaci(ó|o)n( y escritura)?");
		
		if (isOldStructure || sections.isEmpty()) {
			return;
		}
		
		for (Section section : sections) {
			Section parentSection = section.getParentSection();
			
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
			
			String newIntro = Stream.of(String.join("\n", Arrays.asList(selfIntro, parentIntro)).split("\n"))
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
		
		Page page = Page.store(title, this.text);
		page.sortSections();
		String formatted = page.toString();
		checkDifferences(formatted, "sortLangSections", "ordenando secciones de idioma");
	}
	
	public void addMissingSections() {
		Page page = Page.store(title, this.text);
		
		// TODO: prevent adding translation sections in flexive form entries
		if (isOldStructure || page.getAllSections().isEmpty()) {
			return;
		}
		
		Set<String> set = new LinkedHashSet<String>();
		
		// Etymology
		
		for (LangSection langSection : page.getAllLangSections()) {
			if (
				langSection.getChildSections() == null ||
				!langSection.findSubSectionsWithHeader("Etimología.*").isEmpty()
			) {
				continue;
			}
			
			// TODO: review, catch special cases
			Set<String> headers = langSection.getChildSections().stream()
				.map(SectionBase::getHeader)
				.collect(Collectors.toSet());
			
			headers.removeAll(STANDARD_HEADERS);
			headers.removeIf(header -> header.startsWith("Forma "));
			
			if (headers.isEmpty()) {
				continue;
			}
			
			Section etymologySection = Section.create("Etimología", 3);
			etymologySection.setTrailingNewlines(1);
			
			HashMap<String, String> params = new LinkedHashMap<String, String>();
			params.put("templateName", "etimología");
			
			if (!langSection.getLangCode().equals("ES")) {
				params.put("leng", langSection.getLangCode().toLowerCase());
			}
			
			String template = ParseUtils.templateFromMap(params);
			etymologySection.setIntro(template + ".");
			langSection.prependSections(etymologySection);
			set.add("etimología");
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
					set.add("traducciones");
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
						set.add("traducciones");
					}
				}
			}
		}
		
		// References
		
		if (page.getReferencesSection() == null) {
			Section references = Section.create("Referencias y notas", 2);
			references.setIntro("<references />");
			page.setReferencesSection(references);
			set.add("referencias y notas");
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
	
	public void sortSubSections() {
		if (isOldStructure) {
			return;
		}
		
		Page page = Page.store(title, this.text);
		List<LangSection> list = new ArrayList<LangSection>(page.getAllLangSections());
		
		for (LangSection langSection : list) {
			langSection.sortSections();
		}
		
		String formatted = page.toString();
		checkDifferences(formatted, "sortSubSections", "ordenando subsecciones");
	}
	
	public void removeInflectionTemplates() {
		if (isOldStructure || !this.text.contains("{{inflect.")) {
			return;
		}
		
		Page page = Page.store(title, this.text);
		
		for (Section section : page.getAllSections()) {
			String intro = section.getIntro();
			
			if (
				section.getLangSectionParent() == null ||
				!section.getHeader().startsWith("Forma ") || 
				!intro.contains("{{inflect.")
			) {
				continue;
			}
			
			intro = intro.replace("\\{\\{inflect\\..+?\\}\\}", "").trim();
			section.setIntro(intro);
		}
		
		String formatted = page.toString();
		checkDifferences(formatted, "removeInflectionTemplates", "eliminando plantillas de flexión");
	}

	public void adaptPronunciationTemplates() {
		if (isOldStructure) {
			return;
		}
		
		Page page = Page.store(title, this.text);
		Set<String> modified = new LinkedHashSet<String>();
		
		for (Section section : page.getAllSections()) {
			if (!(section instanceof LangSection) && !section.getHeader().matches("Etimología \\d+")) {
				continue;
			}
			
			LangSection langSection = section.getLangSectionParent();
			String content = section.getIntro();
			content = content.replaceAll("\n{2,}", "\n");
			
			if (langSection == null || content.isEmpty() || content.contains("pron-graf")) {
				continue;
			}
			
			Map<String, Map<String, String>> tempMap = new HashMap<String, Map<String, String>>();
			List<String> editedLines = new ArrayList<String>();
			List<String> amboxTemplates = new ArrayList<String>();
			
			linesLoop:
			for (String line : content.split("\n")) {
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
					line = makeTmplLine(
						m.group(1).trim().toLowerCase(),
						m.group(2).trim(),
						PRON_TMPLS,
						PRON_TMPLS_ALIAS
					);
					
					if (line == null) {
						editedLines.add(origLine);
						continue;
					} else {
						templateFromText = m.group(1).trim();
					}
				}
				
				line = line.replaceFirst(
					"\\{\\{(?:P|p)ronunciación(?:\\|leng=[^\\|]*?)?\\|(.+?)\\}\\} (?:o|ó) \\{\\{AFI\\|(.+?)\\}\\}\\.?",
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
				Map<String, String> newParams = new LinkedHashMap<String, String>();
				
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
						
						if (param1 != null && (
							param1.isEmpty() || param1.equals("-") || param1.equals("[]") ||
							param1.equals("//") || param1.equals("...") ||
							param1.equals("[ ˈ ]") || param1.equals("[ˈ]") ||
							param1.equals("&nbsp;")
						)) {
							param1 = null;
						}
						
						if (param1 == null) {
							if (!langSection.getLangCode().equalsIgnoreCase("es")) {
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
								String[] alts = param1.split("\\] *?(o|,) *?\\[");
								
								if (alts.length > 1) {
									for (int i = 1; i <= alts.length; i++) {
										String param = alts[i - 1].trim();
										String num = (i != 1) ? Integer.toString(i) : "";
										newParams.put("fone" + num, param);
									}
								} else if (StringUtils.containsAny(param1, '[', ']')) {
									editedLines.add(origLine);
									continue linesLoop;
								} else {
									newParams.put("fone", param1);
								}
							} else if (param1.matches("/.+/")) {
								param1 = param1.substring(1, param1.length() - 1).trim();
								String[] alts = param1.split("/ *?(o|,) *?/");
								
								if (alts.length > 1) {
									for (int i = 1; i <= alts.length; i++) {
										String param = alts[i - 1].trim();
										String num = (i != 1) ? Integer.toString(i) : "";
										newParams.put("fono" + num, param);
									}
								} else if (param1.contains("/")) {
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
								!StringUtils.strip(params.get("ParamWithoutName2"), " ':").matches("(?i)audio")
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
			}
			
			if (tempMap.isEmpty() || (
				tempMap.containsKey("pronunciación") && tempMap.containsKey("pron.la")
			)) {
				continue;
			}
			
			HashMap<String, String> newMap = new LinkedHashMap<String, String>();
			newMap.put("templateName", "pron-graf");
			
			if (!langSection.getLangCode().equalsIgnoreCase("es")) {
				newMap.put("leng", langSection.getLangCode().toLowerCase());
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
	
	private void makePronGrafParams(Map<String, String> sourceMap, Map<String, String> targetMap, String prefix) {
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
		Set<String> modified = new HashSet<String>();
		String[] lines = this.text.split("\n", -1);
		
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			
			if (line.isEmpty()) {
				continue;
			}
			
			Matcher m = P_TMPL_LINE.matcher(line);
			
			if (
				!m.matches() || !((
					(line = makeTmplLine(
						m.group(1).trim().toLowerCase(),
						m.group(2).trim(),
						TERM_TMPLS,
						TERM_TMPLS_ALIAS)
					) != null) || (
					(line = makeTmplLine(
						m.group(1).trim().toLowerCase(),
						m.group(2).trim(),
						PRON_TMPLS,
						PRON_TMPLS_ALIAS)
					) != null))
			) {
				continue;
			}
			
			line = line.replace("{{derivado|", "{{derivad|");
			lines[i] = line + ".";
			modified.add(m.group(1).trim());
		}
		
		String formatted = String.join("\n", lines);
		String summary = "conversión a plantilla: " + String.join(", ", modified);
		
		checkDifferences(formatted, "convertToTemplate", summary);
	}

	private String makeTmplLine(String name, String content, List<String> listSg, List<String> listPl) {
		name = StringUtils.strip(name, " ':");
		
		if (name.isEmpty()) {
			return null;
		}
		
		if (listPl.contains(name)) {
			name = listSg.get(listPl.indexOf(name));
		}
		
		// TODO: review
		if (name.equals("ortografía alternativa")) {
			name = "grafía alternativa";
		}
		
		if (!listSg.contains(name)) {
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
		Pattern psep = Pattern.compile("(?:[^,\\(\\)\\[\\]\\{\\}]|\\(.+?\\)|\\[\\[.+?\\]\\]|\\{\\{.+?\\}\\})+");
		Matcher msep = psep.matcher(content);
		List<String> lterms = new ArrayList<String>();
		
		while (msep.find()) {
			lterms.add(msep.group().trim());
		}
		
		HashMap<String, String> map = new LinkedHashMap<String, String>(lterms.size(), 1);
		map.put("templateName", name);
		
		for (int i = 1; i <= lterms.size(); i++) {
			String term = lterms.get(i - 1);
			String param = "ParamWithoutName" + i;
			
			if (StringUtils.containsAny(term, '[', ']')) {
				Matcher m2 = Pattern.compile("\\[\\[(.+?)(?:(?:#.+?)?\\|([^\\]]+?))?\\]\\](.*)").matcher(term);
				
				if (!m2.matches() || StringUtils.containsAny(m2.group(3), '[', ']')) {
					return null;
				}
				
				map.put(param, m2.group(1));
				String trail = m2.group(3);
				
				if (!trail.isEmpty() && StringUtils.containsAny(trail, '(', ')')) {
					Matcher m3 = Pattern.compile("(.*?) \\(([^\\)]+)\\)").matcher(trail);
					
					if (!m3.matches()) {
						return null;
					} else {
						trail = m3.group(1).trim();
						map.put("nota" + i, m3.group(2));
					}
				}
				
				if (!trail.isEmpty() || (m2.group(2) != null && !m2.group(2).equals(m2.group(1)))) {
					map.put("alt" + i, (m2.group(2) != null ? m2.group(2) : m2.group(1)) + trail);
				}
			} else if (StringUtils.containsAny(term, '{', '}')) {
				Matcher m2 = Pattern.compile("(\\{\\{l\\+?\\|[^\\}]+\\}\\})(?: *?\\((.+)\\))?").matcher(term);
				
				if (!m2.matches() || StringUtils.containsAny(m2.group(2), '[', ']')) {
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
		
		return ParseUtils.templateFromMap(map);
	}

	public void langTemplateParams() {
		// TODO: ISO code as parameter without name
		// TODO: {{Matemáticas}}, {{mamíferos}}, etc.
		// TODO: {{ampliable}}, etc.
		
		if (isOldStructure) {
			return;
		}
		
		Page page = Page.store(title, this.text);
		
		for (LangSection langSection : page.getAllLangSections()) {
			String langCode = langSection.getLangCode().toLowerCase();
			boolean isSpanishSection = langCode.equals("es");
			String content = langSection.toString();
			boolean sectionModified = false;
			
			for (String target : LENG_PARAM_TMPLS) {
				List<String> templates = ParseUtils.getTemplates(target, content);
				int index = 0;
				
				for (String template : templates) {
					HashMap<String, String> params = ParseUtils.getTemplateParametersWithValue(template);
					String leng = params.get("leng");
					boolean templateModified = false;
					
					if (target.equals("ampliable")) {
						String param1 = params.remove("ParamWithoutName1");
						leng = Optional.ofNullable(leng).orElse(param1);
					}
					
					if (isSpanishSection) {
						// TODO: is this necessary?
						if (leng != null) {
							params.remove("leng");
							templateModified = true;
						}
					} else if (leng == null) {
						@SuppressWarnings("unchecked")
						Map<String, String> tempMap = (Map<String, String>) params.clone();
						params.clear();
						params.put("templateName", tempMap.remove("templateName"));
						params.put("leng", langCode);
						params.putAll(tempMap);
						templateModified = true;
					} else if (!leng.equals(langCode)) {
						params.put("leng", langCode);
						templateModified = true;
					}
					
					index = content.indexOf(template, index);
					
					if (templateModified) {
						String newTemplate = ParseUtils.templateFromMap(params);
						content = content.substring(0, index) + newTemplate + content.substring(index + template.length());
						sectionModified = true;
					}
				}
			}
			
			if (sectionModified) {
				LangSection newLangSection = LangSection.parse(content);
				langSection.replaceWith(newLangSection);
			}
		}
		
		String formatted = page.toString();
		checkDifferences(formatted, "langTemplateParams", "parámetros \"leng=\"");
	}

	public void addMissingElements() {
		if (isOldStructure) {
			return;
		}
		
		Page page = Page.store(title, this.text);
		Set<String> set = new LinkedHashSet<String>();
		
		for (LangSection langSection : page.getAllLangSections()) {
			List<Section> etymologySections = langSection.findSubSectionsWithHeader("Etimología.*");
			String langCode = langSection.getLangCode().toLowerCase();
			
			if (etymologySections.size() == 1) {
				Section etymologySection = etymologySections.get(0);
				String langSectionIntro = langSection.getIntro();
				String etymologyIntro = etymologySection.getIntro();
				
				if (ParseUtils.getTemplates("pron-graf", langSectionIntro).isEmpty()) {
					langSectionIntro = insertTemplate(langSectionIntro, langCode, "pron-graf", "{{%s}}");
					langSection.setIntro(langSectionIntro);
					set.add("{{pron-graf}}");
				}
				
				if (
					ParseUtils.getTemplates("etimología", etymologyIntro).isEmpty() &&
					ParseUtils.getTemplates("etimología2", etymologyIntro).isEmpty()
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
						ParseUtils.getTemplates("etimología", etymologyIntro).isEmpty() &&
						ParseUtils.getTemplates("etimología2", etymologyIntro).isEmpty()
					) {
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
		
		Section references = page.getReferencesSection();
		
		// TODO: check other elements (templates, manually introduced references...)
		if (references != null && references.getIntro().isEmpty()) {
			references.setIntro("<references />");
			set.add("<references>");
		}
		
		if (set.isEmpty()) {
			return;
		}
		
		String formatted = page.toString();
		String summary = String.format("añadiendo %s", String.join(", ", set));
		
		checkDifferences(formatted, "addMissingElements", summary);
	}
	
	private String insertTemplate(String content, String langCode, String templateName, String templateFormat) {
		Matcher m = P_AMBOX_TMPLS.matcher(content);
		StringBuffer sb = new StringBuffer();
		boolean hadMatch = false;
		
		while (m.find()) {
			m.appendReplacement(sb, m.group());
			hadMatch = true;
		}
		
		sb.append("\n");
		String lengParam = "";
		
		if (!langCode.equals("es")) {
			lengParam = String.format("|leng=%s", langCode);
		}
		
		sb.append(String.format(templateFormat, templateName + lengParam));
		
		if (!hadMatch) {
			sb.append("\n");
		}
		
		m.appendTail(sb);
		
		return sb.toString().trim();
	}

	public void deleteEmptySections() {
		// TODO
	}
	
	public void manageClearElements() {
		String original = this.text;
		// TODO: implement proper DOM parsing?
		// TODO: ignore comment regions
		original = original.replaceAll("<br +?clear *?= *?\"? *?all *?\"? *?>", "");
		String templateName = "clear";
		String template = String.format("{{%s}}", templateName);
		// TODO: sanitize templates to avoid inner spaces like in "{{ arriba..."
		String[] arr = {"{{arriba", "{{trad-arriba", "{{rel-arriba"};
		Page page = Page.store(title, original);
		
		for (Section section : page.getAllSections()) {
			Section nextSection = section.nextSection();
			
			if (nextSection == null) {
				continue;
			}
			
			if (
				StringUtils.startsWithAny(nextSection.getIntro(), arr) ||
				(
					nextSection.getHeader().matches("Etimología \\d+") &&
					!nextSection.getHeader().equals("Etimología 1")
				)
			) {
				List<String> templates = ParseUtils.getTemplates(templateName, section.getIntro());
				String intro = section.getIntro();
				
				if (templates.size() == 1 && intro.endsWith(template)) {
					continue;
				}
				
				if (!templates.isEmpty()) {
					intro = intro.replaceAll("\\{\\{ *?" + templateName + " *?\\}\\}", "").trim();
					section.setIntro(intro);
				}
				
				intro += "\n\n" + template;
				intro = intro.trim();
				section.setIntro(intro);
			} else if (section.getIntro().contains("clear")) {
				// TODO: sanitize templates, then change to "{{clear}}"
				String intro = section.getIntro();
				intro = intro.replaceAll("\\{\\{ *?" + templateName + " *?\\}\\}", "").trim();
				section.setIntro(intro);
			}
		}
		
		String formatted = page.toString();
		formatted = Utils.sanitizeWhitespaces(formatted);
		
		checkDifferences(formatted, "manageClearElements", "elementos \"clear\"");
	}
	
	public void strongWhitespaces() {
		// TODO: don't collide with removeComments() and manageClearElements() 
		String initial = this.text;
		initial = initial.replaceAll("( |&nbsp;)*\n", "\n");
		initial = initial.replaceAll(" ?&nbsp;", " ");
		initial = initial.replaceAll("&nbsp; ?", " ");
		Page page = Page.store(title, initial);
		
		if (page.getLeadingNewlines() > 1) {
			page.setLeadingNewlines(0);
		}
		
		if (page.getTrailingNewlines() > 1) {
			page.setTrailingNewlines(1);
		}
		
		for (Section section : page.getAllSections()) {
			if (section.getLeadingNewlines() > 1) {
				section.setLeadingNewlines(1);
			}
			
			if (section.getTrailingNewlines() > 1) {
				section.setTrailingNewlines(1);
			}
		}
		
		String formatted = page.toString();
		formatted = formatted.replaceAll("\n{3,}", "\n\n");
		formatted = formatted.replaceAll("\n\n<!--", "\n<!--");
		// TODO: trim whitespaces inside <ref>
		formatted = formatted.replaceAll("(\\.|\\]\\]|\\}\\}|\\)) <ref(>| )", "$1<ref$2");
		
		checkDifferences(formatted, "strongWhitespaces", "espacios en blanco");
	}

	public void weakWhitespaces() {
		Page page = Page.store(title, this.text);
		
		if (page.getLeadingNewlines() == 1) {
			page.setLeadingNewlines(0);
		}
		
		String intro = page.getIntro();
		
		if (intro.isEmpty() && page.getTrailingNewlines() == 1) {
			page.setTrailingNewlines(0);
		}
		
		if (
			!intro.isEmpty() && page.getTrailingNewlines() == 0 &&
			!(
				intro.split("\n").length == 1 &&
				intro.matches("^\\{\\{(d|D)esambiguación\\|?\\}\\}.*")
			)
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
				section.setHeaderFormat("%1$s%2$s%1$s");
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
		
		String formatted = page.toString();
		
		formatted = formatted.replaceAll("<references *?/ *?>", "<references />");
		formatted = formatted.replaceAll("(?m)^ +?(\\{\\{.+)", "$1"); // TODO: might be a strong whitespace
		formatted = formatted.replace(" </ref>", "</ref>");
		formatted = formatted.replaceAll("([^\n])\n{0,1}\\{\\{clear\\}\\}", "$1\n\n{{clear}}");
		
		checkDifferences(formatted, "weakWhitespaces", null);
	}

	public static void main(String[] args) throws FileNotFoundException, IOException, LoginException {
		ESWikt wb = Login.retrieveSession(Domains.ESWIKT, Users.User2);
		
		String text = null;
		String title = "a";
		//String title = "mole"; TODO
		//String title = "אביב"; // TODO: delete old section template
		//String title = "das"; // TODO: attempt to fix broken headers (missing "=")
		
		text = wb.getPageText(title);
		//text = String.join("\n", IOUtils.loadFromFile("./data/eswikt.txt", "", "UTF8"));
		
		Page page = Page.store(title, text);
		EditorBase editor = new Editor(page);
		editor.check();
		
		wb.edit(title, editor.getPageText(), editor.getSummary(), false, true, -2, null);
		System.out.println(editor.getLogs());
		
		Login.saveSession(wb);
	}
}
