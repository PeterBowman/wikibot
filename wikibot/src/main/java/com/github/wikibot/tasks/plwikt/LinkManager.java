package com.github.wikibot.tasks.plwikt;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.security.auth.login.CredentialException;
import javax.security.auth.login.LoginException;

import org.apache.commons.text.StringEscapeUtils;
import org.wikipedia.ArrayUtils;
import org.wikipedia.Wiki;
import org.wikipedia.Wiki.Revision;
import org.wikipedia.Wiki.User;

import com.github.plural4j.Plural;
import com.github.plural4j.Plural.WordForms;
import com.github.wikibot.main.Selectorizable;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.Utils;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.PluralRules;
import com.ibm.icu.number.LocalizedNumberFormatter;
import com.ibm.icu.number.NumberFormatter;
import com.ibm.icu.number.NumberFormatter.GroupingStrategy;
import com.thoughtworks.xstream.XStream;

public final class LinkManager implements Selectorizable {
	private static Wikibot wb;
	private static final Plural pluralPL;
	private static final LocalizedNumberFormatter numberFormatPL;
	private static final Path location = Paths.get("./data/tasks.plwikt/LinkManager/");
	private static final String mainpage = "Wikipedysta:PBbot/linkowanie";
	private static final int requestsectionnumber = 2;
	private static final int worklistsectionnumber = 3;
	//private static final int reportsectionnumber = 4;
	private static final String requestheader = "== Wyszukiwanie ==\n";
	private static final String worklistheader = "== Lista robocza ==\n";
	private static final String worklistintro = "* Zatwierdzone: \n";
	private static final String reportheader = "== Raport ==\n";
	
	private static final Path f_data = location.resolve("data.xml");
	private static final Path f_codes = location.resolve("codes.xml");
	private static final Path f_timestamps = location.resolve("timestamps.xml");
	private static final Path f_stats = location.resolve("stats.txt");
	private static final Path f_request = location.resolve("request.xml");
	
	private static final int assertbatch = 5;
	private static final int pagecap = 100;
	
	private static String mainpagetext = null;
	
	static {
		WordForms[] polishWords = new WordForms[] {
			new WordForms(new String[] {"strona", "strony", "stron"}),
			new WordForms(new String[] {"wystąpienie", "wystąpienia", "wystąpień"}),
			new WordForms(new String[] {"pozycję", "pozycje", "pozycji"})
		};
		
		pluralPL = new Plural(PluralRules.POLISH, polishWords);
		
		numberFormatPL = NumberFormatter.withLocale(new Locale("pl", "PL")).grouping(GroupingStrategy.MIN2);
	}

	@Override
	public void selector(char op) throws Exception {
		switch (op) {
			case '1':
				wb = Login.createSession("pl.wiktionary.org");
				getRequest();
				wb.logout();
				break;
			case '2':
				int stats = Integer.parseInt(Files.readString(f_stats));
				System.out.println(stats);
				break;
			case '3':
				Files.writeString(f_stats, Integer.toString(375));
				break;
			case 'e':
				wb = Login.createSession("pl.wiktionary.org");
				edit(null, 0);
				wb.logout();
				break;
			case 'p':
				try {
					wb = Login.createSession("pl.wiktionary.org");
					patrol();
					wb.logout();
				} catch (IOException | UncheckedIOException e) {
					e.printStackTrace();
					Thread.sleep(10 * 60 * 1000);
					selector(op);
				}
				
				break;
			default:
				System.out.print("Número de operación incorrecto.");
		}
	}
	
	public static void getRequest() throws IOException, LoginException {
		List<LinkData> data = null;
		
		try {
			data = readRequest();
		} catch (UnsupportedOperationException e) {
			System.out.println(e.getMessage());
			return;
		}
		
		System.out.printf("Tamaño de la lista: %d%n", data.size());
		
		if (data.isEmpty()) {
			return;
		}
		
		List<String> output = new ArrayList<>(data.size());
		List<String> summarylist = new ArrayList<>(data.size());
		
		Map<Integer, LinkDiff> editcodes = new HashMap<>(data.size()*15);
		Map<String, OffsetDateTime> timestamps = new HashMap<>(data.size()*15);
		
		//ArrayList<Revision> revs = new ArrayList<>();
		Map<String, String[]> backlinkscache = new HashMap<>(10000);
		Map<String, String> contentscache = new HashMap<>(10000);
		
		MyRandom r = new MyRandom(5);
		
		for (LinkData entry : data) {
			if ((entry.lang != null && entry.lang.isEmpty()) || entry.links == null || entry.forms == null) {
				output.add(printErrorMessage(entry.target, "Nieprawidłowy format zgłoszenia."));
				continue;
			}
			
			if (entry.forms.length == 0) {
				try {
					switch (entry.templatelinker) {
						case "odmiana-rzeczownik-polski":
							entry.forms = fetchForms(entry.target);
							break;
						case "odmiana-czasownik-polski":
							output.add(printErrorMessage(entry.target, "Nieobsługiwany szablon odmiany."));
							continue;
						default:
							output.add(printErrorMessage(entry.target, "Nieobsługiwany szablon odmiany."));
							continue;
					}
				} catch (FileNotFoundException | UnsupportedOperationException e) {
					output.add(printErrorMessage(entry.target, e.getMessage()));
					continue;
				}
			}
			
			if (entry.links.length != entry.target.split(entry.hasDash ? "-" : "\\s").length) {
				output.add(printErrorMessage(entry.target, "Nie zgadza się liczba linków z liczbą wyrazów w haśle docelowym."));
				continue;
			}
			
			try {
				makeLinks(entry);
			} catch (UnsupportedOperationException e) {
				output.add(printErrorMessage(entry.target, e.getMessage()));
				continue;
			}

			summarylist.add(entry.target);
			List<String[]> lists = new ArrayList<>(entry.links.length);
			
			for (String link : entry.links) {
				if (!backlinkscache.containsKey(link)) {
					backlinkscache.put(link, wb.whatLinksHere(link, 0));
				}
				
				lists.add(backlinkscache.get(link));
			}
			
			String[] pages = null;
			
			if (entry.links.length > 1) {
				String[] temp = lists.get(0);
				
				for (int i = 1; i < lists.size(); i++) {
					temp = ArrayUtils.intersection(temp, lists.get(i));
				}
				
				pages = temp;
			} else {
				pages = lists.get(0);
			}
			
			System.out.printf("Enlaces encontrados para la combinación \"%s\": %d%n", entry.target, pages.length);
			
			//revs = wb.getTopRevision(new ArrayList<>(Arrays.asList(pages)));
			Map<String, String> contents = new HashMap<>(5000);
			List<String> fetchlist = new ArrayList<>(1000);
			
			for (String page : pages) {
				String key = page + "-" + entry.lang;
				
				if (contentscache.containsKey(key)) {
					contents.put(page, contentscache.get(key));
				} else {
					fetchlist.add(page);
				}
			}
			
			if (!fetchlist.isEmpty()) {
				List<PageContainer> temp = wb.getContentOfPages(fetchlist);
				
				for (PageContainer page : temp) {
					try {
						Page p = Page.wrap(page);
						Section s = p.getSection(entry.lang, false).get();
						contents.putIfAbsent(p.getTitle(), s.toString());
					} catch (Exception e) {
						contents.putIfAbsent(page.getTitle(), Utils.sanitizeWhitespaces(page.getText()));
					}
				}
				
				for (String fetched : fetchlist) {
					contentscache.put(fetched + "-" + entry.lang, contents.get(fetched));
				}
			}
			
			StringBuilder sb = new StringBuilder(1500);
			int pagecount = 0;
			int matchcount = 0;
			
			entry.diffmap = new LinkedHashMap<>(pages.length);
			List<String> backlinks = new ArrayList<>(250);
			
			for (Entry<String, String> contentmap : contents.entrySet()) {
				String backlink = contentmap.getKey();
				
				if (backlink.equals(entry.target)) {
					continue;
				}
				
				List<LinkDiff> info = getMatches(entry, contentmap.getValue());
				entry.diffmap.put(backlink, info);
				
				if (!info.isEmpty()) {
					sb.append(String.format("%n* [[%s]]%n", backlink));
					pagecount++;
					matchcount += info.size();
					backlinks.add(backlink);
					
					if (pagecount <= pagecap) {
						for (LinkDiff diff : info) {
							int editcode = r.generateInt();
							String codemark = String.format("<span style=\"display:none;\">%d</span>", editcode);
							sb.append(String.format("*: <nowiki>%s</nowiki>", diff.highlighted));
							sb.append(String.format(" {{red|(→&nbsp;'''<nowiki>%s</nowiki>''')}}%s%n", diff.newlink, codemark));
							diff.page = backlink;
							diff.targetlink = String.format("[[%s]]", entry.target);
							diff.lang = entry.lang;
							editcodes.put(editcode, diff);
						}
					}
				}
			}
			
			String template = printResults(entry, pagecount, matchcount);
			output.add(template.replace("$1", sb.toString()));
			
			if (!backlinks.isEmpty()) {
				Map<String, OffsetDateTime> tmp = new HashMap<>(timestamps);
				tmp.keySet().removeAll(timestamps.keySet());
				
				if (!tmp.isEmpty()) {
					wb.getTopRevision(new ArrayList<>(tmp.keySet())).stream()
						.forEach(rev -> timestamps.put(rev.getTitle(), rev.getTimestamp()));
				}
			}
		}
		
		/*for (Revision rev : revs) {
			timestamps.put(rev.getPage(), rev.getTimestamp());
		}*/
		
		Files.writeString(f_data, new XStream().toXML(data));
		Files.writeString(f_codes, new XStream().toXML(editcodes));
		Files.writeString(f_timestamps, new XStream().toXML(timestamps));
		
		mainpagetext = wb.getPageText(List.of(mainpage)).get(0);
		
		if (mainpagetext.indexOf(worklistheader) == -1) {
			System.out.printf("Imposible hallar la cabecera de la sección %s%n", worklistheader);
			return;
		}
		
		mainpagetext = mainpagetext.substring(0, mainpagetext.indexOf(worklistheader)) + worklistheader;
		mainpagetext += String.format("%s%n%s%n%n%s", worklistintro, String.join("\n", output), reportheader);
		
		String summary = String.format(
			"aktualizacja listy, dodano %s %s (%s)",
			numberFormatPL.format(data.size()), pluralPL.pl(data.size(), "pozycję"),
			String.join(", ", summarylist)
		);
		
		wb.edit(mainpage, mainpagetext, summary, false, false, -2, null);
	}
	
	public static void edit(String user, long revid) throws IOException, LoginException {
		@SuppressWarnings("unchecked")
		var editcodes = (Map<Integer, LinkDiff>) new XStream().fromXML(f_codes.toFile());
		
		@SuppressWarnings("unchecked")
		var timestamps = (Map<String, OffsetDateTime>) new XStream().fromXML(f_timestamps.toFile());
		
		System.out.printf("Modificaciones disponibles: %d%n", editcodes.size());
		
		if (editcodes.isEmpty()) {
			return;
		}
		
		String worklist = wb.getSectionText(mainpage, worklistsectionnumber);
		
		if (worklist.indexOf(worklistheader) != 0) {
			System.out.printf("Imposible hallar la cabecera de la sección %s%n", worklistheader);
			return;
		}
		
		String[] lines = worklist.split("\\n+");
		List<Integer> codes = new ArrayList<>(100);
		Map<String, List<LinkDiff>> pagemap = new HashMap<>();
		
		for (String line : lines) {
			int a = line.indexOf("<span style=\"display:none;\">");
			int b = line.indexOf("</span>", a);
			
			if (a == -1 || b == -1) {
				continue;
			}
			
			int code = Integer.parseInt(line.substring(a + "<span style=\"display:none;\">".length(), b).trim());
			codes.add(code);
		}
		
		System.out.printf("Modificaciones aceptadas: %d%n", codes.size());
		
		for (int code : codes) {
			if (!editcodes.containsKey(code)) {
				continue;
			}
			
			LinkDiff diff = editcodes.get(code);
			
			if (pagemap.containsKey(diff.page)) {
				pagemap.get(diff.page).add(diff);
			} else {
				List<LinkDiff> temp = new ArrayList<>();
				temp.add(diff);
				pagemap.put(diff.page, temp);
			}
		}
		
		wb.setThrottle(4000);
		int edited = 0;
		int iter = 0;
		boolean assertion = true;
		Map<String, String[]> errormap = new LinkedHashMap<>();
		final String errnotfound = "nie udało się wyszukać ciągu znaków „<nowiki>$1</nowiki>”";
		
		outer:
		for (Entry<String, List<LinkDiff>> entry : pagemap.entrySet()) {
			if (iter % assertbatch == 0) {
				assertion = assertBot();
				
				if (!assertion) {
					break;
				}
			}
			
			iter++;
			String page = entry.getKey();
			List<LinkDiff> list = entry.getValue();
			Set<String> difflist = new HashSet<>();
			String pagetext = wb.getPageText(List.of(page)).get(0);
			
			if (pagetext == null) {
				throw new FileNotFoundException("Page does not exist: " + page);
			}
			
			pagetext = Utils.sanitizeWhitespaces(pagetext);
			Map<Integer, LineInfo> linemap = new TreeMap<>(Integer::compare);
			
			int lineindex = 0;
			
			for (LinkDiff diff : list) {
				if (diff.lang != null && !diff.lang.isEmpty()) {
					Section section = Page.store("temp", pagetext).getSection(diff.lang).orElse(null);
					
					if (section == null) {
						errormap.put(page, new String[]{
							String.format("nie znaleziono sekcji językowej „%s”", diff.lang),
							diff.newlink
						});
						continue outer;
					}
					
					lineindex = pagetext.indexOf(section.toString());
					lineindex += diff.linestart;
				} else {
					lineindex = diff.linestart;
				}
				
				int endofline = 0;
				String line = null, match = null;
				difflist.add(diff.targetlink);
				
				try {
					endofline = pagetext.indexOf("\n", lineindex);
					endofline = (endofline == -1) ? pagetext.length() : endofline;
					line = pagetext.substring(lineindex, endofline);
					match = line.substring(diff.diffstart, diff.diffstart + diff.oldlink.length());
				} catch (StringIndexOutOfBoundsException e) {
					errormap.put(page, new String[]{
						String.format("%s<!-- %s -->", errnotfound.replace("$1", diff.oldlink), line),
						diff.newlink
					});
					continue outer;
				}
				
				if (!match.equals(diff.oldlink)) {
					errormap.put(page, new String[]{
						String.format("%s<!-- %s/%s -->", errnotfound.replace("$1", diff.oldlink), diff.oldlink, match),
						diff.newlink
					});
					continue outer;
				}
				
				LineInfo lineinfo = null;
				
				if (linemap.containsKey(lineindex)) {
					lineinfo = linemap.get(lineindex);
				} else {
					lineinfo = new LineInfo(line);
				}
				
				lineinfo.diffmap.put(diff.diffstart, diff);
				linemap.put(lineindex, lineinfo);
			}
			
			for (Entry<Integer, LineInfo> line : linemap.entrySet()) {
				int index = line.getKey();
				LineInfo lineinfo = line.getValue();
				String newline = lineinfo.line;
				
				for (Entry<Integer, LinkDiff> linediff : lineinfo.diffmap.entrySet()) {
					LinkDiff diff = linediff.getValue();
					
					try {
						newline = newline.substring(0, diff.diffstart) + diff.newlink + newline.substring(diff.diffstart + diff.oldlink.length());
					} catch (StringIndexOutOfBoundsException e) {
						errormap.put(page, new String[]{
							String.format("%s<!-- %s/%s -->", errnotfound.replace("$1", diff.oldlink), newline, diff.oldlink),
							diff.newlink
						});
						continue outer;
					}
				}
				
				pagetext = pagetext.substring(0, index) + newline + pagetext.substring(index + lineinfo.line.length());
			}
			
			OffsetDateTime timestamp = timestamps.get(page);
			String difflistmod = String.join(", ", difflist);
			
			String summary = String.format(
				"linkowanie na podstawie [[%s|listy]] (%s)",
				revid != 0
					? String.format("Specjalna:Niezmienny link/%d#Lista robocza", revid)
					: mainpage,
				difflistmod
			);
			
			if (user != null) {
				summary += String.format("; wer.: [[User:%1$s|%1$s]]", user);
			}
			
			try {
				wb.edit(page, pagetext, summary, false, true, -2, timestamp);
				edited++;
			} catch(CredentialException e) {
				errormap.put(page, new String[]{"strona zabezpieczona", difflistmod});
			} catch (ConcurrentModificationException e) {
				errormap.put(page, new String[]{"konflikt edycji", difflistmod});
			}
		}
		
		Files.deleteIfExists(f_codes);
		Files.deleteIfExists(f_timestamps);
		
		int stats = Integer.parseInt(Files.readString(f_stats));
		
		if (edited != 0) {
			stats += edited;
			Files.writeString(f_stats, Integer.toString(stats));
			System.out.printf("Nuevo total: %d%n", stats);
		}
		
		StringBuilder sb = new StringBuilder(1000);
		
		if (!assertion) {
			sb.append("'''Wstrzymano pracę bota'''\n\n");
		}
		
		sb.append("Rozmiar listy roboczej: ");
		sb.append(numberFormatPL.format(pagemap.size())).append(pluralPL.pl(pagemap.size(), " strona") + " (");
		sb.append(numberFormatPL.format(codes.size())).append(pluralPL.pl(codes.size(), " wystąpienie") + ")\n\n");
		
		sb.append("Zedytowanych: " + edited);
		
		if (edited != 0) {
			sb.append(" (<span class=\"plainlinks\">[https://pl.wiktionary.org/w/index.php?limit=" + edited + "&tagfilter=&title=Specjalna%3AWkład&contribs=user&target=PBbot&namespace=0 wkład bota]</span>)\n\n");
		} else {
			sb.append("\n\n");
		}
		
		if (!errormap.isEmpty()) {
			sb.append(String.format("'''Błędów: %d'''%n", errormap.size()));
			
			for (Entry<String, String[]> error : errormap.entrySet()) {
				sb.append(String.format("* [[%s]] (%s): %s%n", error.getKey(), error.getValue()[1], error.getValue()[0]));
			}
		}
		
		sb.append(String.format("%nEdycji do tej pory: %d", stats));
		
		worklist = wb.getPageText(List.of(mainpage)).get(0);
		
		if (worklist.indexOf(reportheader) == -1) {
			System.out.printf("Imposible hallar la cabecera de la sección %s%n", reportheader);
			return;
		}
		
		worklist = worklist.substring(0, worklist.indexOf(reportheader)) + reportheader + sb.toString();
		String summary = String.format("lista przetworzona; edytowanych stron: %d", edited);
		
		if (!errormap.isEmpty()) {
			summary += String.format(", błędów: %d", errormap.size());
		}
		
		wb.edit(mainpage, worklist, summary, false, false, -2, null);
	}
	
	public static void patrol() throws IOException, InterruptedException, LoginException {
		final int minutes = 5;
		final long interval = minutes * 60 * 1000;
		RequestInfo request = null;
		
		if (Files.exists(f_request)) {
			request = (RequestInfo) new XStream().fromXML(f_request.toFile());
		} else {
			request = new RequestInfo(0, "", null);
			Files.writeString(f_request, new XStream().toXML(request));
		}
		
		outer:
		while (true) {
			Revision currentRevision = wb.getTopRevision(mainpage);
			long pickedId = currentRevision.getID();
			
			if (pickedId == request.currentId) {
				System.out.printf("Durmiendo... (%d minutos)%n", minutes);
				Thread.sleep(interval);
				continue outer;
			}
			
			request.currentId = pickedId;
			String currentRequest = wb.getSectionText(mainpage, 2).trim();
			
			if (!currentRequest.contains(requestheader)) {
				System.out.printf("Imposible hallar la cabecera de la sección %s%n", requestheader);
				System.out.printf("Durmiendo... (%d minutos)%n", minutes);
				Thread.sleep(interval);
				continue outer;
			}
			
			if (!currentRequest.equals(request.currentRequest)) {
				Files.deleteIfExists(f_codes);
				getRequest();
				request.currentRequest = currentRequest;
			} else if (Files.exists(f_codes)) {
				OffsetDateTime startTimestamp = request.currentTimestamp; // earliest
				OffsetDateTime endTimestamp = currentRevision.getTimestamp(); // latest
				
				Wiki.RequestHelper helper = wb.new RequestHelper().withinDateRange(startTimestamp, endTimestamp);
				List<Revision> revs = wb.getPageHistory(mainpage, helper);
				
				for (Revision rev : revs) {
					if (rev.getUser().equals(wb.getCurrentUser().getUsername())) {
						break;
					}
					
					String diff = rev.diff(Wiki.PREVIOUS_REVISION);
					diff = StringEscapeUtils.unescapeXml(diff);
					diff = diff.replaceAll("</?ins.*?>", "");
					diff = diff.replace("\n", "");
					
					if (diff.matches(".*?<td class=\"diff-addedline\"><div>\\* *?Zatwierdzone: *?tak *?</div></td>.*")) {
						String username = rev.getUser();
						User user = wb.getUsers(List.of(username)).get(0);
						
						if (user.getGroups().contains("editor")) {
							try {
								edit(username, pickedId);
							} catch (Exception e) {
								System.out.println("Fallo desconocido");
								System.out.println(e);
								Files.deleteIfExists(f_codes);
							}
						} else {
							System.out.printf("Prueba de edición fallida, falta de privilegios (%s)%n", username);
						}
						
						break;
					}
				}
				
				request.currentTimestamp = endTimestamp;
			}
			
			Files.writeString(f_request, new XStream().toXML(request));
			System.out.printf("Durmiendo... (%d minutos)%n", minutes);
			Thread.sleep(interval);
		}
	}
	
	private static boolean assertBot() throws IOException {
		String content = wb.getPageText(List.of(mainpage)).get(0);
		int a = content.indexOf(reportheader);
		
		if (a == -1) {
			return false;
		}
		
		if (content.substring(a).trim().equals(reportheader.trim())) {
			return true;
		} else {
			return false;
		}
	}
	
	private static List<LinkData> readRequest() throws IOException {
		mainpagetext = wb.getSectionText(mainpage, requestsectionnumber);
		
		if (mainpagetext.indexOf(requestheader) != 0) {
			throw new UnsupportedOperationException("Imposible hallar la cabecera de la sección " + requestheader);
		}
		
		List<LinkData> data = new ArrayList<>();
		String[] lines = mainpagetext.split("\\n");
		LinkData aux = null;
		int opt = 0;
		
		for (String line : lines) {
			if (line.startsWith("**")) {
				line = line.substring(2).trim();
				
				switch (++opt) {
					case 1:
						aux.lang = line.equals("wszystkie") ? null : line;
						break;
					case 2:
						aux.links = line.split("\\s*?,\\s?");
						break;
					case 3:
						if (line.startsWith("odmiana-")) {
							aux.forms = new String[]{};
							aux.templatelinker = line;
						} else {
							String[] forms = line.split("\\s*?,\\s*");
							Set<String> set = Set.of(forms);
							aux.forms = set.toArray(String[]::new);
						}
						break;
					default:
						throw new UnsupportedOperationException("Formato de petición incorrecto");
				}
			} else if (line.startsWith("*")) {
				String target = line.substring(1).trim();
				data.add(aux = new LinkData(target));
				opt = 0;
				
				if (target.contains("-")) {
					aux.hasDash = true;
				}
			}
		}
		
		return data;
	}

	private static String printErrorMessage(String target, String message) {
		return String.format("%n=== [[%s]] ===%n%s%n", target, message);
	}
	
	private static String printResults(LinkData entry, int pagecount, int matchcount) {
		StringBuilder sb = new StringBuilder(500);
		
		sb.append(String.format("%n=== [[%s]] ===%n", entry.target));
		
		sb.append(String.format(
			": '''sekcja językowa:''' %s%n",
			(entry.lang == null ? "wszystkie" : entry.lang)
		));
		
		sb.append(String.format(
			": '''%s:''' [[%s]]%n",
			(entry.links.length > 1 ? "linki" : "link"),
			String.join("]], [[", entry.links)
		));
		
		sb.append(": '''wyszukiwane ciągi znaków:''' ");
		
		List<String> tempforms = new ArrayList<>();
		
		if (entry.linkedFormsLower != null && !entry.linkedFormsLower.isEmpty()) {
			tempforms.addAll(entry.linkedFormsLower);
		}
		
		if (entry.linkedFormsUpper != null && !entry.linkedFormsUpper.isEmpty()) {
			tempforms.addAll(entry.linkedFormsUpper);
		}
		
		if (!tempforms.isEmpty()) {
			sb.append(String.format("<nowiki>%s</nowiki>%n", String.join(", ", tempforms)));
		} else {
			sb.append("''brak''\n");
		}
		
		sb.append(": '''wyniki:''' ");
		sb.append(numberFormatPL.format(pagecount)).append(pluralPL.pl(pagecount, " strona") + ", ");
		sb.append(numberFormatPL.format(matchcount)).append(pluralPL.pl(matchcount, " wystąpienie"));
		
		if (pagecount > pagecap) {
			sb.append(" (ograniczono do " + pagecap + " stron)");
		}
		
		sb.append("\n----\n");
		sb.append("$1");
		
		return sb.toString();
	}
	
	private static void makeLinks(LinkData entry) {
		String sep = entry.hasDash ? "-" : " ";
		
		for (String form : entry.forms) {
			String[] words = null;
			
			if (entry.hasDash) {
				words = form.split("-");
			} else {
				words = form.split("\\s");
			}
			
			if (words.length != entry.links.length) {
				throw new UnsupportedOperationException("Każda z podanych form powinna składać się z takiej samej ilości wyrazów co liczba linków składowych wyrazu docelowego.");
			}
			
			if (words[0].toLowerCase().equals(words[0])) {
				String link = linker(words, entry.links, sep);
				entry.linkedFormsLower.add(link);
				entry.lowerLinksMap.put(link, linker(form, entry.target));
				words[0] = words[0].substring(0, 1).toUpperCase() + words[0].substring(1);
			}
			
			String link = linker(words, entry.links, sep);
			entry.linkedFormsUpper.add(link);
			form = form.substring(0, 1).toUpperCase() + form.substring(1);
			entry.upperLinksMap.put(link, linker(form, entry.target));
		}
	}
	
	private static String linker(String word, String source) {
		String[] words = new String[]{word};
		String[] sources = new String[]{source};
		return linker(words, sources, "");
	}
	
	private static String linker(String[] words, String[] source, String sep) {
		List<String> links = new ArrayList<>(words.length);
		
		for (int i = 0; i < words.length; i++) {
			if (words[i].equals(source[i])) {
				links.add(String.format("[[%s]]", source[i]));
			} else if (words[i].indexOf(source[i]) == 0) {
				String ending = words[i].substring(source[i].length());
				
				if (hasForeignChars(ending)) {
					links.add(String.format("[[%s|%s]]", source[i], words[i]));
				} else {
					links.add(String.format("[[%s]]%s", source[i], ending));
				}
			} else {
				links.add(String.format("[[%s|%s]]", source[i], words[i]));
			}
		}
		
		return String.join(sep, links);
	}
	
	private static boolean hasForeignChars(String s) {
		String locale = "aąbcćdeęfghijklłmnńoópqrsśtuvwxyźżAĄBCĆDEĘFGHIJKLŁMNŃOÓPQRSŚTUVWXYZŹŻ";
		String[] chars = s.split("");
		
		for (String c : chars) {
			if (locale.indexOf(c) == -1) {
				return true;
			}
		}
		
		return false;
	}
	
	private static String[] fetchForms(String title) throws IOException {
		String content = wb.getPageText(List.of(title)).get(0);
		
		if (content == null) {
			throw new FileNotFoundException("Page does not exist: " + title);
		}
		
		final String ERRMSGmissing = "Nie znaleziono szablonu odmiany w kodzie hasła.";
		final String ERRMSGformat = "Nie udało się uzyskać form fleksyjnych na podstawie szablonu odmiany; wpisz ją ręcznie.";
		
		int a = content.indexOf("{{odmiana-rzeczownik-polski") + 1;
		
		if (a == -1) {
			throw new UnsupportedOperationException(ERRMSGmissing);
		}
		
		int b = content.indexOf("{{przykłady", a);
		
		if (b == -1) {
			throw new UnsupportedOperationException(ERRMSGformat);
		}
		
		String declension = content.substring(a, b);
		
		if (declension.contains("{{odmiana-rzeczownik-polski")) {
			throw new UnsupportedOperationException(ERRMSGformat);
		}
		
		declension = declension.replaceAll("\\{\\{.*?\\}\\}", "");
		declension = declension.replaceAll("<ref[^/]*?>.*?</ref>", "");
		declension = declension.replaceAll("<ref.*?[ /]>", "");
		
		if (declension.contains("{{") || declension.contains("/") || declension.contains("'")) {
			throw new UnsupportedOperationException(ERRMSGformat);
		}
		
		Matcher m = Pattern.compile("^\\|.*?=(.*?)$", Pattern.MULTILINE).matcher(declension);
		Set<String> forms = new HashSet<>(14);
		
		while (m.find()) {
			String group = m.group(1).trim();
			
			if (!group.equals("")) {
				forms.add(group);
			}
		}
		
		return forms.toArray(String[]::new);
	}
	
	private static List<LinkDiff> getMatches(LinkData entry, String text) {
		List<LinkDiff> list = new ArrayList<>();
		text += "\n";
		
		int a = 0, b = 0;
		
		while ((b = text.indexOf("\n", a)) != -1) {
			String line = text.substring(a, b);
			List<LinkDiff> linediffs = new ArrayList<>();
			
			for (String form : entry.linkedFormsUpper) {
				String targetlink = entry.upperLinksMap.get(form);
				linediffs.addAll(searchLinkedForms(line, a, form, targetlink));
			}
			
			InspectLinkList(linediffs);
			list.addAll(linediffs);
			linediffs = new ArrayList<>();
			
			if (entry.isUpperCase) {
				a = b + 1;
				continue;
			}
			
			for (String form : entry.linkedFormsLower) {
				String targetlink = entry.lowerLinksMap.get(form);
				linediffs.addAll(searchLinkedForms(line, a, form, targetlink));
			}
			
			InspectLinkList(linediffs);
			list.addAll(linediffs);
			
			a = b + 1;
		}
		
		return list;
	}
	
	private static List<LinkDiff> searchLinkedForms(String line, int linestart, String oldlink, String newlink) {
		List<LinkDiff> diffs = new ArrayList<>();
		int index = 0;
		
		while ((index = line.indexOf(oldlink, index)) != -1) {
			LinkDiff diff = new LinkDiff(oldlink, newlink);
			diff.diffstart = index++;
			diff.linestart = linestart;
			diff.highlighted = line.substring(0, diff.diffstart) +
				"</nowiki>{{red|<nowiki>" + oldlink + "</nowiki>}}<nowiki>" +
				line.substring(diff.diffstart + oldlink.length());
			
			diffs.add(diff);
		}
		
		return diffs;
	}
	
	private static void InspectLinkList(List<LinkDiff> list) {
		Iterator<LinkDiff> i = list.iterator();
		
		// http://stackoverflow.com/a/1196612
		// http://stackoverflow.com/a/223929
		while (i.hasNext()) {
			LinkDiff diff1 = i.next();
			String link1 = diff1.oldlink;
			
			for (LinkDiff diff2 : list) {
				String link2 = diff2.oldlink;
				
				if (diff2.equals(diff1)) {
					continue;
				}
				
				if (
					diff2.diffstart == diff1.diffstart &&
					link2.indexOf(link1) == 0
				) {
					i.remove();
					break;
				}
			}
		}
	}
	
	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.out.print("Mode of operation: ");
			char op = (char) System.in.read();
			new LinkManager().selector(op);
		} else {
			char op = args[0].toCharArray()[0];
			new LinkManager().selector(op);
		}
	}

	private static class LinkData implements Serializable {
		private static final long serialVersionUID = 1L;
		
		String target;
		boolean isUpperCase;
		String lang = "";
		
		String[] links;
		String[] forms;
		
		Map<String, String> lowerLinksMap;
		Map<String, String> upperLinksMap;
		
		String templatelinker;
		List<String> linkedFormsLower;
		List<String> linkedFormsUpper;
		
		boolean hasDash = false;
		
		Map<String, List<LinkDiff>> diffmap;
		
		LinkData(String target) {
			this.target = target;
			isUpperCase = !target.substring(0, 1).toLowerCase().equals(target.substring(0, 1));
			
			if (!isUpperCase) {
				linkedFormsLower = new ArrayList<>();
				linkedFormsUpper = new ArrayList<>();
				
				lowerLinksMap = new HashMap<>();
				upperLinksMap = new HashMap<>();
			} else {
				linkedFormsLower = null;
				linkedFormsUpper = new ArrayList<>();
				
				lowerLinksMap = null;
				upperLinksMap = new HashMap<>();
			}
		}
	}

	private static class LineInfo implements Serializable {
		private static final long serialVersionUID = 1L;
		String line;
		Map<Integer, LinkDiff> diffmap;
		
		LineInfo(String line) {
			this.line = line;
			diffmap = new TreeMap<>((x, y) -> -Integer.compare(x, y));
		}
	}

	private static class LinkDiff implements Serializable {
		private static final long serialVersionUID = 1L;
		String page;
		String targetlink;
		String lang;
		String oldlink;
		String newlink;
		String highlighted;
		int linestart;
		int diffstart;
		
		LinkDiff(String oldlink, String newlink) {
			this.oldlink = oldlink;
			this.newlink = newlink;
		}
	}

	private static class RequestInfo implements Serializable {
		private static final long serialVersionUID = 4878770923056540962L;
		long currentId;
		String currentRequest;
		OffsetDateTime currentTimestamp;
		
		RequestInfo(long id, String request, OffsetDateTime timestamp) {
			currentId = id;
			currentRequest = request;
			currentTimestamp = timestamp;
		}
	}
	
	private static class MyRandom {
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
}