package com.github.wikibot.main;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.LoginException;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.wikipedia.WMFWiki;

import com.github.wikibot.utils.PageContainer;

public class Wikibot extends WMFWiki {
	protected Wikibot(String domain) {
    	super(domain);
    }
    
    public static Wikibot createInstance(String domain) {
    	Wikibot wb = new Wikibot(domain);
    	wb.initVars();
    	return wb;
    }
	
    public PageContainer[] getContentOfPages(String[] pages) throws IOException {
    	Map<String, String> getparams = new HashMap<>();
    	getparams.put("action", "query");
    	getparams.put("prop", "revisions");
    	getparams.put("rvprop", "timestamp|content");
    	getparams.put("rvslots", "main");
		BiConsumer<String, List<PageContainer>> biCons = this::parseContentLine;
		List<PageContainer> coll = getListedContent(getparams, pages, "getContents", "titles", biCons);
		return coll.toArray(new PageContainer[coll.size()]);
	}
    
    public PageContainer[] getContentOfPageIds(Long[] pageids) throws IOException {
    	Map<String, String> getparams = new HashMap<>();
    	getparams.put("action", "query");
    	getparams.put("prop", "revisions");
    	getparams.put("rvprop", "timestamp|content");
    	getparams.put("rvslots", "main");
		BiConsumer<String, List<PageContainer>> biCons = this::parseContentLine;
		String[] stringified = Stream.of(pageids).map(Object::toString).toArray(String[]::new);
		List<PageContainer> coll = getListedContent(getparams, stringified, "getContents", "pageids", biCons);
		return coll.toArray(new PageContainer[coll.size()]);
	}
    
    public PageContainer[] getContentOfRevIds(Long[] revids) throws IOException {
    	Map<String, String> getparams = new HashMap<>();
    	getparams.put("action", "query");
    	getparams.put("prop", "revisions");
    	getparams.put("rvprop", "timestamp|content");
    	getparams.put("rvslots", "main");
		BiConsumer<String, List<PageContainer>> biCons = this::parseContentLine;
		String[] stringified = Stream.of(revids).map(Object::toString).toArray(String[]::new);
		List<PageContainer> coll = getListedContent(getparams, stringified, "getContents", "revids", biCons);
		return coll.toArray(new PageContainer[coll.size()]);
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
	public PageContainer[] getContentOfCategorymembers(String category, int... ns) throws IOException {
		Map<String, String> getparams = new HashMap<>();
		getparams.put("prop", "revisions");
		getparams.put("rvprop", "timestamp|content");
		getparams.put("rvslots", "main");
		getparams.put("generator", "categorymembers");
		getparams.put("gcmtitle", "Category:" + normalize(removeNamespace(category)));
		getparams.put("gcmtype", "page");
		getparams.put("gcmnamespace", constructNamespaceString(ns));
		
		return getGeneratedContent(getparams, "gcm");
	}
	
	public PageContainer[] getContentOfTransclusions(String page, int... ns) throws IOException {
		Map<String, String> getparams = new HashMap<>();
		getparams.put("prop", "revisions");
		getparams.put("rvprop", "timestamp|content");
		getparams.put("rvslots", "main");
		getparams.put("generator", "embeddedin");
		getparams.put("geititle", normalize(page));
		getparams.put("geinamespace", constructNamespaceString(ns));
		
		return getGeneratedContent(getparams, "gei");
	}
	
	public PageContainer[] getContentOfBacklinks(String page, int... ns) throws IOException {
		Map<String, String> getparams = new HashMap<>();
		getparams.put("prop", "revisions");
		getparams.put("rvprop", "timestamp|content");
		getparams.put("rvslots", "main");
		getparams.put("generator", "backlinks");
		getparams.put("gbltitle", normalize(page));
		getparams.put("gblnamespace", constructNamespaceString(ns));
		
		return getGeneratedContent(getparams, "gbl");
	}
	
	private <T> List<T> getListedContent(Map<String, String> getparams, String[] titles, String caller,
			String postParamName, BiConsumer<String, List<T>> biCons)
	throws IOException {
		List<String> chunks = constructTitleString(titles);
		List<T> list = new ArrayList<>(titles.length);
		Map<String, Object> postparams = new HashMap<>();
		
		for (int i = 0; i < chunks.size(); i++) {
			postparams.put(postParamName, chunks.get(i));
			String localCaller = String.format("%s (%d/%d)", caller, i + 1, chunks.size());
			String line = makeApiCall(getparams, postparams, localCaller);
			biCons.accept(line, list);
		}
		
		log(Level.INFO, "getListedContent", "Successfully retrieved page contents (" + list.size() + " revisions)");
		return list;
	}

	private PageContainer[] getGeneratedContent(Map<String, String> getparams, String queryPrefix) throws IOException {
		List<PageContainer> list = makeListQuery(queryPrefix, getparams, null, "getGeneratedContent", -1, this::parseContentLine);
		return list.toArray(new PageContainer[list.size()]);
	}
	
	private void parseContentLine(String line, List<PageContainer> list) {
		Document doc = Jsoup.parse(line, "", Parser.xmlParser());
		doc.outputSettings().prettyPrint(false);
		
		doc.getElementsByTag("page").stream()
			.flatMap(page -> page.getElementsByTag("rev").stream()
				.map(rev -> new PageContainer(
					decode(page.attr("title")),
					decode(rev.select("slot[role=main]").html()),
					OffsetDateTime.parse(rev.attr("timestamp"))
				))
			)
			.forEach(list::add);
	}
	
	public Map<String, OffsetDateTime> getTimestamps(String[] pages) throws IOException {
		return Stream.of(getTopRevision(pages))
			.collect(Collectors.toMap(
				Revision::getTitle,
				Revision::getTimestamp
			));
	}
	
	public String expandTemplates(String text) throws IOException {
		return expandTemplates(text, null);
	}
	
	public String expandTemplates(String text, String title) throws IOException {
		Map<String, String> getparams = new HashMap<>();
		getparams.put("action", "expandtemplates");
		getparams.put("prop", "wikitext");
		if (title != null)
			getparams.put("title", normalize(title));
		Map<String, Object> postparams = new HashMap<>();
		postparams.put("text", text);
		
		String line = makeApiCall(getparams, postparams, "expandTemplates");
		
		int a = line.indexOf("<wikitext ");
		a = line.indexOf(">", a) + 1;
		int b = line.indexOf("</wikitext>", a);
		
		return decode(line.substring(a, b));
	}
	
	public Revision[] getTopRevision(String[] titles) throws IOException {
		Map<String, String> getparams = new HashMap<>();
		getparams.put("action", "query");
		getparams.put("prop", "revisions");
		getparams.put("rvprop", "timestamp|user|ids|flags|size|comment|sha1");
		getparams.put("meta", "tokens");
		getparams.put("type", "rollback");
        
		BiConsumer<String, List<Revision>> biCons = (line, list) -> {
			for (int page = line.indexOf("<page "); page != -1; page = line.indexOf("<page ", ++page)) {
				String title = parseAttribute(line, "title", page);
				int start = line.indexOf("<rev ", page);
				int end = line.indexOf("/>", start);
				list.add(parseRevision(line.substring(start, end), title));
			}
		};
		
		Collection<Revision> coll = getListedContent(getparams, titles, "getTopRevision", "titles", biCons);
		return coll.toArray(new Revision[coll.size()]);
    }
	
	public Revision[] recentChanges(OffsetDateTime starttimestamp, OffsetDateTime endtimestamp, Map<String, Boolean> rcoptions,
			List<String> rctypes, boolean toponly, String excludeUser, int... ns) throws IOException
	{
		Map<String, String> getparams = new HashMap<>();
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
            List<String> temp = new ArrayList<>();
            rcoptions.forEach((key, value) -> temp.add((Boolean.FALSE.equals(value) ? "!" : "") + key));
            getparams.put("rcshow", String.join("|", temp));
        }

        if (starttimestamp != null) {
        	getparams.put("rcstart", starttimestamp.withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
        
        if (endtimestamp == null) {
        	getparams.put("rcend", OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        } else {
        	getparams.put("rcend", endtimestamp.withOffsetSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
        
        List<Revision> revisions = makeListQuery("rc", getparams, null, "recentChanges", -1, (line, results) -> {
        	for (int i = line.indexOf("<rc "); i != -1; i = line.indexOf("<rc ", ++i)) {
                int j = line.indexOf("/>", i);
                results.add(parseRevision(line.substring(i, j), ""));
            }
        });

        int temp = revisions.size();
        log(Level.INFO, "Successfully retrieved recent changes (" + temp + " revisions)", "recentChanges");
        return revisions.toArray(new Revision[temp]);
    }
    
    public String[] allLinks(String prefix, int namespace) throws IOException {
    	Map<String, String> getparams = new HashMap<>();
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
		
		return pages.toArray(new String[size]);
    }
    
    public String[] listPages(String prefix, Map<String, Object> protectionstate, int namespace, String from, 
            String to, Boolean redirects) throws IOException
    {
        // @revised 0.15 to add short/long pages
        // No varargs namespace here because MW API only supports one namespace
        // for this module.
        Map<String, String> getparams = new HashMap<>();
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
        	List<String> apprtype = new ArrayList<>();
        	List<String> apprlevel = new ArrayList<>();
            for (Map.Entry<String, Object> entry : protectionstate.entrySet())
            {
                String key = entry.getKey();
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
        return pages.toArray(new String[size]);
    }
    
    public synchronized void review(Revision rev, String comment) throws LoginException, IOException {
		requiresExtension("Flagged Revisions");
		throttle();
		
		User user = getCurrentUser();
		
		if (user == null || !user.isAllowedTo("review")) {
            throw new SecurityException("Permission denied: cannot review.");
		}
		
		Map<String, String> getparams = new HashMap<>();
		getparams.put("action", "review");
		
		Map<String, Object> postparams = new HashMap<>();
		
		if (comment != null && !comment.isEmpty()) {
			postparams.put("comment", comment);
		}
		
		postparams.put("flag_accuracy", "1");
		postparams.put("revid", Long.toString(rev.getID()));
		postparams.put("token", getToken("csrf"));
		
		String response = makeApiCall(getparams, postparams, "review");
		checkErrorsAndUpdateStatus(response, "review");
		log(Level.INFO, "review", "Successfully reviewed revision " + rev.getID() + " of page " + rev.getTitle());
	}
    
    public Map<String, String> getPageProps(String page) throws IOException {
    	return getPageProps(new String[] { page })[0];
    }
    
    public Map<String, String>[] getPageProps(String[] pages) throws IOException
    {
        Map<String, String> getparams = new HashMap<>();
        getparams.put("action", "query");
        getparams.put("prop", "pageprops");
        Map<String, Object> postparams = new HashMap<>();
        Map<String, Map<String, String>> metamap = new HashMap<>();
        // copy because redirect resolver overwrites
        String[] pages2 = Arrays.copyOf(pages, pages.length);
        List<String> chunks = constructTitleString(pages);
        for (int i = 0; i < chunks.size(); i++)
        {
        	String temp = chunks.get(i);
            postparams.put("titles", temp);
            String caller = String.format("getPageProps (%d/%d)", i + 1, chunks.size());
            String line = makeApiCall(getparams, postparams, caller);
            if (isResolvingRedirects())
                resolveRedirectParser(pages2, line);

            // form: <page _idx="353684" pageid="353684" ns="0" title="rescate" ... />
            for (int j = line.indexOf("<page "); j > 0; j = line.indexOf("<page ", ++j))
            {
            	boolean hasprops = false;
                int x = line.indexOf(" />", j);
                String item = line.substring(j + "<page ".length(), x);
                String header = item;
                if (item.contains("<pageprops "))
                {
                    hasprops = true;
                    header = item.substring(0, item.indexOf("<pageprops "));
                    item = line.substring(j, line.indexOf("</page>", j));
                }
                String parsedtitle = parseAttribute(header, "title", 0);
                Map<String, String> tempmap = new HashMap<>();
                tempmap.put("pagename", parsedtitle);
                if (item.contains("missing=\"\""))
                    tempmap.put("missing", "");
                else
                {
                    String pageid = parseAttribute(header, "pageid", 0);
                    tempmap.put("pageid", pageid);
                    if (hasprops)
                    {
                        j = line.indexOf("<pageprops ", j);
                        item = line.substring(j + "<pageprops ".length(), line.indexOf(" />", j));
		                for (String attr : item.split("=\\S+\\s*"))
		                    tempmap.put(attr, parseAttribute(item, attr, 0));
                    }
                }

                metamap.put(parsedtitle, tempmap);
            }
        }

        @SuppressWarnings("unchecked")
		Map<String, String>[] props = new HashMap[pages.length];
        // Reorder. Make a new HashMap so that inputpagename remains unique.
        for (int i = 0; i < pages2.length; i++)
        {
            Map<String, String> tempmap = metamap.get(normalize(pages2[i]));
            if (tempmap != null)
            {
                props[i] = new HashMap<>(tempmap);
                props[i].put("inputpagename", pages[i]);
            }
        }
        log(Level.INFO, "getPageProps", "Successfully retrieved page properties (" + pages.length + " titles)");
        return props;
    }
}
