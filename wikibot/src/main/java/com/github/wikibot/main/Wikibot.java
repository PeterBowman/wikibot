package com.github.wikibot.main;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.parser.Parser;
import org.wikipedia.WMFWiki;

import com.github.wikibot.utils.PageContainer;

public class Wikibot extends WMFWiki {
	public static final int MODULE_NAMESPACE = 828;
	public static final int MODULE_TALK_NAMESPACE = 829;
	public static final int GADGET_NAMESPACE = 2300;
	public static final int GADGET_TALK_NAMESPACE = 2301;
	public static final int GADGET_DEFINITION_NAMESPACE = 2302;
	public static final int GADGET_DEFINITION_TALK_NAMESPACE = 2303;
	public static final int TOPIC_NAMESPACE = 2600;
    
    public Wikibot(String site) {
    	super(site);
    }
	
    public PageContainer[] getContentOfPages(String[] pages) throws IOException {
    	Map<String, String> getparams = new HashMap<>();
    	getparams.put("prop", "revisions");
    	getparams.put("rvprop", "timestamp|content");
		BiConsumer<String, List<PageContainer>> biCons = this::parseContentLine;
		List<PageContainer> coll = getListedContent(getparams, pages, "getContents", "titles", biCons);
		return coll.toArray(new PageContainer[coll.size()]);
	}
    
    public PageContainer[] getContentOfPageIds(Long[] pageids) throws IOException {
    	Map<String, String> getparams = new HashMap<>();
    	getparams.put("prop", "revisions");
    	getparams.put("rvprop", "timestamp|content");
		BiConsumer<String, List<PageContainer>> biCons = this::parseContentLine;
		String[] stringified = Stream.of(pageids).map(Object::toString).toArray(String[]::new);
		List<PageContainer> coll = getListedContent(getparams, stringified, "getContents", "pageids", biCons);
		return coll.toArray(new PageContainer[coll.size()]);
	}
    
    public PageContainer[] getContentOfRevIds(Long[] revids) throws IOException {
    	Map<String, String> getparams = new HashMap<>();
    	getparams.put("prop", "revisions");
    	getparams.put("rvprop", "timestamp|content");
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
		getparams.put("generator", "embeddedin");
		getparams.put("geititle", normalize(page));
		getparams.put("geinamespace", constructNamespaceString(ns));
		
		return getGeneratedContent(getparams, "gei");
	}
	
	public PageContainer[] getContentOfBacklinks(String page, int... ns) throws IOException {
		Map<String, String> getparams = new HashMap<>();
		getparams.put("prop", "revisions");
		getparams.put("rvprop", "timestamp|content");
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
			String line = makeHTTPRequest(query, getparams, postparams, localCaller);
			biCons.accept(line, list);
		}
		
		log(Level.INFO, "getListedContent", "Successfully retrieved page contents (" + list.size() + " revisions)");
		return list;
	}

	private PageContainer[] getGeneratedContent(Map<String, String> getparams, String queryPrefix) throws IOException {
		List<PageContainer> list = makeListQuery(queryPrefix, query, getparams, null, "getGeneratedContent", this::parseContentLine);
		return list.toArray(new PageContainer[list.size()]);
	}
	
	private void parseContentLine(String line, List<PageContainer> list) {
		Document doc = Jsoup.parse(line, "", Parser.xmlParser());
		doc.outputSettings().prettyPrint(false);
		
		doc.getElementsByTag("page").stream()
			.flatMap(page -> page.getElementsByTag("rev").stream()
				.map(rev -> new PageContainer(
					decode(page.attr("title")),
					decode(rev.html()),
					OffsetDateTime.parse(rev.attr("timestamp"))
				))
			)
			.forEach(list::add);
	}
	
	public Map<String, OffsetDateTime> getTimestamps(String[] pages) throws IOException {
		return Stream.of(getTopRevision(pages))
			.collect(Collectors.toMap(
				Revision::getPage,
				Revision::getTimestamp
			));
	}
	
	@Deprecated
	public Map<String, OffsetDateTime> getTimestamps(Collection<? extends String> pages) throws IOException {
		return Stream.of(getTopRevision(pages.toArray(new String[pages.size()])))
			.collect(Collectors.toMap(
				Revision::getPage,
				Revision::getTimestamp
			));
	}
	
	public Map<String, OffsetDateTime> getTimestamps(PageContainer[] pages) {
		return Stream.of(pages)
			.collect(Collectors.toMap(
				PageContainer::getTitle,
				PageContainer::getTimestamp
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
		
		String line = makeHTTPRequest(apiUrl, getparams, postparams, "expandTemplates");
		
		int a = line.indexOf("<wikitext ");
		a = line.indexOf(">", a) + 1;
		int b = line.indexOf("</wikitext>", a);
		
		return decode(line.substring(a, b));
	}
	
	public Revision[] getTopRevision(String[] titles) throws IOException {
		Map<String, String> getparams = new HashMap<>();
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
	
	public Revision[] recentChanges(OffsetDateTime start, OffsetDateTime end, Map<String, Boolean> rcoptions,
			List<String> rctypes, boolean toponly, String excludeUser, int... ns) throws IOException
	{
		String startTimestamp = start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
		String endTimestamp = end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
		return recentChanges(startTimestamp, endTimestamp, rcoptions, rctypes, toponly, excludeUser, ns);
	}
	
	public Revision[] recentChanges(String starttimestamp, String endtimestamp, Map<String, Boolean> rcoptions,
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
        	getparams.put("rcstart", starttimestamp);
        }
        
        if (endtimestamp == null) {
        	getparams.put("rcend", OffsetDateTime.now(timezone()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        } else {
        	getparams.put("rcend", endtimestamp);
        }
        
        List<Revision> revisions = makeListQuery("rc", query, getparams, null, "recentChanges", (line, results) -> {
        	for (int i = line.indexOf("<rc "); i != -1; i = line.indexOf("<rc ", ++i)) {
                int j = line.indexOf("/>", i);
                results.add(parseRevision(line.substring(i, j), ""));
            }
        });

        int temp = revisions.size();
        log(Level.INFO, "Successfully retrieved recent changes (" + temp + " revisions)", "recentChanges");
        return revisions.toArray(new Revision[temp]);
    }
	
	public String decode(String in) {
		return super.decode(in);
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
		
		List<String> pages = makeListQuery("al", query, getparams, null, "allPages", (line, results) -> {
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
        List<String> pages = makeListQuery("ap", query, getparams, null, "listPages", (line, results) -> {
        	// xml form: <p pageid="1756320" ns="0" title="Kre'fey" />
            for (int a = line.indexOf("<p "); a > 0; a = line.indexOf("<p ", ++a))
            	results.add(parseAttribute(line, "title", a));
        });
        
        // tidy up
        int size = pages.size();
        log(Level.INFO, "listPages", "Successfully retrieved page list (" + size + " pages)");
        return pages.toArray(new String[size]);
    }
    
    public Map<String, List<String[]>> allIwBacklinks() throws IOException {
    	Map<String, List<String[]>> map = new HashMap<>(max);
    	Map<String, String> getparams = new HashMap<>();
    	getparams.put("list", "iwbacklinks");
    	getparams.put("iwblprop", "iwprefix|iwtitle");
    	
    	makeListQuery("iwbl", query, getparams, null, "allIwBacklinks", (line, results) -> {
    		for (int a = line.indexOf("<iw "); a > 0; a = line.indexOf("<iw ", ++a)) {
				String title = parseAttribute(line, "title", a);
				String iwTitle = parseAttribute(line, "iwtitle", a);
				
				if (iwTitle.isEmpty()) {
					return;
				}
				
				String iwPrefix = parseAttribute(line, "iwprefix", a);
				
				if (map.containsKey(title)) {
					List<String[]> list = map.get(title);
					list.add(new String[]{iwPrefix, iwTitle});
				} else {
					List<String[]> list = new ArrayList<>();
					list.add(new String[]{iwPrefix, iwTitle});
					map.put(title, list);
				}
			}
    	});
    	
    	log(Level.INFO, "allIwBacklinks", "Successfully retrieved interwiki backlinks list (" + map.size() + " pages)");
    	return map;
    }
    
    public Map<String, List<String>> allIwBacklinksWithPrefix(String prefix) throws IOException {
    	Map<String, List<String>> map = new HashMap<>(max);;
    	Map<String, String> getparams = new HashMap<>();
    	getparams.put("list", "iwbacklinks");
    	getparams.put("iwblprop", "iwtitle");
    	
    	if (prefix == null || prefix.isEmpty()) {
    		throw new UnsupportedOperationException("Null or empty prefix parameter.");
    	}
    	
    	getparams.put("iwblprefix", normalize(prefix));
    	
    	makeListQuery("iwbl", query, getparams, null, "allIwBacklinksWithPrefix", (line, results) -> {
    		for (int a = line.indexOf("<iw "); a > 0; a = line.indexOf("<iw ", ++a)) {
				String title = parseAttribute(line, "title", a);
				String iwTitle = parseAttribute(line, "iwtitle", a);
				
				if (iwTitle.isEmpty()) {
					return;
				}
				
				if (map.containsKey(title)) {
					List<String> list = map.get(title);
					list.add(iwTitle);
				} else {
					List<String> list = new ArrayList<>();
					list.add(iwTitle);
					map.put(title, list);
				}
			}
    	});
    	
    	log(Level.INFO, "allIwBacklinksWithPrefix", "Successfully retrieved interwiki backlinks list (" + map.size() + " pages)");
    	return map;
    }
    
    public String[] searchIwBacklinks(String prefix, String target) throws IOException {
    	Map<String, String> getparams = new HashMap<>();
    	getparams.put("list", "iwbacklinks");
    	getparams.put("iwblprop", "iwtitle");
    	
    	if (prefix == null || prefix.isEmpty()) {
    		throw new UnsupportedOperationException("Null or empty prefix parameter.");
    	}
    	
    	if (target == null || target.isEmpty()) {
    		throw new UnsupportedOperationException("Null or empty target parameter.");
    	}
    	
    	getparams.put("iwblprefix", normalize(prefix));
    	getparams.put("iwbltitle", normalize(target));
    	
    	List<String> list = makeListQuery("iwbl", query, getparams, null, "searchIwBacklinks", (line, results) -> {
    		for (int a = line.indexOf("<iw "); a > 0; a = line.indexOf("<iw ", ++a)) {
				String title = parseAttribute(line, "title", a);
				results.add(title);
			}
    	});
    	
    	log(Level.INFO, "searchIwBacklinks", "Successfully retrieved interwiki backlinks list (" + list.size() + " pages)");
    	return list.toArray(new String[list.size()]);
    }
    
    /**
	 *  Purges the server-side cache for various pages
	 *  and updates the links tables recursively.
	 *  @param titles the titles of the pages to purge
	 *  @throws IOException if a network error occurs
	 */
	public void purgeRecursive(String... titles) throws IOException {
		Map<String, String> getparams = new HashMap<>();
		getparams.put("action", "purge");
		getparams.put("forcerecursivelinkupdate", "1");
        Map<String, Object> postparams = new HashMap<>();
        for (String x : constructTitleString(titles))
        {
            postparams.put("title", x);
            makeHTTPRequest(apiUrl, getparams, postparams, "purge");
        }
        log(Level.INFO, "purgeRecursive", "Successfully purged " + titles.length + " pages.");
	}
}
