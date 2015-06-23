package com.github.wikibot.main;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.IntStream;

import javax.security.auth.login.CredentialNotFoundException;
import javax.security.auth.login.LoginException;

import com.github.wikibot.utils.PageContainer;

public class PLWikt extends Wikibot {
	private static final long serialVersionUID = -4033360410848180018L;
	
	public static final int ANNEX_NAMESPACE = 100;
	public static final int ANNEX_TALK_NAMESPACE = 101;
	public static final int INDEX_NAMESPACE = 102;
	public static final int INDEX_TALK_NAMESPACE = 103;
	public static final int PORTAL_NAMESPACE = 104;
	public static final int PORTAL_TALK_NAMESPACE = 105;
	
	public PLWikt() {
    	super("pl.wiktionary.org");
    }
	
	/**
	 * Gets the id of a particular section based on its name. In case it's
	 * used more than once, it always returns the id for the first
	 * encountered header for that name.
	 * 
	 * @param page the name of the page
	 * @param section the name of the section header
	 * @return the id for that section
	 * @throws IOException
	 * @throws UnsupportedOperationException no sections detected or couldn't
	 * find the specified section
	 */
	public int getSectionId(String page, String section) throws IOException, UnsupportedOperationException {
		Map<String, String> lhm = getSectionMap(page);
		String header = String.format("%s (<span>%s</span>)", page, section);
		
		if (lhm.isEmpty() || !lhm.containsValue(header)) {
			String errMsg = String.format("Missing section \"%s\" from page \"%s\"", section, page);
			throw new UnsupportedOperationException(errMsg);
		}
		
		String[] sections = lhm.values().toArray(new String[lhm.size()]);
		
		int id = IntStream.rangeClosed(1, sections.length)
			.filter(i -> sections[i - 1].equals(header))
			.findFirst()
			.getAsInt();
		
		return id;
	}
	
	public synchronized void review(Revision rev) throws LoginException, IOException {
		User user = getCurrentUser();
		
		if (user == null || !user.isAllowedTo("review")) {
            throw new CredentialNotFoundException("Permission denied: cannot review.");
		}
		
		@SuppressWarnings("rawtypes")
		Map info = getPageInfo(rev.getPage());
		String token = URLEncoder.encode((String)info.get("token"), "UTF-8");
		
		StringBuilder sb = new StringBuilder();
		
		sb.append("flag_accuracy=1&");
		sb.append("revid=" + rev.getRevid() + "&");
		sb.append("token=" + token);
				
		String response = post(apiUrl + "action=review", sb.toString(), "review");

		if (!response.contains("error ")) {
			log(Level.INFO, "review", "Successfully reviewed revision of page " + rev.getPage());
		} else {
			log(Level.SEVERE, "review", "REVIEW FAILED");
		}
	}
	
	public void readXmlDump(Consumer<PageContainer> cons) throws IOException {
		readXmlDump("plwiktionary", cons);
	}
}
