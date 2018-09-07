package com.github.wikibot.main;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.stream.IntStream;

import javax.security.auth.login.CredentialNotFoundException;
import javax.security.auth.login.LoginException;

public class PLWikt extends Wikibot {
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
	
	public synchronized void review(Revision rev, String comment) throws LoginException, IOException {
		// TODO: move to new FlaggedRevsWiki with support for clone contructors
		throttle();
		
		User user = getCurrentUser();
		
		if (user == null || !user.isAllowedTo("review")) {
            throw new CredentialNotFoundException("Permission denied: cannot review.");
		}
		
		Map<String, String> getparams = new HashMap<>();
		getparams.put("action", "review");
		
		Map<String, Object> postparams = new HashMap<>();
		
		if (comment != null && !comment.isEmpty()) {
			postparams.put("comment", comment);
		}
		
		postparams.put("flag_accuracy", "1");
		postparams.put("revid", Long.toString(rev.getRevid()));
		postparams.put("token", getToken("csrf"));
		
		String response = makeHTTPRequest(apiUrl, getparams, postparams, "review");
		checkErrorsAndUpdateStatus(response, "review");
		log(Level.INFO, "review", "Successfully reviewed revision of page " + rev.getPage());
	}
}
