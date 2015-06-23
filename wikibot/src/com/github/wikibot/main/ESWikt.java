package com.github.wikibot.main;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.security.auth.login.FailedLoginException;

import org.wikiutils.IOUtils;
import org.wikiutils.ParseUtils;

import com.github.wikibot.utils.PageContainer;

public class ESWikt extends Wikibot {
	private static final long serialVersionUID = 2142284575098809683L;

	public static final int ANNEX_NAMESPACE = 100;
	public static final int ANNEX_TALK_NAMESPACE = 101;
	
	public ESWikt() {
		super("es.wiktionary.org");
	}
	
	public void readXmlDump(Consumer<PageContainer> cons) throws IOException {
		readXmlDump("eswiktionary", cons);
	}
	
	public static void main (String[] args) throws IOException, FailedLoginException {
		ESWikt wb = new ESWikt();
		List<String> titles = new ArrayList<String>(1000);
		//Pattern patt = Pattern.compile("\\{\\{ *?(?:ES|[^-]+-ES) *?\\|.*?(escritura\\d? *?=.+)\\}\\}");
		Pattern patt = Pattern.compile("^ *?\\{\\{ *?(?:.*?-)?ES *?(?:\\|[^\n]*)?\\}\\} *$", Pattern.MULTILINE);
		
		wb.readXmlDump(page -> {
			Matcher m = patt.matcher(page.getText());
			boolean found = false;
			String title = page.getTitle();
			
			while (m.find()) {
				String template = m.group().trim();
				HashMap<String, String> params = ParseUtils.getTemplateParametersWithValue(template);
				String param1 = params.get("ParamWithoutName1");
				
				if (param1 == null || param1.isEmpty()) {
					continue;
				}
				
				if (param1.contains("[[")) {
					return;
				}
				
				param1 = param1.replace("ʼ", "'");
				
				if (!param1.equals("{{PAGENAME}}") && !param1.equals(title.replace("ʼ", "'"))) {
					found = true;
					break;
				}
			}
			
			if (found) {
				titles.add(title);
			}
		});
		
		System.out.printf("Tamaño de la lista: %d%n", titles.size());
		IOUtils.writeToFile(String.join("\n", titles), "./test.txt");
	}
}
