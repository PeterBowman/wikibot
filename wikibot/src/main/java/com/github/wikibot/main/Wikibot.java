package com.github.wikibot.main;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.logging.Level;

import javax.security.auth.login.LoginException;

import org.wikipedia.WMFWiki;
import org.wikipedia.Wiki;

import com.github.wikibot.utils.PageContainer;

public class Wikibot extends WMFWiki {
	protected Wikibot(String domain) {
    	super(domain);
    }
    
    public static Wikibot newSession(String domain) {
    	var wb = new Wikibot(domain);
    	wb.initVars();
    	return wb;
    }
	
    public List<PageContainer> getContentOfPages(Collection<String> pages) throws IOException {
    	var getparams = Map.of(
    		"action", "query",
    		"prop", "revisions",
    		"rvprop", "timestamp|content",
    		"rvslots", "main"
    	);
		return getListedContent(new HashMap<>(getparams), pages, "getContents", "titles", this::parseContentLine);
	}
    
    public List<PageContainer> getContentOfPageIds(Collection<Long> pageids) throws IOException {
    	var getparams = Map.of(
    		"action", "query",
    		"prop", "revisions",
    		"rvprop", "timestamp|content",
    		"rvslots", "main"
    	);
		var stringified = pageids.stream().map(Object::toString).toList();
		return getListedContent(new HashMap<>(getparams), stringified, "getContents", "pageids", this::parseContentLine);
	}
    
    public List<PageContainer> getContentOfRevIds(Collection<Long> revids) throws IOException {
    	var getparams = Map.of(
    		"action", "query",
    		"prop", "revisions",
    		"rvprop", "timestamp|content",
    		"rvslots", "main"
    	);
		var stringified = revids.stream().map(Object::toString).toList();
		return getListedContent(new HashMap<>(getparams), stringified, "getContents", "revids", this::parseContentLine);
    }
	
	/**
	 * Gets the contents of the members of a category filtered by an optional
	 * section.
	 * 
	 * @param category the name of the category
	 * @param section the language section or <tt>null</tt> to retrieve the
	 * contents of the entire page
	 * @param ns a list of namespaces to filter by, empty = all namespaces
	 * @return a Map containing page titles and its respective contents
	 * @throws IOException
	 */
	public List<PageContainer> getContentOfCategorymembers(String category, int... ns) throws IOException {
		var getparams = Map.of(
			"prop", "revisions",
			"rvprop", "timestamp|content",
			"rvslots", "main",
			"generator", "categorymembers",
			"gcmtitle", "Category:" + normalize(removeNamespace(category, Wiki.CATEGORY_NAMESPACE)),
			"gcmtype", "page",
			"gcmnamespace", constructNamespaceString(ns)
		);
		
		return getGeneratedContent(getparams, "gcm");
	}
	
	public List<PageContainer> getContentOfTransclusions(String page, int... ns) throws IOException {
		var getparams = Map.of(
			"prop", "revisions",
			"rvprop", "timestamp|content",
			"rvslots", "main",
			"generator", "embeddedin",
			"geititle", normalize(page),
			"geinamespace", constructNamespaceString(ns)
		);
		
		return getGeneratedContent(getparams, "gei");
	}
	
	public List<PageContainer> getContentOfBacklinks(String page, int... ns) throws IOException {
		var getparams = Map.of(
			"prop", "revisions",
			"rvprop", "timestamp|content",
			"rvslots", "main",
			"generator", "backlinks",
			"gbltitle", normalize(page),
			"gblnamespace", constructNamespaceString(ns)
		);
		
		return getGeneratedContent(getparams, "gbl");
	}
	
	private <T> List<T> getListedContent(Map<String, String> getparams, Collection<String> titles, String caller,
			String postParamName, BiConsumer<String, List<T>> biCons)
	throws IOException {
		var chunks = constructTitleString(new ArrayList<>(titles));
		var list = new ArrayList<T>(titles.size());
		var postparams = new HashMap<String, Object>();
		final int totalChunks = chunks.size();
		
		while (!chunks.isEmpty() || getparams.containsKey("continue")) {
			final String localCaller;
			
			if (!getparams.containsKey("continue")) {
				postparams.put(postParamName, chunks.remove(0));
				localCaller = String.format("%s (%d/%d)", caller, totalChunks - chunks.size(), totalChunks);
			} else {
				localCaller = String.format("%s (%d/%d) [continuation]", caller, totalChunks - chunks.size(), totalChunks);
			}
			
			var line = makeApiCall(getparams, postparams, localCaller);
			detectUncheckedErrors(line, null, null);
			
			if (line.contains("<continue ")) {
				int a = line.indexOf("<continue ") + 9;
				int b = line.indexOf(" />", a);
				var cont = line.substring(a, b);
				
				for (var contpair : cont.split("\" ")) {
					contpair = " " + contpair.trim();
					var contattr = contpair.substring(0, contpair.indexOf("=\""));
					getparams.put(contattr.trim(), parseAttribute(cont, contattr, 0));
				}
			} else {
				getparams.keySet().removeIf(param -> param.endsWith("continue"));
			}
			
			biCons.accept(line, list);
		}
		
		log(Level.INFO, "getListedContent", "Successfully retrieved page contents (" + list.size() + " revisions)");
		return list;
	}

	private List<PageContainer> getGeneratedContent(Map<String, String> getparams, String queryPrefix) throws IOException {
		return makeListQuery(queryPrefix, getparams, null, "getGeneratedContent", -1, this::parseContentLine);
	}
	
	private void parseContentLine(String line, List<PageContainer> list) {
		int closeTag = 0;
		int pageIndex = 0;
		
		while ((pageIndex = line.indexOf("<page ", pageIndex + 1)) != -1) {
			closeTag = line.indexOf("/>", pageIndex);
			
			if (closeTag != -1 && closeTag < line.indexOf(">", pageIndex)) {
				continue;
			}
			
			var page = line.substring(pageIndex, line.indexOf("</page>", pageIndex));
			var title = decode(parseAttribute(page, "title", 0));
			int revIndex = 0;
			
			while ((revIndex = page.indexOf("<rev ", revIndex + 1)) != -1) {
				closeTag = page.indexOf("/>", revIndex);
				
				if (closeTag != -1 && closeTag < page.indexOf(">", revIndex)) {
					continue;
				}
				
				var rev = page.substring(revIndex, page.indexOf("</rev>", revIndex));
				var timestamp = OffsetDateTime.parse(parseAttribute(rev, "timestamp", 0));
				int slotIndex = 0;
				
				while ((slotIndex = rev.indexOf("<slot ", slotIndex + 1)) != -1) {
					var role = parseAttribute(rev, "role", slotIndex);
					
					if (!"main".equals(role)) {
						continue;
					}
					
					closeTag = rev.indexOf("/>", slotIndex);
					
					if (closeTag == -1 || closeTag > rev.indexOf('>', slotIndex)) {
						var start = rev.indexOf('>', slotIndex) + 1;
						var end = rev.indexOf("</slot>", start);
						var text = decode(rev.substring(start, end));
						list.add(new PageContainer(title, text, timestamp));
					}
					
					break;
				}
			}
		}
	}
	
	public String expandTemplates(String text) throws IOException {
		return expandTemplates(text, null);
	}
	
	public String expandTemplates(String text, String title) throws IOException {
		var getparams = new HashMap<String, String>();
		getparams.put("action", "expandtemplates");
		getparams.put("prop", "wikitext");
		if (title != null)
			getparams.put("title", normalize(title));
		var postparams = new HashMap<String, Object>();
		postparams.put("text", text);
		var line = makeApiCall(getparams, postparams, "expandTemplates");
		detectUncheckedErrors(line, null, null);
		
		int a = line.indexOf("<wikitext ");
		a = line.indexOf(">", a) + 1;
		int b = line.indexOf("</wikitext>", a);
		
		return decode(line.substring(a, b));
	}
	
	public List<Wiki.Revision> getTopRevision(List<String> titles) throws IOException {
		var getparams = Map.of(
			"action", "query",
			"prop", "revisions",
			"rvprop", "timestamp|user|ids|flags|size|comment|sha1",
			"meta", "tokens",
			"type", "rollback"
		);
        
		BiConsumer<String, List<Wiki.Revision>> biCons = (line, list) -> {
			for (int page = line.indexOf("<page "); page != -1; page = line.indexOf("<page ", ++page)) {
				String title = parseAttribute(line, "title", page);
				int start = line.indexOf("<rev ", page);
				int end = line.indexOf("/>", start);
				list.add(parseRevision(line.substring(start, end), title));
			}
		};
		
		return getListedContent(getparams, titles, "getTopRevision", "titles", biCons);
    }
	
	public List<Wiki.Revision> recentChanges(OffsetDateTime starttimestamp, OffsetDateTime endtimestamp, Map<String, Boolean> rcoptions,
			List<String> rctypes, boolean toponly, String excludeUser, int... ns) throws IOException
	{
		var getparams = new HashMap<String, String>();
		getparams.put("list", "recentchanges");
		getparams.put("rcdir", "newer");
		getparams.put("rcprop", "title|ids|user|timestamp|flags|comment|sizes|sha1");
		getparams.put("rcnamespace", constructNamespaceString(ns));
        
        if (toponly) {
        	getparams.put("rctoponly", "1");
        }

        if (excludeUser != null) {
        	getparams.put("rcexcludeuser", excludeUser);
        }
        
        if (rctypes != null && !rctypes.isEmpty()) {
            getparams.put("rctype", String.join("|", rctypes));
        }
        
        if (rcoptions != null && !rcoptions.isEmpty())
        {
            var temp = new ArrayList<String>();
            rcoptions.forEach((key, value) -> temp.add((Boolean.FALSE.equals(value) ? "!" : "") + key));
            getparams.put("rcshow", String.join("|", temp));
        }

        if (starttimestamp != null) {
        	var odt = starttimestamp.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        	getparams.put("rcstart", odt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
        
        if (endtimestamp == null) {
        	var odt = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        	getparams.put("rcend", odt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        } else {
        	var odt = endtimestamp.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        	getparams.put("rcend", odt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
        
        List<Wiki.Revision> revisions = makeListQuery("rc", getparams, null, "recentChanges", -1, (line, results) -> {
        	for (int i = line.indexOf("<rc "); i != -1; i = line.indexOf("<rc ", ++i)) {
                int j = line.indexOf("/>", i);
                results.add(parseRevision(line.substring(i, j), ""));
            }
        });

        int temp = revisions.size();
        log(Level.INFO, "Successfully retrieved recent changes (" + temp + " revisions)", "recentChanges");
        return revisions;
    }
    
    public List<String> allLinks(String prefix, int namespace) throws IOException {
    	var getparams = new HashMap<String, String>();
    	getparams.put("list", "alllinks");

    	if (namespace == ALL_NAMESPACES) {
			throw new UnsupportedOperationException("ALL_NAMESPACES not supported in MediaWiki API.");
		}
    	
    	if (!prefix.isEmpty()) {
			namespace = namespace(prefix);
			
			if (prefix.contains(":") && namespace != MAIN_NAMESPACE) {
				prefix = prefix.substring(prefix.indexOf(':') + 1);
			}  
			
			getparams.put("alprefix", normalize(prefix));
		}
		
    	getparams.put("alnamespace", Integer.toString(namespace));
		getparams.put("alunique", "1");
		
		List<String> pages = makeListQuery("al", getparams, null, "allPages", -1, (line, results) -> {
			for (int a = line.indexOf("<l "); a > 0; a = line.indexOf("<l ", ++a)) {
				results.add(parseAttribute(line, "title", a));
			}
		});
		
		// tidy up
		int size = pages.size();
		log(Level.INFO, "allPages", "Successfully retrieved links list (" + size + " pages)");
		
		return pages;
    }
    
    public List<String> listPages(String prefix, Map<String, Object> protectionstate, int namespace, String from, 
            String to, Boolean redirects) throws IOException
    {
        // @revised 0.15 to add short/long pages
        // No varargs namespace here because MW API only supports one namespace
        // for this module.
        var getparams = new HashMap<String, String>();
        getparams.put("list", "allpages");
        if (!prefix.isEmpty()) // prefix
        {
            // cull the namespace prefix
            namespace = namespace(prefix);
            if (prefix.contains(":") && namespace != MAIN_NAMESPACE)
                prefix = prefix.substring(prefix.indexOf(':') + 1);
            getparams.put("apprefix", normalize(prefix));
        }
        else if (namespace == ALL_NAMESPACES) // check for namespace
            throw new UnsupportedOperationException("ALL_NAMESPACES not supported in MediaWiki API.");
        getparams.put("apnamespace", Integer.toString(namespace));
        if (protectionstate != null)
        {
        	var apprtype = new ArrayList<String>();
        	var apprlevel = new ArrayList<String>();
            for (var entry : protectionstate.entrySet())
            {
                var key = entry.getKey();
                if (key.equals("cascade"))
                {
                	getparams.put("apprfiltercascade", (Boolean)entry.getValue() ? "cascading" : "noncascading");
                }
                else if (!key.contains("expiry"))
                {
                    apprtype.add(key);
                    apprlevel.add((String)entry.getValue());
                }      
            }
            getparams.put("apprtype", String.join("|", apprtype));
            getparams.put("apprlevel", String.join("|", apprlevel));
        }
        // max and min
        if (from != null)
        {
        	getparams.put("apfrom", from);
        }
        if (to != null)
        {
        	getparams.put("apto", to);
        }
        if (redirects == Boolean.TRUE)
        	getparams.put("apfilterredir", "redirects");
        else if (redirects == Boolean.FALSE)
        	getparams.put("apfilterredir", "nonredirects");

        // parse
        List<String> pages = makeListQuery("ap", getparams, null, "listPages", -1, (line, results) -> {
        	// xml form: <p pageid="1756320" ns="0" title="Kre'fey" />
            for (int a = line.indexOf("<p "); a > 0; a = line.indexOf("<p ", ++a))
            	results.add(parseAttribute(line, "title", a));
        });
        
        // tidy up
        int size = pages.size();
        log(Level.INFO, "listPages", "Successfully retrieved page list (" + size + " pages)");
        return pages;
    }
    
    public synchronized void review(Wiki.Revision rev, String comment) throws LoginException, IOException {
		requiresExtension("Flagged Revisions");
		throttle();
		
		var user = getCurrentUser();
		
		if (user == null || !user.isAllowedTo("review")) {
            throw new SecurityException("Permission denied: cannot review.");
		}
		
		var getparams = new HashMap<String, String>();
		getparams.put("action", "review");
		
		var postparams = new HashMap<String, Object>();
		
		if (comment != null && !comment.isEmpty()) {
			postparams.put("comment", comment);
		}
		
		postparams.put("flag_accuracy", "1");
		postparams.put("revid", Long.toString(rev.getID()));
		postparams.put("token", getToken("csrf"));
		
		var response = makeApiCall(getparams, postparams, "review");
		
		if (checkErrorsAndUpdateStatus(response, "review", null, null)) {
			log(Level.INFO, "review", "Successfully reviewed revision " + rev.getID() + " of page " + rev.getTitle());
		}
	}
    
	public void createClaim(String entity, String property, String value) throws LoginException, IOException {
		createClaim(entity, property, value, -1L, null);
	}
	
	public void createClaim(String entity, String property, String value, long baseRevid) throws LoginException, IOException {
		createClaim(entity, property, value, baseRevid, null);
	}
	
	public synchronized void createClaim(String entity, String property, String value, long baseRevid, String summary) throws IOException, LoginException {
		requiresExtension("WikibaseRepository");
		throttle();
		
		var getparams = new HashMap<>(Map.of(
			"action", "wbcreateclaim",
			"entity", entity,
			"snaktype", "value",
			"property", property,
			"value", value,
			"bot", isMarkBot() ? "1" : "0"
		));
		
		if (baseRevid != -1L) {
			getparams.put("baserevid", Long.toString(baseRevid));
		}
		
		if (summary != null) {
			getparams.put("summary", summary);
		}
		
		var postparams = Map.of("token", (Object)getToken("csrf"));
		var response = makeApiCall(getparams, postparams, "wbcreateclaim");
		checkErrorsAndUpdateStatus(response, "wbcreateclaim", null, null);
		log(Level.INFO, "wbcreateclaim", "Successfully added claim to " + entity);
	}
}
