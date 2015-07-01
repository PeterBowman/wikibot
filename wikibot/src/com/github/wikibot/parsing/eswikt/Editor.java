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
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.LoginException;

import org.apache.commons.lang3.StringUtils;
import org.wikiutils.IOUtils;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.ESWikt;
import com.github.wikibot.parsing.EditorBase;
import com.github.wikibot.parsing.Utils;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public class Editor extends EditorBase {
	private static final Pattern P_OLD_STRUCT_HEADER = Pattern.compile("^(.*?)(\\{\\{(?:ES|\\w+?-ES|TRANSLIT)(?:\\|[^\\}]+?)?\\}\\})\\s*(?:(?:<!--.*?-->)+\\s*)?$", Pattern.MULTILINE);
	private static final Pattern P_ADAPT_PRON_TMPL;
	private static final Pattern P_AMBOX_TMPLS;
	private static final Pattern P_PRON_LINE = Pattern.compile("^:*?\\* *?'''(.+?)'''(.+?)(?: *?\\.)?$", Pattern.MULTILINE);
	
	private static final List<String> LENG_PARAM_TMPLS = Arrays.asList(
		"etimología", "etimología2", "transliteración", "homófono", "grafía alternativa", "variantes",
		"parónimo", "sinónimo", "antónimo", "hiperónimo", "hipónimo", "uso", "ámbito", "apellido",
		"doble conjugación", "derivad", "grafía", "pron-graf", "rima", "relacionado", "pronunciación"
	);
	
	private static final List<String> PRON_TEMPLS = Arrays.asList(
		"pronunciación", "pron.la",  "audio", "transliteración", "homófono",
		"grafía alternativa", "variantes", "parónimo"
	);
	
	private static final List<String> PRON_TEMPLS_PL = Arrays.asList(
		null, null, null, "transliteraciones", "homófonos", "grafías alternativas", null, "parónimos"
	);
	
	private boolean isOldStructure;
	
	static {
		P_ADAPT_PRON_TMPL = Pattern.compile("^[:\\*]*? *?\\{\\{ *?(" + String.join("|", PRON_TEMPLS) + ") *?(?:\\|[^\\{]*?)?\\}\\}\\.?$");
		
		// https://es.wiktionary.org/wiki/Categor%C3%ADa:Wikcionario:Plantillas_de_mantenimiento
		final List<String> amboxTemplates = Arrays.asList(
			"ampliable", "creado por bot", "definición", "discutido", "esbozo", "stub", "estructura", "formato",
			"falta", "revisión", "revisar"
		);
		
		P_AMBOX_TMPLS = Pattern.compile("^ *?\\{\\{ *?(" + String.join("|", amboxTemplates) + ") *?(?:\\|.*)?\\}\\}$", Pattern.CASE_INSENSITIVE);
	}
	
	public Editor(Page page) {
		super(page.getTitle(), page.toString());
		checkOldStructure();
	}
	
	public Editor(PageContainer pc) {
		super(pc.getTitle(), pc.getText());
		checkOldStructure();
	}
	
	private void checkOldStructure() {
		if (text.contains("{{ES") || text.contains("-ES}}") || text.contains("-ES|")) {
			isOldStructure = true;
		} else {
			isOldStructure = false;
		}
	}
	
	@Override
	public void check() {
		removeComments();
		transformToNewStructure();
		normalizeSectionHeaders();
		rearrangeSubsections();
		normalizeTemplateNames();
		adaptPronunciationTemplates();
		lengTemplateParams();
		rearrangeSubsections();
		substituteReferencesTemplate();
		duplicateReferencesSection();
		sortLangSections();
		addMissingReferencesSection();
		sortSubSections();
		strongWhitespaces();
		weakWhitespaces();
	}
	
	public void removeComments() {
		String original = this.text;
		String formatted = original;
		
		formatted = formatted.replaceAll("<!-- *?-->", "");
		formatted = formatted.replaceAll("<!-- ?si hay términos que se diferencian .+?-->", "");
		formatted = formatted.replaceAll("<!---? ?Añádela en el Alfabeto Fonético Internacional.+?-->", "");
		formatted = formatted.replaceAll("<!---? ?Añade la pronunciación en el Alfabeto Fonético Internacional.+?-->", "");
		formatted = formatted.replaceAll("<!-- ?A(ñ|n)ádela con el siguiente patrón.+?-->", "");
		formatted = formatted.replaceAll("<!-- ?Añade la etimología con el siguiente patrón.+?-->", "");
		formatted = formatted.replaceAll("(?s)\n?<!-- ?Escoge la plantilla adecuada .+?-->", "");
		formatted = formatted.replaceAll("(?s)\n?<!-- ?Utiliza cualquiera de las siguientes plantillas .+?-->", "");
		formatted = formatted.replaceAll("<!-- ?explicación de lo que significa la palabra -->", "");
		formatted = formatted.replaceAll("\n?<!-- ?(; )?si pertenece a un campo semántico .+?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?(;2: )?si hay más acepciones.+?-->", "");
		formatted = formatted.replaceAll("(?s)\n?<!-- ?puedes incluir uno o más de los siguientes campos .+?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?\\{\\{ámbito\\|<ÁMBITO 1>\\|<ÁMBITO2>\\}\\}.*?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?\\{\\{uso\\|\\}\\}.+?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?\\{\\{sinónimo\\|<(SINÓNIMO )?1>\\|<(SINÓNIMO )?2>\\}\\}.*?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?\\{\\{antónimo\\|<(ANTÓNIMO )?1>\\|<(ANTÓNIMO )?2>\\}\\}.*?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?\\{\\{hipónimo\\|<(HIPÓNIMO )?1>\\|<(HIPÓNIMO )?2>\\}\\}.*?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?\\{\\{relacionado\\|<1>\\|<2>\\}\\}.*?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?\\{\\{ejemplo\\|<oración.+?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?\\{\\{ejemplo\\}\\} ?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?aquí pones una explicaci(ó|o)n .+?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?aquí escribes una explicaci(ó|o)n .+?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?si tienes información adicional.+?-->", "");
		formatted = formatted.replaceAll("(?s)\n?<!-- ?Puedes también incluir las siguientes secciones.+?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?\\{\\{etimología\\|IDIOMA.+?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?posiblemente desees incluir una imagen.+?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?si se trata de un país.+?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?puedes también incluir .+?-->", "");
		formatted = formatted.replaceAll("(?s)\n?<!-- ?Incluir la plantilla de conjugación aquí.+?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?otra sección opcional para .+?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?¿flexión?: mira en .+?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?\\{\\{inflect.sust.sg-pl\\|AQUÍ EL SINGULAR.+?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?\\{\\{pronunciación\\|\\[ ˈ \\]\\}\\}.+?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?en general, no se indica la etimología .+?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?\\{\\{pronunciación\\|\\}\\} ?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?si vas a insertar una nueva sección de etimología o de idioma.+?-->", "");
		formatted = formatted.replaceAll("\n?<!-- ?si se trata de un país,? por favor,? pon.+?-->", "");
		formatted = formatted.replaceAll("<!-- *?apellidos .+?-->", "");
		// TODO: catch open comment tags in arbitrary Sections - [[Especial:PermaLink/2709606]]
		formatted = formatted.replaceAll("<!--\\s*$", "");
		
		formatted = Utils.sanitizeWhitespaces(formatted);
		
		checkDifferences(original, formatted, "removeComments", "eliminando comentarios");
	}
	
	public void minorSanitizing() {
		// TODO: perrichines (comment close tag)
		// TODO: trailing period after {{etimología}}
	}
	
	public void transformToNewStructure() {
		String original = this.text;
		Page page = Page.store(title, original);
		
		// TODO
		if (!isOldStructure ||
			original.contains("{{lengua|") ||
			original.contains("{{TRANS") ||
			original.contains("{{TRANSLIT|") ||
			original.contains("{{Chono-ES") ||
			original.contains("{{Protopolinesio-ES") ||
			original.contains("{{carácter oriental") ||
			original.contains("{{Carácter oriental") ||
			original.contains("{{TAXO}}") ||
			page.hasSectionWithHeader("^Etimolog(i|í)a.*") ||
			page.hasSectionWithHeader("^(F|f)orma .+")
		) {
			return;
		}
		
		// Process headers
		
		Matcher m = P_OLD_STRUCT_HEADER.matcher(original);
		StringBuffer sb = new StringBuffer();
		String currentEtym = "";
		int lastIndex = 0;
		
		while (m.find()) {
			String pre = m.group(1);
			String template = m.group(2);
			HashMap<String, String> params = ParseUtils.getTemplateParametersWithValue(template);
			String name = params.get("templateName");
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
			
			if (
				!altGraf.isEmpty() && !altGraf.equals("{{PAGENAME}}") &&
				!altGraf.replace("ʼ", "'").equals(title.replace("ʼ", "'"))
			) {
				params.put("alt", altGraf);
			} else {
				altGraf = "";
			}
			
			pre = pre.isEmpty() ? "" : "$1\n";
			
			if (!etym.isEmpty() && !etym.equals("1")) {
				m.appendReplacement(sb, String.format("%s=ETYM%s alt-%s=", pre, etym, altGraf));
			} else {
				String newTemplate = ParseUtils.templateFromMap(params);
				m.appendReplacement(sb, String.format("%s=%s=", pre, newTemplate));
			}
			
			if (lastIndex != 0) {
				String added = sb.substring(lastIndex);
				int headerIndex = added.indexOf("\n=");
				
				if (headerIndex != -1) {
					String etymHeader = currentEtym.isEmpty()
						? "==Etimología=="
						: String.format("==Etimología %s==", currentEtym);
					String replacement = added.substring(0, headerIndex) + "\n" + etymHeader + added.substring(headerIndex);
					sb.replace(lastIndex, sb.length(), replacement);
				}
			}
			
			lastIndex = sb.length();
			currentEtym = etym;
		}
		
		m.appendTail(sb);
		
		if (lastIndex != 0) {
			String added = sb.substring(lastIndex);
			int headerIndex = added.indexOf("\n=");
			
			if (headerIndex != -1) {
				String etymHeader = currentEtym.isEmpty()
					? "==Etimología=="
					: String.format("==Etimología %s==", currentEtym);
				String replacement = added.substring(0, headerIndex) + "\n" + etymHeader + added.substring(headerIndex);
				sb.replace(lastIndex, sb.length(), replacement);
			}
		}
		
		String newText = sb.toString();
		
		if (newText.equals(original)) {
			return;
		}
		
		newText = newText.replaceAll("=+? *?<small>(Referencias.*?)</small> *?=+", "=$1=");
		newText = newText.replaceAll("=+? *?(Referencias.*?) *?=+", "=$1=");
		newText = newText.replaceAll("\\{\\{transic\\}\\}\n?", "");
		
		page = Page.store(page.getTitle(), newText);
		
		for (Section section : page.getAllSections()) {
			section.setLevel(section.getLevel() + 1);
		}
		
		// TODO: add a method to reparse all Sections?
		page = Page.store(page.getTitle(), page.toString());
		
		// Rearrange etymology sections
		
		for (Section section : page.getAllSections()) {
			String header = section.getHeader();
			
			if (!header.matches("^Etimología.*")) {
				continue;
			}
			
			Section previousSection = section.previousSection();
			String content = previousSection.getIntro();
			String previousHeader = previousSection.getHeader();
			String prevHeaderTmpl = null;
			HashMap<String, String> prevHeaderTmplParams = null;
			String alt = "";
			
			// Extract alt parameter
			
			if (previousHeader.startsWith("ETYM")) {
				alt = previousHeader.substring(previousHeader.indexOf(" alt-") + " alt-".length());
			} else {
				List<String> prevHeaderTmpls = ParseUtils.getTemplates("lengua", previousHeader);
				
				if (prevHeaderTmpls.isEmpty()) {
					prevHeaderTmpls = ParseUtils.getTemplates("translit", previousHeader);
				}
				
				prevHeaderTmpl = prevHeaderTmpls.get(0);
				prevHeaderTmplParams = ParseUtils.getTemplateParametersWithValue(prevHeaderTmpl);
				alt = prevHeaderTmplParams.getOrDefault("alt", "");
			}
			
			// Apply or dismiss alt parameter
			// TODO: handle etymology for compounds and {{pron-graf}} for alt parameters
			
			if (!alt.isEmpty()) {
				List<String> pronLaTmpls = ParseUtils.getTemplates("pron.la", content);
				
				if (!pronLaTmpls.isEmpty() && pronLaTmpls.size() == 1) {
					String pronLaTmpl = pronLaTmpls.get(0);
					HashMap<String, String> pronLaParams = ParseUtils.getTemplateParametersWithValue(pronLaTmpl);
					
					if (!pronLaParams.containsKey("alt")) {
						pronLaParams.put("alt", alt);
						content = content.replace(pronLaTmpl, ParseUtils.templateFromMap(pronLaParams));
					}
					
					if (previousSection instanceof LangSection) {
						prevHeaderTmplParams.remove("alt");
						String newTemplate = ParseUtils.templateFromMap(prevHeaderTmplParams);
						previousHeader = previousHeader.replace(prevHeaderTmpl, newTemplate);
						previousSection.setHeader(previousHeader);
					}
				} else if (!(previousSection instanceof LangSection)) {
					return;
				}
			}
			
			// Move contents to the new etymology sections
			// TODO: review, catch special cases
			
			boolean isEmpty = true;
			LangSection langSectionParent = section.getLangSectionParent();
			Section nextParentSiblingSection = (langSectionParent != null) ? langSectionParent.nextSiblingSection() : null;
			
			if (
				header.matches("^Etimología \\d+$") &&
				!(nextParentSiblingSection instanceof LangSection)
			) {
				if (!content.isEmpty()) {
					section.setIntro(content);
					isEmpty = false;
					
					if (previousSection instanceof LangSection) {
						previousSection.setIntro("");
						previousSection.setTrailingNewlines(1);
					} else {
						previousSection.detachOnlySelf();
					}
					
					// Search for the etymology template and move it to the last line
					
					String[] lines = content.split("\n");
					
					if (lines.length > 1) {
						int lineNumber = 0;
						boolean found = false;
						
						for (; lineNumber < lines.length; lineNumber++) {
							String line = lines[lineNumber];
							
							if (line.matches(".*?\\{\\{(e|E)timología.+")) {
								found = true;
								break;
							}
						}
						
						if (found && lineNumber != lines.length - 1) {
							String line = lines[lineNumber];
							lines[lineNumber] = null;
							List<String> list = Stream.of(lines).filter(Objects::nonNull).collect(Collectors.toList());
							list.add(line);
							section.setIntro(String.join("\n", list));
						}
					}
				}
			} else {
				// In case there is one single "Etimología 1" section in the current LangSection parent
				
				section.setHeader("Etimología");
				
				// Move etymology template to the newly created etymology section
				
				Pattern tempPatt = Pattern.compile("\n?(\\{\\{(?:e|E)timología[^\n]+)", Pattern.DOTALL);
				Matcher m2 = tempPatt.matcher(content);
				List<String> temp = new ArrayList<String>();
				
				while (m2.find()) {
					isEmpty = false;
					temp.add(m2.group(1).trim());
					content = content.substring(0, m2.start()) + content.substring(m2.end());
					m2 = tempPatt.matcher(content);
				}
				
				if (!temp.isEmpty()) {
					previousSection.setIntro(content);
					section.setIntro(String.join("\n\n", temp));
				}
			}
			
			// TODO: move to new task
			
			if (isEmpty) {
				HashMap<String, String> params = new LinkedHashMap<String, String>();
				params.put("templateName", "etimología");
				String langCode = langSectionParent.getLangCode().toLowerCase();
				
				if (!langCode.equals("es")) {
					params.put("leng", langCode);
				}
				
				section.setIntro(ParseUtils.templateFromMap(params) + ".");
			}
			
			section.setTrailingNewlines(1);
		}
		
		page.normalizeChildLevels();
		
		// Check section levels
		
		for (LangSection langSection : page.getAllLangSections()) {
			Collection<Section> childSections = langSection.getChildSections();
			
			if (childSections == null) {
				continue;
			}
			
			List<Section> etymologySections = childSections
				.parallelStream()
				.filter(section -> section.getHeader().matches("^Etimolog(i|í)a.*"))
				.collect(Collectors.toList());
			
			if (etymologySections == null) {
				continue;
			}
			
			if (etymologySections.size() == 1) {
				Section etymologySection = etymologySections.get(0);
				Collection<Section> etymologyChildren = etymologySection.getChildSections();
				
				if (etymologyChildren == null) {
					continue;
				}
				
				for (Section child : etymologyChildren) {
					child.pushLevels(-1);
				}
			} else {
				for (Section sibling : childSections) {
					if (etymologySections.contains(sibling)) {
						continue;
					}
					
					sibling.pushLevels(1);
				}
			}
		}
		
		String formatted = page.toString();
		isOldStructure = false;
		
		checkDifferences(original, formatted, "transformToNewStructure", "conversión a la nueva estructura");
	}
	
	public void normalizeSectionHeaders() {
		String original = this.text;
		Page page = Page.store(title, original);
		
		for (Section section : page.getAllSections()) {
			if (section instanceof LangSection) {
				continue;
			}
			
			String header = section.getHeader();
			
			header = StringUtils.strip(header, "=").trim();
			header = header.replaceAll("^(?:e|E)timolog(?:i|í)a ?(\\d)?$", "Etimología $1").trim();
			// TODO: don't confuse with {{locución}}, {{refrán}}
			header = header.replaceAll("^(?:L|l)ocuciones$", "Locuciones");
			header = header.replaceAll("^(?:R|r)efranes$", "Refranes");
			header = header.replaceAll("^(?:c|C)onjugaci(?:ó|o)n$", "Conjugación");
			header = header.replaceAll("^(?:I|i)nformaci(?:ó|o)n (?:adicional|avanzada)$", "Información adicional");
			header = header.replaceAll("^(?:V|v)er tambi(?:é|e)n$", "Véase también");
			header = header.replaceAll("^(?:V|v)(?:é|e)ase tambi(?:é|e)n$", "Véase también");
			
			// TODO: https://es.wiktionary.org/w/index.php?title=klei&oldid=2727290
			LangSection langSection = section.getLangSectionParent();
			
			if (langSection != null) {
				if (langSection.getLangName().equals("español")) {
					header = header.replaceAll("^(?:T|t)raducci(?:ó|o)n(es)?$", "Traducciones");
				} else {
					header = header.replaceAll("^(?:T|t)raducci(?:ó|o)n(es)?$", "Traducción");
				}
			}
			
			// TODO: check structure, normalize header levels
			if (!isOldStructure) {
				header = header.replaceAll("^(?:<small> *?)?(?:R|r)eferencias?.*?$", "Referencias y notas");
			}
			
			section.setHeader(header);
		}
		
		String formatted = page.toString();
		
		checkDifferences(original, formatted, "normalizeSectionHeaders", "normalizando títulos de encabezamiento");
	}

	public void rearrangeSubsections() {
		// TODO: satura, review sortSections (intermediate Sections - between LangSections)
		// TODO: tagua tagua (single, numbered etymology sections), temporarily fixed in transformToNewStructure
		String original = this.text;
		Page page = Page.store(title, original);
		Section references = page.getReferencesSection();
		
		if (references != null && references.getLevel() != 2 && references.getChildSections() == null) {
			references.setLevel(2);
		} else {
			return;
		}
		
		String formatted = page.toString();
		checkDifferences(original, formatted, "rearrangeSubsections", "reorganizando secciones");
	}

	public void normalizeTemplateNames() {
		String original = this.text;
		String formatted = original;
		Map<String, String> map = new HashMap<String, String>();
		
		map.put("Pronunciación", "pronunciación");
		map.put("Etimología", "etimología");
		map.put("Desambiguación", "desambiguación");
		map.put("grafías alternativas", "grafía alternativa");
		
		List<String> found = new ArrayList<String>();
		
		for (Entry<String, String> entry : map.entrySet()) {
			String target = entry.getKey();
			String replacement = entry.getValue();
			StringBuffer sb = new StringBuffer();
			Pattern patt = Pattern.compile("\\{\\{ *?" + target + " *?(\\|.*?)?\\}\\}", Pattern.DOTALL);
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
		
		found.remove("Pronunciación");
		found.remove("grafías alternativas");
		
		String summary = null;
		
		if (!found.isEmpty()) {
			summary = found.stream()
				.map(target -> String.format("{{%s}} → {{%s}}", target, map.get(target)))
				.collect(Collectors.joining(", "));
		}
		
		checkDifferences(original, formatted, "normalizeTemplateNames", summary);
	}

	public void adaptPronunciationTemplates() {
		String original = this.text;
		Page page = Page.store(title, original);
		Set<String> modified = new HashSet<String>();
		List<String> recognizedSpanishParams = Arrays.asList("y", "ll", "s", "c", "ys", "yc", "lls", "llc");
		
		for (Section section : page.getAllSections()) {
			String content = section.getIntro();
			content = content.replaceAll("\n{2,}", "\n");
			
			if (content.isEmpty() || content.contains("{{pron-graf")) {
				continue;
			}
			
			LangSection langSection = section.getLangSectionParent();
			
			if (langSection == null) {
				continue;
			}
			
			String langCode = langSection.getLangCode().toLowerCase();
			boolean isSpanishSection = langCode.equals("es");
			String[] lines = content.split("\n");
			boolean noMatch = false;
			Map<String, Map<String, String>> tempMap = new HashMap<String, Map<String, String>>();
			List<String> editedLines = new ArrayList<String>();
			List<String> amboxTemplates = new ArrayList<String>();
			
			linesLoop:
			for (String line : lines) {
				// TODO: more cases (images, comments...)
				
				if (line.contains("{{etimología")) {
					editedLines.add(line);
					continue;
				}
				
				if (P_AMBOX_TMPLS.matcher(line).matches()) {
					amboxTemplates.add(line);
					continue;
				}
				
				Matcher m = P_PRON_LINE.matcher(line);
				
				if (m.matches() && ((line = makePronLine(m)) == null)) {
					noMatch = true;
					break;
				}
				
				m = P_ADAPT_PRON_TMPL.matcher(line);
				
				if (!m.matches()) {
					noMatch = true;
					break;
				}
				
				String templateName = m.group(1).toLowerCase();
				
				if (tempMap.containsKey(templateName)) {
					noMatch = true;
					break;
				}
				
				List<String> templates = ParseUtils.getTemplates(templateName, line);
				
				if (templates.isEmpty() || templates.size() > 1) {
					noMatch = true;
					break;
				}
				
				Map<String, String> params = ParseUtils.getTemplateParametersWithValue(templates.get(0));
				Map<String, String> newParams = new LinkedHashMap<String, String>();
				
				switch (templateName) {
					case "pronunciación":
						if (params.containsKey("fuente")) {
							noMatch = true;
							break linesLoop;
						}
						
						String param1 = params.get("ParamWithoutName1");
						
						if (param1 != null && (
							param1.isEmpty() || param1.equals("-") || param1.equals("[]") ||
							param1.equals("//") || param1.equals("...") ||
							param1.equals("[ ˈ ]") || param1.equals("[ˈ]")
						)) {
							param1 = null;
						}
						
						if (param1 == null) {
							if (!isSpanishSection) {
								break;
							}
							
							int count = 1;
							
							for (Entry<String, String> entry : params.entrySet()) {
								String type = entry.getKey();
								
								if (!recognizedSpanishParams.contains(type)) {
									continue;
								}
								
								String num = (count != 1) ? Integer.toString(count) : "";
								String pron = "", altpron = "";
								
								if (Arrays.asList("s", "c").contains(type)) {
									pron = "seseo";
									altpron = type.equals("s") ? "Seseante" : "No seseante";
								} else if (Arrays.asList("ll", "y").contains(type)) {
									pron = "yeísmo";
									altpron = type.equals("y") ? "Yeísta" : "No yeísta";
								} else {
									pron = "variaciones fonéticas";
									
									switch (type) {
										case "ys":
											altpron = "Yeísta, seseante";
											break;
										case "yc":
											altpron = "Yeísta, no seseante";
											break;
										case "lls":
											altpron = "No yeísta, seseante";
											break;
										case "llc":
											altpron = "No yeísta, no seseante";
											break;
									}
								}
								
								newParams.put(num + "pron", pron);
								newParams.put("alt" + num + "pron", altpron);
								newParams.put(num + "fone", params.get(type));
								
								count++;
							}
						} else {
							if (param1.startsWith("[") && !param1.endsWith("]")) {
								param1 += "]";
							} else if (!param1.startsWith("[") && param1.endsWith("]")) {
								param1 = "[" + param1;
							}
							
							if (param1.matches("\\[.+\\]")) {
								param1 = param1.substring(1, param1.length() - 1).trim();
								String[] alts = param1.split("\\] *?o *?\\[");
								
								if (alts.length > 1) {
									for (int i = 1; i <= alts.length; i++) {
										String param = alts[i - 1].trim();
										String num = (i != 1) ? Integer.toString(i) : "";
										newParams.put("fone" + num, param);
									}
								} else {
									newParams.put("fone", param1);
								}
							} else if (param1.matches("/.+/")) {
								param1 = param1.substring(1, param1.length() - 1).trim();
								newParams.put("fono", param1);
							} else {
								newParams.put("fone", param1);
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
						if (
							(params.containsKey("nb") && !params.get("nb").isEmpty()) ||
							(
								params.containsKey("ParamWithoutName2") &&
								!params.get("ParamWithoutName2").isEmpty() &&
								!params.get("ParamWithoutName2").matches("(?i)audio.?")
							)
						) {
							noMatch = true;
							break linesLoop;
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
					default:
						noMatch = true;
						break linesLoop;
				}
				
				tempMap.put(templateName, newParams);
				modified.add(templateName);
			}
			
			if (noMatch || tempMap.isEmpty() || (tempMap.containsKey("pronunciación") && tempMap.containsKey("pron.la"))) {
				continue;
			}
			
			HashMap<String, String> newMap = new LinkedHashMap<String, String>();
			newMap.put("templateName", "pron-graf");
			
			if (!isSpanishSection) {
				newMap.put("leng", langCode);
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
		
		if (modified.isEmpty()) {
			return;
		}
		
		String formatted = page.toString();
		
		String summary = modified.stream()
			.map(template -> String.format("{{%s}}", template))
			.collect(Collectors.joining(", "));
		
		summary += " → {{pron-graf}}";
		
		checkDifferences(original, formatted, "adaptPronunciationTemplates", summary);
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
	
	private String makePronLine(Matcher m) {
		String name = m.group(1).trim().toLowerCase();
		
		if (name.endsWith(":")) {
			name = name.substring(0, name.length() - 1).trim();
		}
		
		if (name.isEmpty()) {
			return null;
		}
		
		if (PRON_TEMPLS_PL.contains(name)) {
			name = PRON_TEMPLS.get(PRON_TEMPLS_PL.indexOf(name));
		}
		
		if (!PRON_TEMPLS.contains(name)) {
			return null;
		}
		
		String content = m.group(2).trim();
		
		if (StringUtils.containsAny(content, '{', '}', '(', ')', '|')) {
			return null;
		}
		
		String[] terms = content.split(" *?, *?");
		HashMap<String, String> map = new LinkedHashMap<String, String>(terms.length, 1);
		map.put("templateName", name);
		
		for (int i = 0; i < terms.length; i++) {
			String term = terms[i];
			String param = "ParamWithoutName" + i;
			
			if (StringUtils.containsAny(term, '[', ']')) {
				Matcher m2 = Pattern.compile("\\[\\[(.+?)\\]\\](.*)").matcher(term);
				
				if (!m2.find() || StringUtils.containsAny(m2.group(2), '[', ']')) {
					return null;
				} else {
					map.put(param, String.format("%s%s", m2.group(1), m2.group(2)));
				}
			} else {
				map.put(param, term);
			}
		}
		
		return ParseUtils.templateFromMap(map);
	}

	public void lengTemplateParams() {
		// TODO: ISO code as parameter without name
		// TODO: {{Matemáticas}}, {{mamíferos}}, etc.
		String original = this.text;
		Page page = Page.store(title, original);
		
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
		checkDifferences(original, formatted, "lengTemplateParams", "parámetros \"leng=\"");
	}

	public void substituteReferencesTemplate() {
		String original = this.text;
		Page page = Page.store(title, original);
		String template = "{{título referencias}}";
		List<String> contents = new ArrayList<String>();
		boolean found = false;
		
		List<Section> sections = page.getAllSections();
		ListIterator<Section> iterator = sections.listIterator(sections.size());
		
		while (iterator.hasPrevious()) {
			Section section = iterator.previous();
			String intro = section.getIntro();
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
		checkDifferences(original, formatted, "substituteReferencesTemplate", "sustituyendo {{título referencias}}");
	}
	
	public void duplicateReferencesSection() {
		String original = this.text;
		Page page = Page.store(title, original);
		
		Section bottomReferences = page.getReferencesSection();
		List<Section> allReferences = page.findSectionsWithHeader("^(R|r)eferencias.*");
		
		if (allReferences.size() < 2) {
			return;
		}
		
		Iterator<Section> iterator = allReferences.iterator();
		
		while (iterator.hasNext()) {
			Section section = iterator.next();
			
			if (section.getChildSections() == null) {
				String content = section.getIntro();
				content = content.replaceAll("(?s)<!--.*?-->", ""); 
				content = content.replaceAll("<references *?/ *?>", "");
				content = content.trim();
				
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
		
		checkDifferences(original, formatted, "duplicateReferencesSection", "más de una sección de referencias");
	}
	
	public void sortLangSections() {
		String original = this.text;
		Page page = Page.store(title, original);
		page.sortSections();
		String formatted = page.toString();
		checkDifferences(original, formatted, "sortLangSections", "ordenando secciones de idioma");
	}
	
	public void addMissingReferencesSection() {
		String original = this.text;
		Page page = Page.store(title, original);
		Section references = page.getReferencesSection();
		boolean onlyTag = false;
		
		if (page.getAllSections().isEmpty() || (references == null && page.hasSectionWithHeader(".*?Referencias.*"))) {
			return;
		}
		
		if (references == null) {
			references = Section.create("Referencias y notas", 2);
			references.setIntro("<references />");
			page.setReferencesSection(references);
		} else {
			String intro = references.getIntro();
			
			// TODO: check other elements (templates, manually introduced references...)
			if (!intro.isEmpty()) {
				return;
			}
			
			references.setIntro("<references />");
			onlyTag = true;
		}
		
		String formatted = page.toString();
		String summary = onlyTag ? "añadiendo <references>" : "añadiendo título de referencias y notas";
		
		checkDifferences(original, formatted, "addMissingReferencesSection", summary);
	}
	
	public void sortSubSections() {
		String original = this.text;
		Page page = Page.store(title, original);
		List<LangSection> list = new ArrayList<LangSection>(page.getAllLangSections());
		
		for (LangSection langSection : list) {
			langSection.sortSections();
		}
		
		String formatted = page.toString();
		checkDifferences(original, formatted, "sortSubSections", "ordenando subsecciones");
	}

	public void deleteEmptySections() {
		// TODO
	}
	
	public void strongWhitespaces() {
		String original = text;
		Page page = Page.store(title, original);
		
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
		
		checkDifferences(original, formatted, "strongWhitespaces", "espacios en blanco");
	}

	public void weakWhitespaces() {
		String original = text;
		Page page = Page.store(title, original);
		
		if (page.getLeadingNewlines() == 1) {
			page.setLeadingNewlines(0);
		}
		
		String intro = page.getIntro();
		
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
				!sectionIntro.isEmpty() &&
				!sectionIntro.contains("<!-- si vas a insertar una nueva sección") &&
				!sectionIntro.endsWith("<br clear=\"all\">")
			) {
				section.setTrailingNewlines(1);
			} else if (sectionIntro.isEmpty() && section.getTrailingNewlines() == 0) {
				section.setTrailingNewlines(1);
			}
			
			section.setHeaderFormat("%1$s%2$s%1$s");
		}
		
		String formatted = page.toString();
		
		formatted = formatted.replaceAll("<references */ *>", "<references />");
		formatted = formatted.replaceAll("(?m)^ +?(\\{\\{.+)", "$1"); // TODO: might be a strong whitespace
		
		checkDifferences(original, formatted, "weakWhitespaces", null);
	}

	private void checkDifferences(String original, String formatted, String caller, String log) {
		if (!formatted.equals(original)) {
			logger.add(caller);
			text = formatted;
			
			if (log != null) {
				summ.add(log);
				notifyModifications = true;
			}
		}
	}

	public static void main(String[] args) throws FileNotFoundException, IOException, LoginException {
		ESWikt wb = Login.retrieveSession(Domains.ESWIKT, Users.User2);
		
		String text = null;
		String title = "wasi";
		//String title = "mole"; TODO
		//String title = "אביב"; // TODO: delete old section template
		//String title = "das"; // TODO: attempt to fix broken headers (missing "=")
		
		//text = wb.getPageText(title);
		text = String.join("\n", IOUtils.loadFromFile("./data/eswikt.txt", "", "UTF8"));
		
		Page page = Page.store(title, text);
		Editor editor = new Editor(page);
		editor.check();
		
		wb.edit(title, editor.getPageText(), editor.getSummary(), false, true, -2, null);
		System.out.println(editor.getLogs());
		
		Login.saveSession(wb);
	}
}
