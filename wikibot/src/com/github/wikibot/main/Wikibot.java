package com.github.wikibot.main;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
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
    
    private static final File dumpsPath = new File("./data/dumps");
    
    public Wikibot(String site) {
    	super(site);
    }
	
    public PageContainer[] getContentOfPages(String[] pages) throws IOException {
		return getContentOfPages(pages, slowmax);
	}
    
    public PageContainer[] getContentOfPages(String[] pages, int limit) throws IOException {
		limit = Math.min(limit, slowmax);
		String url = query + "prop=revisions&rvprop=timestamp%7Ccontent&titles=";
		Function<String, Collection<PageContainer>> func = this::parseContentLine;
		Collection<PageContainer> coll = getListedContent(url, pages, "getContents", func, limit);
		return coll.toArray(new PageContainer[coll.size()]);
	}
    
    public PageContainer[] getContentOfPageIds(Long[] pageids) throws IOException {
		return getContentOfPageIds(pageids, slowmax);
	}
    
    public PageContainer[] getContentOfPageIds(Long[] pageids, int limit) throws IOException {
		limit = Math.min(limit, slowmax);
		String url = query + "prop=revisions&rvprop=timestamp%7Ccontent&pageids=";
		Function<String, Collection<PageContainer>> func = this::parseContentLine;
		String[] stringified = Stream.of(pageids).map(Object::toString).toArray(String[]::new);
		Collection<PageContainer> coll = getListedContent(url, stringified, "getContents", func, limit);
		return coll.toArray(new PageContainer[coll.size()]);
	}
    
    public PageContainer[] getContentOfRevIds(Long[] revids) throws IOException {
		return getContentOfRevIds(revids, slowmax);
	}
    
    public PageContainer[] getContentOfRevIds(Long[] revids, int limit) throws IOException {
    	limit = Math.min(limit, slowmax);
		String url = query + "prop=revisions&rvprop=timestamp%7Ccontent&revids=";
		Function<String, Collection<PageContainer>> func = this::parseContentLine;
		String[] stringified = Stream.of(revids).map(Object::toString).toArray(String[]::new);
		Collection<PageContainer> coll = getListedContent(url, stringified, "getContents", func, limit);
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
		sb.append("gcmtype=page&");
		sb.append("gcmlimit=max");
		
		constructNamespaceString(sb, "gcm", ns);
		
		return getGeneratedContent(sb.toString());
	}
	
	public PageContainer[] getContentOfTransclusions(String page, int... ns) throws IOException {
		StringBuilder sb = new StringBuilder(query);
		sb.append("prop=revisions&");
		sb.append("rvprop=timestamp%7Ccontent&");
		sb.append("generator=embeddedin&");
		sb.append("geititle=" + URLEncoder.encode(page, "UTF-8") + "&");
		sb.append("geilimit=max");
		
		constructNamespaceString(sb, "gei", ns);
		
		return getGeneratedContent(sb.toString());
	}
	
	public PageContainer[] getContentOfBacklinks(String page, int... ns) throws IOException {
		StringBuilder sb = new StringBuilder(query);
		sb.append("prop=revisions&");
		sb.append("rvprop=timestamp%7Ccontent&");
		sb.append("generator=backlinks&");
		sb.append("gbltitle=" + URLEncoder.encode(page, "UTF-8") + "&");
		sb.append("gbllimit=max");
		
		constructNamespaceString(sb, "gbl", ns);
		
		return getGeneratedContent(sb.toString());
	}
	
	private <T> Collection<T> getListedContent(String url, String[] titles, String caller,
			Function<String, Collection<T>> func, int limit)
	throws IOException {
		int listSize = titles.length;
		String[] batches = constructTitleString(titles, limit);
		List<T> list = new ArrayList<T>(listSize);
		
		for (int i = 0; i < batches.length; i++) {
			String line = null;
			
			try {
				line = fetch(url + batches[i], String.format(
						"%s (%d/%d)",
						caller, Math.min(limit * (i + 1), listSize), listSize
					));
			} catch (IOException e) {
				if (limit <= 50) {
					throw e;
				}
				
				System.out.println("Retrying...");
				StringBuilder sb = new StringBuilder(30000);
				String decoded = URLDecoder.decode(batches[i], "UTF-8");
				String[] minibatches = constructTitleString(decoded.split("\\|"), 50);
				
				for (int j = 0; j < minibatches.length; j++) {
					sb.append(fetch(url + minibatches[j], String.format(
							"%s (%d/%d)",
							caller, Math.min((i * limit) + (j + 1) * 50, listSize), listSize
						)));
				}
				
				line = sb.toString();
			}
			
			list.addAll(func.apply(line));
		}
		
		log(Level.INFO, "getListedContent", "Successfully retrieved page contents (" + list.size() + " pages)");
		return list;
	}

	private PageContainer[] getGeneratedContent(String url) throws IOException {
		return getGeneratedContent(url, -1);
	}
	
	private PageContainer[] getGeneratedContent(String url, int limit) throws IOException {
		List<PageContainer> list = new ArrayList<PageContainer>(limit);
		
		String cont = "continue=";
		String line;
		
		do {
	    	line = fetch(url + "&" + cont, "getGeneratedContent");
	    	cont = parseContinue(line);
	        list.addAll(parseContentLine(line));
	    } while (cont != null && (limit < 0 || list.size() < limit));
		
		log(Level.INFO, "getGeneratedContent", "Successfully retrieved page contents (" + list.size() + " pages)");
		return list.toArray(new PageContainer[list.size()]);
	}
	
	private String parseContinue(String xml) {
		int a = xml.indexOf("<continue ");
		
		if (a == -1) {
			return null;
		}
		
		int b = xml.indexOf("/>", a);
		String[] params = xml.substring(a + "<continue ".length(), b).split(" ");
		
		String out = Stream.of(params)
			.map(param -> param.replace("\"", ""))
			.collect(Collectors.joining("&"));
		
		return out;
	}
	
	private Collection<PageContainer> parseContentLine(String line) {
		Collection<PageContainer> list = new ArrayList<PageContainer>(max);
		int start = line.indexOf("<page ");
		
		do {
			int end = line.indexOf("<page ", start + 1);
			String text = line.substring(start, end != -1 ? end : line.length());
			start = end;
			
			if (!text.contains("<rev ")) {
				continue;
			}
			
			String title = decode(parseAttribute(text, "title", 0));
			String timestamp = parseAttribute(text, "timestamp", 0);
			String content = null;
			
			if (!text.contains("</rev>")) {
				content = "";
			} else {
				int a = text.indexOf("<rev ");
		        a = text.indexOf("xml:space=\"preserve\">", a) + 21;
		        int b = text.indexOf("</rev>", a);
		        content = decode(text.substring(a, b));
			}
			
			list.add(new PageContainer(title, content, timestampToCalendar(timestamp, true)));
		} while (start != -1);
		
		return list;
	}
	
	public Map<String, Calendar> getTimestamps(String[] pages) throws IOException {
		return Stream.of(getTopRevision(pages))
			.collect(Collectors.toMap(
				rev -> rev.getPage(),
				rev -> rev.getTimestamp()
			));
	}
	
	@Deprecated
	public Map<String, Calendar> getTimestamps(Collection<? extends String> pages) throws IOException {
		return Stream.of(getTopRevision(pages.toArray(new String[pages.size()])))
			.collect(Collectors.toMap(
				rev -> rev.getPage(),
				rev -> rev.getTimestamp()
			));
	}
	
	public Map<String, Calendar> getTimestamps(PageContainer[] pages) {
		return Stream.of(pages)
			.collect(Collectors.toMap(
				page -> page.getTitle(),
				page -> page.getTimestamp()
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
		
		String line = fetch(url, "expandTemplates");
		
		int a = line.indexOf("<wikitext ");
		a = line.indexOf(">", a) + 1;
		int b = line.indexOf("</wikitext>", a);
		
		return decode(line.substring(a, b));
	}
	
	public String parsePage(String page) throws IOException {
        return parsePage(page, -1);
    }
	
	public String parsePage(String page, int section) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("prop=text&");
        sb.append("page=" + URLEncoder.encode(page, "UTF-8"));
        
        if (section != -1) {
        	sb.append("&section=" + section);
        }
        
        String response = post(apiUrl + "action=parse", sb.toString(), "parse");
        int y = response.indexOf('>', response.indexOf("<text ")) + 1;
        int z = response.indexOf("</text>");
        return decode(response.substring(y, z));
    }
	
	public Revision[] getTopRevision(String[] titles) throws IOException {
        StringBuilder url = new StringBuilder(query);
        url.append("prop=revisions&rvprop=timestamp%7Cuser%7Cids%7Cflags%7Csize%7Ccomment");
        url.append("&rvtoken=rollback&titles=");
		
		Function<String, Collection<Revision>> func = (line) -> {
			List<Revision> list = new ArrayList<Revision>(slowmax);
			
			for (int page = line.indexOf("<page "); page != -1; page = line.indexOf("<page ", ++page)) {
				String title = parseAttribute(line, "title", page);
				int start = line.indexOf("<rev ", page);
				int end = line.indexOf("/>", start);
				list.add(parseRevision(line.substring(start, end), title));
			}
			
			return list;
		};
		
		Collection<Revision> coll = getListedContent(url.toString(), titles, "getTopRevision", func, slowmax);
		return coll.toArray(new Revision[coll.size()]);
    }
	
	public String getWikiTimestamp() {
		return getWikiTimestamp(timezone);
	}
	
	public static String getWikiTimestamp(String timezone) {
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
		dateFormat.setTimeZone(TimeZone.getTimeZone(timezone));
		Calendar cal = Calendar.getInstance();
		return dateFormat.format(cal.getTime()).replace(" ", "T") + "Z";
	}
	
	protected String calendarToTimestamp(Calendar c, boolean api) {
		if (api) {
			return String.format(
				"%04d%02d%02dT%02d%02d%02dZ",
				c.get(Calendar.YEAR),
				c.get(Calendar.MONTH) + 1,
				c.get(Calendar.DAY_OF_MONTH),
				c.get(Calendar.HOUR_OF_DAY),
				c.get(Calendar.MINUTE),
				c.get(Calendar.SECOND)
			);
		} else {
			return super.calendarToTimestamp(c);
		}
	}
	
	public Revision[] recentChanges(Calendar starttimestamp, Calendar endtimestamp, int rcoptions, int rctypes, boolean toponly, int... ns) throws IOException
	{
		return recentChanges(calendarToTimestamp(starttimestamp, true), calendarToTimestamp(endtimestamp, true), rcoptions, rctypes, toponly, ns);
	}
	
	public Revision[] recentChanges(String starttimestamp, String endtimestamp, int rcoptions, int rctypes, boolean toponly, int... ns) throws IOException
    {
        StringBuilder sb_url = new StringBuilder(query);
        
        sb_url.append("list=recentchanges");
        sb_url.append("&rcdir=newer");
        sb_url.append("&rclimit=max");
        sb_url.append("&rcprop=title%7Cids%7Cuser%7Ctimestamp%7Cflags%7Ccomment%7Csizes");
        
        constructNamespaceString(sb_url, "rc", ns);
        
        if (toponly) {
        	sb_url.append("&rctoponly=");
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
        	sb_url.append("&rcend=" + getWikiTimestamp());
        } else {
        	sb_url.append("&rcend=" + endtimestamp);
        }
        
        String url = sb_url.toString();
        String cont = "continue=";

        List<Revision> revisions = new ArrayList<Revision>(500);
        
        do {
            String line = fetch(url + "&" + cont, "recentChanges");
            cont = parseContinue(line);
            
            // xml form <rc type="edit" ns="0" title="Main Page" ... />
            for (int i = line.indexOf("<rc "); i != -1; i = line.indexOf("<rc ", ++i)) {
                int j = line.indexOf("/>", i);
                revisions.add(parseRevision(line.substring(i, j), ""));
            }
        } while (cont != null);
        
        int temp = revisions.size();
        log(Level.INFO, "Successfully retrieved recent changes (" + temp + " revisions)", "recentChanges");
        return revisions.toArray(new Revision[temp]);
    }
	
	public String decode(String in) {
		in = in.replace("&lt;", "<").replace("&gt;", ">"); // html tags
		in = in.replace("&quot;", "\"");
		in = in.replace("&#039;", "'");
		in = in.replace("&amp;", "&");
		return in;
    }
	
	protected String[] constructTitleString(String[] titles) throws IOException {
		return constructTitleString(titles, 50);
	}
	
    protected String[] constructTitleString(String[] titles, int batchSize) throws IOException {
    	int size = (int) Math.ceil(((double) titles.length) / ((double) batchSize));
    	String[] ret = new String[size];
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < titles.length; i++)
        {
            buffer.append(normalize(titles[i]));
            if (i == titles.length - 1 || i % batchSize == batchSize - 1)
            {
                ret[i / batchSize] = URLEncoder.encode(buffer.toString(), "UTF-8");
                buffer = new StringBuilder();
            }
            else
                buffer.append("|");
        }
        return ret;
    }
    
    public String[] allLinks(String prefix, int namespace) throws IOException {
    	StringBuilder url = new StringBuilder(query);
    	url.append("list=alllinks&allimit=max");

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
		url.append("&rawcontinue=");
		
		List<String> pages = new ArrayList<>(6667);
		String next = null;
		
		do {
			String s = url.toString();
			
			if (next != null) {
				s += ("&alcontinue=" + URLEncoder.encode(next, "UTF-8"));
			}
			
			String line = fetch(s, "allLinks");
			
			if (line.contains("alcontinue=")) {
				next = parseAttribute(line, "alcontinue", 0);
			} else {
				next = null;
			}
			
			for (int a = line.indexOf("<l "); a > 0; a = line.indexOf("<l ", ++a)) {
				pages.add(parseAttribute(line, "title", a));
			}
		} while (next != null);
		
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
        url.append("list=allpages&aplimit=max");
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
        List<String> pages = new ArrayList<>(6667);
        String next = null;
        url.append("&rawcontinue=");
        do
        {
            // connect and read
            String s = url.toString();
            if (next != null)
                s += ("&apcontinue=" + URLEncoder.encode(next, "UTF-8"));
            String line = fetch(s, "listPages");

            // don't set a continuation if no max, min, prefix or protection level
            if (from != null && to != null && prefix.isEmpty() && protectionstate == null)
                next = null;
            // find next value
            else if (line.contains("apcontinue="))
                next = parseAttribute(line, "apcontinue", 0);
            else
                next = null;

            // xml form: <p pageid="1756320" ns="0" title="Kre'fey" />
            for (int a = line.indexOf("<p "); a > 0; a = line.indexOf("<p ", ++a))
                pages.add(parseAttribute(line, "title", a));
        }
        while (next != null);

        // tidy up
        int size = pages.size();
        log(Level.INFO, "listPages", "Successfully retrieved page list (" + size + " pages)");
        return pages.toArray(new String[size]);
    }
    
    public PageContainer[] listPagesContent(String from, int limit, int... ns) throws IOException
    {
        // @revised 0.15 to add short/long pages
        // No varargs namespace here because MW API only supports one namespace
        // for this module.
        StringBuilder url = new StringBuilder(query);
        url.append("prop=revisions&rvprop=content%7Ctimestamp&generator=allpages");
        // max and min
        if (from != null)
        {
            url.append("&gapfrom=");
            url.append(URLEncoder.encode(from, "UTF-8"));
        }
        url.append("&gapfilterredir=nonredirects");
        limit = Math.min(limit, max);
        url.append("&gaplimit=" + limit);

        constructNamespaceString(url, "gap", ns);		
        return getGeneratedContent(url.toString(), limit);
    }
    
    public Map<String, List<String[]>> allIwBacklinks() throws IOException {
    	Map<String, List<String[]>> map = new HashMap<String, List<String[]>>(max);
    	String url = query + "list=iwbacklinks&iwbllimit=max&iwblprop=iwprefix%7Ciwtitle";
    	url += "&rawcontinue=";
    	String next = null;
    	
    	do {
			String s = url;
			
			if (next != null) {
				s += ("&iwblcontinue=" + URLEncoder.encode(next, "UTF-8"));
			}
			
			String line = fetch(s, "allIwBacklinks");
			
			if (line.contains("iwblcontinue=")) {
				next = parseAttribute(line, "iwblcontinue", 0);
			} else {
				next = null;
			}
			
			for (int a = line.indexOf("<iw "); a > 0; a = line.indexOf("<iw ", ++a)) {
				String title = parseAttribute(line, "title", a);
				String iwTitle = parseAttribute(line, "iwtitle", a);
				
				if (iwTitle.isEmpty()) {
					continue;
				}
				
				String iwPrefix = parseAttribute(line, "iwprefix", a);
				
				if (map.containsKey(title)) {
					List<String[]> list = map.get(title);
					list.add(new String[]{iwPrefix, iwTitle});
				} else {
					List<String[]> list = new ArrayList<String[]>();
					list.add(new String[]{iwPrefix, iwTitle});
					map.put(title, list);
				}
			}
		} while (next != null);
    	
    	log(Level.INFO, "allIwBacklinks", "Successfully retrieved interwiki backlinks list (" + map.size() + " pages)");
    	return map;
    }
    
    public Map<String, List<String>> allIwBacklinksWithPrefix(String prefix) throws IOException {
    	Map<String, List<String>> map = new HashMap<String, List<String>>(max);
    	StringBuilder url = new StringBuilder(query);
    	url.append("list=iwbacklinks&iwbllimit=max&iwblprop=iwtitle");
    	url.append("&rawcontinue=");
    	
    	if (prefix == null || prefix.isEmpty()) {
    		throw new UnsupportedOperationException("Null or empty prefix parameter.");
    	}
    	
    	url.append("&iwblprefix=" + prefix);
    	String next = null;
    	
    	do {
			String s = url.toString();
			
			if (next != null) {
				s += ("&iwblcontinue=" + URLEncoder.encode(next, "UTF-8"));
			}
			
			String line = fetch(s, "allIwBacklinksWithPrefix");
			
			if (line.contains("iwblcontinue=")) {
				next = parseAttribute(line, "iwblcontinue", 0);
			} else {
				next = null;
			}
			
			for (int a = line.indexOf("<iw "); a > 0; a = line.indexOf("<iw ", ++a)) {
				String title = parseAttribute(line, "title", a);
				String iwTitle = parseAttribute(line, "iwtitle", a);
				
				if (iwTitle.isEmpty()) {
					continue;
				}
				
				if (map.containsKey(title)) {
					List<String> list = map.get(title);
					list.add(iwTitle);
				} else {
					List<String> list = new ArrayList<String>();
					list.add(iwTitle);
					map.put(title, list);
				}
			}
		} while (next != null);
    	
    	log(Level.INFO, "allIwBacklinksWithPrefix", "Successfully retrieved interwiki backlinks list (" + map.size() + " pages)");
    	return map;
    }
    
    public String[] searchIwBacklinks(String prefix, String target) throws IOException {
    	List<String> list = new ArrayList<String>();
    	
    	StringBuilder url = new StringBuilder(query);
    	url.append("list=iwbacklinks&iwbllimit=max&iwblprop=iwtitle");
    	
    	if (prefix == null || prefix.isEmpty()) {
    		throw new UnsupportedOperationException("Null or empty prefix parameter.");
    	}
    	
    	if (target == null || target.isEmpty()) {
    		throw new UnsupportedOperationException("Null or empty target parameter.");
    	}
    	
    	url.append("&iwblprefix=" + prefix);
    	url.append("&iwbltitle=" + target);
    	url.append("&rawcontinue=");
    	String next = null;
    	
    	do {
			String s = url.toString();
			
			if (next != null) {
				s += ("&iwblcontinue=" + URLEncoder.encode(next, "UTF-8"));
			}
			
			String line = fetch(s, "searchIwBacklinks");
			
			if (line.contains("iwblcontinue=")) {
				next = parseAttribute(line, "iwblcontinue", 0);
			} else {
				next = null;
			}
			
			for (int a = line.indexOf("<iw "); a > 0; a = line.indexOf("<iw ", ++a)) {
				String title = parseAttribute(line, "title", a);
				list.add(title);
			}
		} while (next != null);
    	
    	log(Level.INFO, "searchIwBacklinks", "Successfully retrieved interwiki backlinks list (" + list.size() + " pages)");
    	return list.toArray(new String[list.size()]);
    }
    
    public void readXmlDump(String domain, Consumer<PageContainer> cons) throws IOException {
    	File[] matching = dumpsPath.listFiles(new FilenameFilter() {
			public boolean accept(File dir, String name) {
				return name.startsWith(domain);
			}
		});
    	
    	if (matching.length == 0) {
    		throw new FileNotFoundException("Dump not found: " + domain);
    	}
    	
    	System.out.printf("Reading from file: %s%n", matching[0].getName());
    	ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
    	
    	try (BufferedReader br = new BufferedReader(new InputStreamReader(new BZip2CompressorInputStream(new FileInputStream(matching[0]))))) {
			String line;
			String title = null;
			StringBuilder text = null;
			String timestamp = null;
			boolean isReadingText = false;
			boolean skipPage = false;
			boolean forceParsing = false;
			
			while ((line = br.readLine()) != null) {
				if (line.contains("<page>")) {
					title = null;
					text = null;
					timestamp = null;
					skipPage = false;
				} else if (line.contains("<title>")) {
					int a = line.indexOf("<title>") + "<title>".length();
					int b = line.indexOf("</title>", a);
					title = decode(line.substring(a, b));
				} else if (line.contains("<ns>")) {
					int a = line.indexOf("<ns>") + "<ns>".length();
					int b = line.indexOf("</ns>", a);
					String ns = line.substring(a, b);
					
					if (!ns.equals("0")) {
						skipPage = true;
					}
				} else if (!skipPage && line.contains("<timestamp>")) {
					int a = line.indexOf("<timestamp>") + "<timestamp>".length();
					int b = line.indexOf("</timestamp>", a);
					timestamp = line.substring(a, b);
				} else if (!skipPage && line.contains("<text ")) {
					isReadingText = true;
					text = new StringBuilder();
					int a = line.indexOf(">") + 1;
					
					if (line.contains("</text>")) {
						int b = line.indexOf("</text>", a);
						text.append(line.substring(a, b));
						isReadingText = false;
						forceParsing = true;
					} else {
						text.append(line.substring(a));
						text.append("\n");
					}
				} else if (!skipPage && (forceParsing || line.contains("</text>"))) {
					isReadingText = false;
					
					if (!forceParsing) {
						int a = line.indexOf("</text>");
						text.append(line.substring(0, a));
					} else {
						forceParsing = false;
					}
					
					PageContainer page = new PageContainer(title, decode(text.toString()), timestampToCalendar(timestamp, true));
					executor.execute(() -> cons.accept(page));
				} else if (!skipPage && isReadingText) {
					text.append(line);
					text.append("\n");
				}
			}
		}
    	
    	executor.shutdown();
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
		url.append("&forcerecursivelinkupdate=");
		
		String[] temp = constructTitleString(titles);
		
		for (String x : temp) {
			post(url.toString(), "&titles=" + x, "purgeRecursive");
		}
		
		log(Level.INFO, "purgeRecursive", "Successfully purged " + titles.length + " pages.");
	}
    
    public void fetchRequest(Map<String, String> params, String tag, Consumer<String> cons) throws IOException {
    	List<String> temp = new ArrayList<String>(params.size());
    	
    	for (Entry<String, String> entry : params.entrySet()) {
    		String key = entry.getKey();
    		String value = URLEncoder.encode(entry.getValue(), "UTF-8");
    		temp.add((String.format("%s=%s", key, value)));
    	}
    	
    	String url = query + String.join("&", temp) + "&continue=";
    	String cont = null;
    	String splitString = String.format("(?=<%s )", tag);
    	
    	do {
    		String line = fetch(url, "custom request");
    		String[] splits = line.split(splitString);
    		
    		if (splits.length < 2) {
    			break;
    		}
    		
			cont = parseContinue(splits[0]);
			
			for (int i = 1; i < splits.length; i++) {
				cons.accept(splits[i]);
			}
    	} while (cont != null);
    }
}
