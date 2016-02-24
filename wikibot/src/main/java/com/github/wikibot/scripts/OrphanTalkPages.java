package com.github.wikibot.scripts;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import javax.security.auth.login.LoginException;

import org.wikiutils.IOUtils;

import com.github.wikibot.main.PLWikt;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.Users;

public class OrphanTalkPages {
	@SuppressWarnings({ "rawtypes" })
	public static void main(String[] args) throws IOException, LoginException {
		PLWikt wb = Login.retrieveSession(Domains.PLWIKT, Users.USER2);
		Integer[] namespaces = new Integer[]{
			PLWikt.TALK_NAMESPACE,
			//PLWikt.USER_TALK_NAMESPACE,
			PLWikt.PROJECT_TALK_NAMESPACE,
			PLWikt.FILE_TALK_NAMESPACE,
			PLWikt.MEDIAWIKI_TALK_NAMESPACE,
			PLWikt.TEMPLATE_TALK_NAMESPACE,
			PLWikt.HELP_TALK_NAMESPACE,
			PLWikt.CATEGORY_TALK_NAMESPACE,
			PLWikt.ANNEX_TALK_NAMESPACE,
			PLWikt.INDEX_TALK_NAMESPACE,
			PLWikt.PORTAL_TALK_NAMESPACE
		};
		
		List<String> list = new ArrayList<>(3000);
		
		for (int namespace : namespaces) {
			list.addAll(Arrays.asList(wb.listPages("", null, namespace)));
		}
		
		System.out.printf("Tamaño de la lista: %d%n", list.size());
		IOUtils.writeToFile(String.join("\n", list), "./test.txt");
		
		List<String> targets = new ArrayList<>(list.size());
		
		for (String talkpage : list) {
			int namespace = wb.namespace(talkpage);
			String title = talkpage.substring(talkpage.indexOf(':') + 1);
			
			if (namespace != 1) {
				title = wb.namespaceIdentifier(namespace - 1) + ":" + title;
			}
			
			targets.add(title);
		}
		
		Map[] infos = wb.getPageInfo(targets.toArray(new String[targets.size()]));
		List<String> missing = new ArrayList<>(targets.size());
		
		for (int i = 0; i < targets.size(); i++) {
			if (!(boolean)infos[i].get("exists")) {
				missing.add(targets.get(i));
			}
		}
		
		System.out.printf("Tamaño de la lista: %d%n", missing.size());
		IOUtils.writeToFile(String.join("\n", missing), "./test2.txt");
		
		Login.saveSession(wb);
	}
}