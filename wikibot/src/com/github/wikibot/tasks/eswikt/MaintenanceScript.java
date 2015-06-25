package com.github.wikibot.tasks.eswikt;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.FailedLoginException;

import org.wikipedia.Wiki.Revision;
import org.wikiutils.IOUtils;

import com.github.wikibot.main.ESWikt;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.eswikt.Editor;
import com.github.wikibot.utils.Domains;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;
import com.github.wikibot.utils.Users;

public final class MaintenanceScript {
	private static final String LOCATION = "./data/tasks.eswikt/MaintenanceScript/";
	private static final String LAST_DATE = LOCATION + "last_date.txt";
	private static final String PICK_DATE = LOCATION + "pick_date.txt";
	
	public static void main(String[] args) throws FailedLoginException, IOException, ParseException {
		File f_last_date = new File(LAST_DATE);
		File f_pick_date = new File(PICK_DATE);
		
		String startTimestamp = null;
		
		if (f_last_date.exists()) {
			startTimestamp = IOUtils.loadFromFile(LAST_DATE, "", "UTF8")[0];
		} else if (f_pick_date.exists()) {
			startTimestamp = IOUtils.loadFromFile(PICK_DATE, "", "UTF8")[0];
		} else {
			System.out.println("No initial timestamp found, aborting...");
			return;
		}
		
		ESWikt wb = Login.retrieveSession(Domains.ESWIKT, Users.User2);
		
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
		
		Calendar startCal = Calendar.getInstance();
		startCal.setTime(dateFormat.parse(startTimestamp));
		
		Calendar endCal = wb.makeCalendar();
		
		if (endCal.before(startCal)) {
			return;
		}
		
		int rcoptions = Wikibot.HIDE_REDIRECT | Wikibot.HIDE_BOT;
		int rctypes = Wikibot.RC_NEW | Wikibot.RC_EDIT;
		
		List<String> titles = new ArrayList<String>(Stream
			.of(wb.recentChanges(startCal, endCal, rcoptions, rctypes, false, Wikibot.MAIN_NAMESPACE))
			.map(Revision::getPage)
			.collect(Collectors.toCollection(LinkedHashSet::new)));
		
		PageContainer[] pages = Stream.of(wb.getContentOfPages(titles.toArray(new String[titles.size()])))
			.sorted((pc1, pc2) -> Integer.compare(titles.indexOf(pc1.getTitle()), titles.indexOf(pc2.getTitle())))
			.toArray(PageContainer[]::new);
		
		List<String> errors = new ArrayList<String>();
		
		for (PageContainer pc : pages) {
			String title = pc.getTitle();
			Editor editor = new Editor(pc);
			editor.check();
			
			if (editor.isModified()) {
				try {
					wb.edit(title, editor.getPageText(), editor.getSummary(), pc.getTimestamp());
					System.out.println(editor.getLogs());
				} catch (Exception e) {
					e.printStackTrace();
					errors.add(title);
				}
			}
		}
		
		if (!errors.isEmpty()) {
			System.out.printf("%d errors in: %s%n", errors.size(), errors.toString());
		}
		
		dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
		IOUtils.writeToFile(dateFormat.format(endCal.getTime()), LAST_DATE);
		
		Login.saveSession(wb);
	}
}

// https://weblogs.java.net/blog/kocko/archive/2014/12/19/java8-how-implement-custom-collector
// http://www.nurkiewicz.com/2014/07/introduction-to-writing-custom.html

class PageContainerCollector implements Collector<Revision, LinkedHashMap<String, Long>, PageContainer[]> {
	private Wikibot wb;

	public PageContainerCollector(Wikibot wb) {
		this.wb = wb;
	}
	
	@Override
	public Supplier<LinkedHashMap<String, Long>> supplier() {
		return LinkedHashMap::new;
	}

	@Override
	public BiConsumer<LinkedHashMap<String, Long>, Revision> accumulator() {
		return (accum, rev) -> {
			String title = rev.getPage();
			
			if (accum.containsKey(title)) {
				accum.remove(title);
			} else if (rev.isBot()) {
				return;
			}
			
			accum.put(title, rev.getRevid());
		};
	}

	@Override
	public BinaryOperator<LinkedHashMap<String, Long>> combiner() {
		return null;
	}

	@Override
	public Function<LinkedHashMap<String, Long>, PageContainer[]> finisher() {
		return accum -> {
			Long[] pageids = accum.values().toArray(new Long[accum.size()]);
			PageContainer[] pages;
			
			try {
				pages = wb.getContentOfRevIds(pageids);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			
			return accum.keySet().stream()
				.map(title -> Stream.of(pages).filter(pc -> pc.getTitle().equals(title)).findAny().get())
				.filter(Objects::nonNull)
				.toArray(PageContainer[]::new);
		};
	}

	@Override
	public Set<java.util.stream.Collector.Characteristics> characteristics() {
		return Collections.emptySet();
	}
	
}
