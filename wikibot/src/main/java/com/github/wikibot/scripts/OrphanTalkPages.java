package com.github.wikibot.scripts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.security.auth.login.LoginException;

import org.wikipedia.Wiki;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.Login;

public class OrphanTalkPages {
	public static void main(String[] args) throws IOException, LoginException {
		Wikibot wb = Wikibot.newSession("pl.wiktionary.org");
		Login.login(wb);
		Map<String, Integer> namespaceIdentifiers = wb.getNamespaces();
		
		Integer[] namespaces = new Integer[]{
			Wiki.TALK_NAMESPACE,
			//Wiki.USER_TALK_NAMESPACE,
			Wiki.PROJECT_TALK_NAMESPACE,
			Wiki.FILE_TALK_NAMESPACE,
			Wiki.MEDIAWIKI_TALK_NAMESPACE,
			Wiki.TEMPLATE_TALK_NAMESPACE,
			Wiki.HELP_TALK_NAMESPACE,
			Wiki.CATEGORY_TALK_NAMESPACE,
			namespaceIdentifiers.get("Aneks"),
			namespaceIdentifiers.get("Indeks"),
			namespaceIdentifiers.get("Portal")
		};
		
		List<String> list = new ArrayList<>(3000);
		
		for (int namespace : namespaces) {
			list.addAll(wb.listPages("", null, namespace));
		}
		
		System.out.printf("Tamaño de la lista: %d%n", list.size());
		Files.write(Paths.get("./test.txt"), list);
		
		List<String> targets = new ArrayList<>(list.size());
		
		for (String talkpage : list) {
			int namespace = wb.namespace(talkpage);
			String title = talkpage.substring(talkpage.indexOf(':') + 1);
			
			if (namespace != 1) {
				title = wb.namespaceIdentifier(namespace - 1) + ":" + title;
			}
			
			targets.add(title);
		}
		
		boolean[] exist = wb.exists(targets);
		List<String> missing = new ArrayList<>(targets.size());
		
		for (int i = 0; i < targets.size(); i++) {
			if (!exist[i]) {
				missing.add(targets.get(i));
			}
		}
		
		System.out.printf("Tamaño de la lista: %d%n", missing.size());
		Files.write(Paths.get("./test2.txt"), missing);
	}
}
