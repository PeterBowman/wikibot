package com.github.wikibot.main;

import java.io.IOException;
import java.net.URLEncoder;
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
	
	public static final int HIDE_REDIRECT = 32;
	public static final int RC_EDIT = 1;
	public static final int RC_NEW = 2;
	public static final int RC_LOG = 4;
	public static final int RC_EXTERNAL = 8;
	public static final int RC_CATEGORIZE = 16;
	
	// serial version
    private static final long serialVersionUID = -8745212681497644126L;
    
    public Wikibot(String site) {
    	super(site);
    }
	
    public PageContainer[] getContentOfPages(String[] pages) throws IOException {
		String url = query + "prop=revisions&rvprop=timestamp%7Ccontent";
		BiConsumer<String, List<PageContainer>> biCons = this::parseContentLine;
		List<PageContainer> coll = getListedContent(url, pages, "getContents", "titles", biCons);
		return coll.toArray(new PageContainer[coll.size()]);
	}
    
    public PageContainer[] getContentOfPageIds(Long[] pageids) throws IOException {
		String url = query + "prop=revisions&rvprop=timestamp%7Ccontent";
		BiConsumer<String, List<PageContainer>> biCons = this::parseContentLine;
		String[] stringified = Stream.of(pageids).map(Object::toString).toArray(String[]::new);
		List<PageContainer> coll = getListedContent(url, stringified, "getContents", "pageids", biCons);
		return coll.toArray(new PageContainer[coll.size()]);
	}
    
    public PageContainer[] getContentOfRevIds(Long[] revids) throws IOException {
		String url = query + "prop=revisions&rvprop=timestamp%7Ccontent";
		BiConsumer<String, List<PageContainer>> biCons = this::parseContentLine;
		String[] stringified = Stream.of(revids).map(Object::toString).toArray(String[]::new);
		List<PageContainer> coll = getListedContent(url, stringified, "getContents", "revids", biCons);
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
		category = category.replaceFirst("^(Category|" + namespaceIdentifier(CATEGORY_NAMESPACE) + "):", "");
		
		StringBuilder sb = new StringBuilder(query);
		sb.append("prop=revisions&");
		sb.append("rvprop=timestamp%7Ccontent&");
		sb.append("generator=categorymembers&");
		sb.append("gcmtitle=Category:" + URLEncoder.encode(category, "UTF-8") + "&");
		sb.append("gcmtype=page");
		
		constructNamespaceString(sb, "gcm", ns);
		
		return getGeneratedContent(sb, "gcm");
	}
	
	public PageContainer[] getContentOfTransclusions(String page, int... ns) throws IOException {
		StringBuilder sb = new StringBuilder(query);
		sb.append("prop=revisions&");
		sb.append("rvprop=timestamp%7Ccontent&");
		sb.append("generator=embeddedin&");
		sb.append("geititle=" + URLEncoder.encode(page, "UTF-8"));
		
		constructNamespaceString(sb, "gei", ns);
		
		return getGeneratedContent(sb, "gei");
	}
	
	public PageContainer[] getContentOfBacklinks(String page, int... ns) throws IOException {
		StringBuilder sb = new StringBuilder(query);
		sb.append("prop=revisions&");
		sb.append("rvprop=timestamp%7Ccontent&");
		sb.append("generator=backlinks&");
		sb.append("gbltitle=" + URLEncoder.encode(page, "UTF-8"));
		
		constructNamespaceString(sb, "gbl", ns);
		
		return getGeneratedContent(sb, "gbl");
	}
	
	private <T> List<T> getListedContent(String url, String[] titles, String caller,
			String postParamName, BiConsumer<String, List<T>> biCons)
	throws IOException {
		String[] chunks = constructTitleString(titles);
		List<T> list = new ArrayList<>(titles.length);
		Map<String, String> postParams = new HashMap<>();
		
		for (int i = 0; i < chunks.length; i++) {
			postParams.put(postParamName, chunks[i]);
			String localCaller = String.format("%s (%d/%d)", caller, i + 1, chunks.length);
			String line = fetch(url, postParams, localCaller);
			biCons.accept(line, list);
		}
		
		log(Level.INFO, "getListedContent", "Successfully retrieved page contents (" + list.size() + " revisions)");
		return list;
	}

	private PageContainer[] getGeneratedContent(StringBuilder url, String queryPrefix) throws IOException {
		List<PageContainer> list = queryAPIResult(queryPrefix, url, null, "getGeneratedContent", this::parseContentLine);
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
		String url = apiUrl
			+ "action=expandtemplates&"
			+ "format=xml&"
			+ "prop=wikitext&"
			+ (title != null ? "title=" + title + "&" : "")
			+ "text=" + URLEncoder.encode(text, "UTF-8");
		
		String line = fetch(url, null, "expandTemplates");
		
		int a = line.indexOf("<wikitext ");
		a = line.indexOf(">", a) + 1;
		int b = line.indexOf("</wikitext>", a);
		
		return decode(line.substring(a, b));
	}
	
	public String parsePage(String page) throws IOException {
        return parsePage(page, -1);
    }
	
	public String parsePage(String page, int section) throws IOException {
		Map<String, String> postParams = new HashMap<>();
		postParams.put("prop", "text");
		postParams.put("page", URLEncoder.encode(page, "UTF-8"));
        
        if (section != -1) {
        	postParams.put("section", Integer.toString(section));
        }
        
        String response = fetch(apiUrl + "action=parse", postParams, "parse");
        int y = response.indexOf('>', response.indexOf("<text ")) + 1;
        int z = response.indexOf("</text>");
        return decode(response.substring(y, z));
    }
	
	public Revision[] getTopRevision(String[] titles) throws IOException {
        StringBuilder url = new StringBuilder(query);
        url.append("prop=revisions&rvprop=timestamp%7Cuser%7Cids%7Cflags%7Csize%7Ccomment%7Csha1");
        url.append("&meta=tokens&type=rollback");
		
		BiConsumer<String, List<Revision>> biCons = (line, list) -> {
			for (int page = line.indexOf("<page "); page != -1; page = line.indexOf("<page ", ++page)) {
				String title = parseAttribute(line, "title", page);
				int start = line.indexOf("<rev ", page);
				int end = line.indexOf("/>", start);
				list.add(parseRevision(line.substring(start, end), title));
			}
		};
		
		Collection<Revision> coll = getListedContent(url.toString(), titles, "getTopRevision", "titles", biCons);
		return coll.toArray(new Revision[coll.size()]);
    }
	
	public Revision[] recentChanges(OffsetDateTime start, OffsetDateTime end, int rcoptions, int rctypes, boolean toponly, String excludeUser, int... ns) throws IOException
	{
		String startTimestamp = start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
		String endTimestamp = end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
		return recentChanges(startTimestamp, endTimestamp, rcoptions, rctypes, toponly, excludeUser, ns);
	}
	
	public Revision[] recentChanges(String starttimestamp, String endtimestamp, int rcoptions, int rctypes, boolean toponly, String excludeUser, int... ns) throws IOException
    {
        StringBuilder sb_url = new StringBuilder(query);
        
        sb_url.append("list=recentchanges");
        sb_url.append("&rcdir=newer");
        sb_url.append("&rcprop=title%7Cids%7Cuser%7Ctimestamp%7Cflags%7Ccomment%7Csizes%7Csha1");
        
        constructNamespaceString(sb_url, "rc", ns);
        
        if (toponly) {
        	sb_url.append("&rctoponly=");
        }

        if (excludeUser != null) {
        	sb_url.append("&rcexcludeuser=").append(excludeUser);
        }
        
        if (rctypes > 0) {
        	sb_url.append("&rctype=");
        	
        	if ((rctypes & RC_EDIT) == RC_EDIT) {
            	sb_url.append("edit%7C");
        	}
        	
        	if ((rctypes & RC_NEW) == RC_NEW) {
            	sb_url.append("new%7C");
        	}
        	
        	if ((rctypes & RC_LOG) == RC_LOG) {
            	sb_url.append("log%7C");
        	}
        	
        	if ((rctypes & RC_EXTERNAL) == RC_EXTERNAL) {
            	sb_url.append("external%7C");
        	}
        	
        	if ((rctypes & RC_CATEGORIZE) == RC_CATEGORIZE) {
            	sb_url.append("categorize%7C");
        	}
        	
        	// chop off last |
            sb_url.delete(sb_url.length() - 3, sb_url.length());
        }
        
        if (rcoptions > 0) {
        	sb_url.append("&rcshow=");
        	
            if ((rcoptions & HIDE_ANON) == HIDE_ANON) {
            	sb_url.append("!anon%7C");
            }
            
            if ((rcoptions & HIDE_BOT) == HIDE_BOT) {
            	sb_url.append("!bot%7C");
            }
            
            if ((rcoptions & HIDE_SELF) == HIDE_SELF) {
            	sb_url.append("!self%7C");
            }
            
            if ((rcoptions & HIDE_MINOR) == HIDE_MINOR) {
            	sb_url.append("!minor%7C");
            }
            
            if ((rcoptions & HIDE_PATROLLED) == HIDE_PATROLLED) {
            	sb_url.append("!patrolled%7C");
            }
            
            if ((rcoptions & HIDE_REDIRECT) == HIDE_REDIRECT) {
            	sb_url.append("!redirect%7C");
            }
            
            // chop off last |
            sb_url.delete(sb_url.length() - 3, sb_url.length());
        }

        if (starttimestamp != null) {
        	sb_url.append("&rcstart=" + starttimestamp);
        }
        
        if (endtimestamp == null) {
        	sb_url.append("&rcend=" + OffsetDateTime.now(timezone()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        } else {
        	sb_url.append("&rcend=" + endtimestamp);
        }
        
        List<Revision> revisions = queryAPIResult("rc", sb_url, null, "recentChanges", (line, results) -> {
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
    	StringBuilder url = new StringBuilder(query);
    	url.append("list=alllinks");

    	if (namespace == ALL_NAMESPACES) {
			throw new UnsupportedOperationException("ALL_NAMESPACES not supported in MediaWiki API.");
		}
    	
    	if (!prefix.isEmpty()) {
			namespace = namespace(prefix);
			
			if (prefix.contains(":") && namespace != MAIN_NAMESPACE) {
				prefix = prefix.substring(prefix.indexOf(':') + 1);
			}  
			
			url.append("&alprefix=");
			url.append(URLEncoder.encode(normalize(prefix), "UTF-8"));
		}
		
		url.append("&alnamespace=");
		url.append(namespace);
		url.append("&alunique=");
		
		List<String> pages = queryAPIResult("al", url, null, "allPages", (line, results) -> {
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
        StringBuilder url = new StringBuilder(query);
        url.append("list=allpages");
        if (!prefix.isEmpty()) // prefix
        {
            // cull the namespace prefix
            namespace = namespace(prefix);
            if (prefix.contains(":") && namespace != MAIN_NAMESPACE)
                prefix = prefix.substring(prefix.indexOf(':') + 1);
            url.append("&apprefix=");
            url.append(URLEncoder.encode(normalize(prefix), "UTF-8"));
        }
        else if (namespace == ALL_NAMESPACES) // check for namespace
            throw new UnsupportedOperationException("ALL_NAMESPACES not supported in MediaWiki API.");
        url.append("&apnamespace=");
        url.append(namespace);
        if (protectionstate != null)
        {
            StringBuilder apprtype = new StringBuilder("&apprtype=");
            StringBuilder apprlevel = new StringBuilder("&apprlevel=");
            for (Map.Entry<String, Object> entry : protectionstate.entrySet())
            {
                String key = entry.getKey();
                if (key.equals("cascade"))
                {
                    url.append("&apprfiltercascade=");
                    url.append((Boolean)entry.getValue() ? "cascading" : "noncascading");
                }
                else if (!key.contains("expiry"))
                {
                    apprtype.append(key);
                    apprtype.append("%7C");
                    apprlevel.append((String)entry.getValue());
                    apprlevel.append("%7C");
                }      
            }
            apprtype.delete(apprtype.length() - 3, apprtype.length());
            apprlevel.delete(apprlevel.length() - 3, apprlevel.length());
            url.append(apprtype);
            url.append(apprlevel);
        }
        // max and min
        if (from != null)
        {
            url.append("&apfrom=");
            url.append(URLEncoder.encode(from, "UTF-8"));
        }
        if (to != null)
        {
            url.append("&apto=");
            url.append(URLEncoder.encode(to, "UTF-8"));
        }
        if (redirects == Boolean.TRUE)
            url.append("&apfilterredir=redirects");
        else if (redirects == Boolean.FALSE)
            url.append("&apfilterredir=nonredirects");

        // parse
        List<String> pages = queryAPIResult("ap", url, null, "listPages", (line, results) -> {
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
    	StringBuilder url = new StringBuilder(query);
    	url.append("list=iwbacklinks&iwblprop=iwprefix%7Ciwtitle");
    	
    	queryAPIResult("iwbl", url, null, "allIwBacklinks", (line, results) -> {
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
    	Map<String, List<String>> map = new HashMap<>(max);
    	StringBuilder url = new StringBuilder(query);
    	url.append("list=iwbacklinks&iwblprop=iwtitle");
    	
    	if (prefix == null || prefix.isEmpty()) {
    		throw new UnsupportedOperationException("Null or empty prefix parameter.");
    	}
    	
    	url.append("&iwblprefix=" + prefix);
    	
    	queryAPIResult("iwbl", url, null, "allIwBacklinksWithPrefix", (line, results) -> {
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
    	StringBuilder url = new StringBuilder(query);
    	url.append("list=iwbacklinks&iwblprop=iwtitle");
    	
    	if (prefix == null || prefix.isEmpty()) {
    		throw new UnsupportedOperationException("Null or empty prefix parameter.");
    	}
    	
    	if (target == null || target.isEmpty()) {
    		throw new UnsupportedOperationException("Null or empty target parameter.");
    	}
    	
    	url.append("&iwblprefix=" + prefix);
    	url.append("&iwbltitle=" + target);
    	
    	List<String> list = queryAPIResult("iwbl", url, null, "searchIwBacklinks", (line, results) -> {
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
		StringBuilder url = new StringBuilder(apiUrl);
        url.append("action=purge");
        url.append("&forcerecursivelinkupdate=1");
        Map<String, String> postparams = new HashMap<>();
        for (String x : constructTitleString(titles))
        {
            postparams.put("title", x);
            fetch(url.toString(), postparams, "purge");
        }
        log(Level.INFO, "purgeRecursive", "Successfully purged " + titles.length + " pages.");
	}
}
