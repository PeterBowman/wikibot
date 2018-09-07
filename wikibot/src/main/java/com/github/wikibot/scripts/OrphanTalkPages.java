package com.github.wikibot.scripts;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.security.auth.login.LoginException;

import com.github.wikibot.main.PLWikt;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Users;

public class OrphanTalkPages {
	public static void main(String[] args) throws IOException, LoginException {
		PLWikt wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
		Map<String, Integer> namespaceIdentifiers = wb.getNamespaces();
		
		Integer[] namespaces = new Integer[]{
			PLWikt.TALK_NAMESPACE,
			//PLWikt.USER_TALK_NAMESPACE,
			PLWikt.PROJECT_TALK_NAMESPACE,
			PLWikt.FILE_TALK_NAMESPACE,
			PLWikt.MEDIAWIKI_TALK_NAMESPACE,
			PLWikt.TEMPLATE_TALK_NAMESPACE,
			PLWikt.HELP_TALK_NAMESPACE,
			PLWikt.CATEGORY_TALK_NAMESPACE,
			namespaceIdentifiers.get("Aneks"),
			namespaceIdentifiers.get("Indeks"),
			namespaceIdentifiers.get("Portal")
		};
		
		List<String> list = new ArrayList<>(3000);
		
		for (int namespace : namespaces) {
			list.addAll(Arrays.asList(wb.listPages("", null, namespace)));
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
		
		Map<String, Object>[] infos = wb.getPageInfo(targets.toArray(new String[targets.size()]));
		List<String> missing = new ArrayList<>(targets.size());
		
		for (int i = 0; i < targets.size(); i++) {
			if (!(boolean)infos[i].get("exists")) {
				missing.add(targets.get(i));
			}
		}
		
		System.out.printf("Tamaño de la lista: %d%n", missing.size());
		Files.write(Paths.get("./test2.txt"), missing);
		
		Login.saveSession(wb);
	}
}
