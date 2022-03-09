package com.github.wikibot.parsing;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class AbstractEditor {
	protected String text;
	protected String title;
	protected Summary summ;
	protected boolean notifyModifications;
	protected List<String> logger;
	
	private static final Pattern p_linker = Pattern.compile("\\[\\[\\s*?(:?)\\s*?([^\\]\\|]+)\\s*?(?:\\|\\s*?((?:]?[^\\]\\|])*+))*\\s*?\\]\\]([^\\[]*)", Pattern.DOTALL);
	
	protected AbstractEditor(String title, String text) {
		this.summ = new Summary();
		this.notifyModifications = false;
		this.logger = new ArrayList<>();
		this.title = title;
		this.text = Utils.sanitizeWhitespaces(text);
	}
	
	public String getPageText() {
		return text;
	}
	
	public String getPageTitle() {
		return title;
	}
	
	public String getSummary() {
		return summ.toString();
	}
	
	public String getSummary(String primary) {
		summ.setPrimary(primary);
		return getSummary();
	}
	
	public boolean isModified() {
		return notifyModifications;
	}
	
	public String getLogs() {
		String out;
		
		if (!logger.isEmpty()) {
			out = String.format(
				"%d action%s performed on \"%s\": %s",
				logger.size(), logger.size() == 1 ? "" : "s", title, logger.toString()
			);
		} else {
			out = String.format("No actions performed on \"%s\"", title);
		}
		
		return out;
	}
	
	public abstract void check();
	
	protected void addOperation(String caller, String log, UnaryOperator<String> func) {
		String formatted = func.apply(text);
		checkDifferences(formatted, caller, log);
	}

	protected void checkDifferences(String formatted, String caller, String log) {
		if (
			formatted != null &&
			!formatted.replaceFirst("\\s+$", "").equals(text.replaceFirst("\\s+$", ""))
		) {
			logger.add(caller);
			text = formatted;
			
			if (log != null) {
				summ.add(log);
				notifyModifications = true;
			}
		}
	}
	
	@Override
	public String toString() {
		return String.format("%s || title=\"%s\"%n%s", logger, title, text);
	}
	
	public static String linker(String original) {
		//String original = page.toString();
		String formatted = original;
		Matcher m = p_linker.matcher(original);
		StringBuilder sb = new StringBuilder(original.length());
		
		while (m.find()) {
			String colon = m.group(1);
			String link = m.group(2);
			String text = m.group(3);
			String trail = m.group(4);
			
			if (sb.length() == 0) {
				sb.append(formatted.substring(0, m.start()));
			}
			
			sb.append("[[");
			
			if (!colon.isEmpty()) {
				sb.append(":");
			}
			
			sb.append(link.trim());
			
			if (text != null) {
				sb.append("|");
				sb.append(text.trim());
			}
			
			sb.append("]]");
			sb.append(trail);
			//sb.append(formatted.substring(m.end()));
		}
		
		if (!formatted.trim().equals(original.trim())) {
			//page = Page.store(page.getTitle(), formatted);
		}
		
		return sb.toString();
	}
	
	protected class Summary {
		private Set<String> data;
		private String primary;
		
		public Summary() {
			data = new LinkedHashSet<>();
			primary = "";
		}
		
		public void add(String s) {
			data.add(s);
		}
		
		public void setPrimary(String primary) {
			this.primary = primary;
		}

		@Override
		public String toString() {
			StringBuilder sb = new StringBuilder();
			
			if (!primary.isEmpty()) {
				sb.append(primary);
				
				if (!data.isEmpty()) {
					sb.append(" || ");
				}
			}
			
			sb.append(String.join("; ", data));
			return sb.toString();
		}
	}

	public static void main(String[] args) throws Exception {
		String s = "testeo [[hola|asdfg]]sdfsd ffff [[ : rehola ]] test";
		System.out.println(linker(s));
	}
}
