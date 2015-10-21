package com.github.wikibot.utils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import com.univocity.parsers.common.ParsingContext;
import com.univocity.parsers.common.processor.ObjectRowProcessor;
import com.univocity.parsers.tsv.TsvParser;
import com.univocity.parsers.tsv.TsvParserSettings;

public class MorfeuszLookup {
	private static final String dumpsPath = "./data/dumps/";
	private static final File f = new File(dumpsPath + "polimorf-20150602.tab.gz");
	
	private MorfeuszLookup() {}
	
	public static Map<String, List<String>> getResults(String target) {
		Map<String, List<String>> results = new HashMap<>();
		
		// http://stackoverflow.com/a/27087060
		ObjectRowProcessor rowProcessor = new ObjectRowProcessor() {
		    @Override
		    public void rowProcessed(Object[] row, ParsingContext context) {
		    	String s = (String) row[0];
		    	
		        if (s.equals(target)) {
		        	String canonical = (String) row[1];
		        	String info = (String) row[2];
		        	
		        	if (results.containsKey(canonical)) {
		        		List<String> list = results.get(canonical);
		        		list.add(info);
		        	} else {
		        		List<String> list = new ArrayList<>();
		        		list.add(info);
		        		results.put(canonical, list);
		        	}
		        }
		    }
		};
		
		TsvParserSettings settings = new TsvParserSettings();
		settings.setRowProcessor(rowProcessor);
		TsvParser parser = new TsvParser(settings);
		
		wrapParser(parser::parse);
		
		return results;
	}
	
	public static void find(Predicate<Object[]> p) {
		ObjectRowProcessor rowProcessor = new ObjectRowProcessor() {
		    @Override
		    public void rowProcessed(Object[] row, ParsingContext context) {
		    	if (!p.test(row)) {
		    		context.stop();
		    	}
		    }
		};
		
		TsvParserSettings settings = new TsvParserSettings();
		settings.setRowProcessor(rowProcessor);
		TsvParser parser = new TsvParser(settings);
		
		wrapParser(parser::parse);
	}
	
	/*public static void find(Consumer<String[]> cons) {
		TsvParserSettings settings = new TsvParserSettings();
		TsvParser parser = new TsvParser(settings);
		
		wrapParser(parser::beginParsing);
		
		String[] row;
		
		while ((row = parser.parseNext()) != null) {
			cons.accept(row);
		}
	}*/
	
	public static void find(Consumer<Object[]> cons) {
		ObjectRowProcessor rowProcessor = new ObjectRowProcessor() {
		    @Override
		    public void rowProcessed(Object[] row, ParsingContext context) {
		    	cons.accept(row);
		    }
		};
		
		TsvParserSettings settings = new TsvParserSettings();
		settings.setRowProcessor(rowProcessor);
		TsvParser parser = new TsvParser(settings);
		
		wrapParser(parser::parse);
	}
	
	private static void wrapParser(Consumer<Reader> cons) {
		try (Reader r = new InputStreamReader(new GzipCompressorInputStream(new BufferedInputStream(new FileInputStream(f))))) {
			cons.accept(r);
		} catch (IOException e) {}
	}
	
	public static void main(String[] args) {
		//Map<String, List<String>> res = MorfeuszLookup.getResults("piksel");
		List<String> res = new ArrayList<>();
		MorfeuszLookup.find(arr -> {
			if (arr[1].equals("kontent")) {
				res.add(Arrays.asList(arr).toString());
			}
		});
		System.out.println(String.join("\n", res));
	}
}
