package com.github.wikibot.utils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import com.univocity.parsers.common.ParsingContext;
import com.univocity.parsers.common.processor.ObjectRowProcessor;
import com.univocity.parsers.common.processor.RowProcessor;
import com.univocity.parsers.tsv.TsvParser;
import com.univocity.parsers.tsv.TsvParserSettings;

public class MorfeuszLookup {
	private static final Path DUMPS = Paths.get("./data/dumps/");
	private static final int SKIPPED_ROWS = 29;
	
	private File file;
	private TsvParserSettings settings;
	private boolean isCompressed;
	
	public MorfeuszLookup(String pathToFile) throws FileNotFoundException {
		this(Paths.get(pathToFile));
	}
	
	public MorfeuszLookup(Path path) throws FileNotFoundException {
		Objects.requireNonNull(path);
		
		file = path.toFile();
		
		if (!file.exists()) {
			throw new FileNotFoundException(path.toString());
		}
		
		settings = new TsvParserSettings();
		
		// had some issues with copyright notice (uses Windows CRLF, main content is Unix LF)
		settings.setNumberOfRowsToSkip(SKIPPED_ROWS);
		settings.setLineSeparatorDetectionEnabled(false);
		settings.setHeaderExtractionEnabled(false);
		settings.getFormat().setLineSeparator("\n");
				
		isCompressed = true;
	}
	
	public TsvParserSettings getSettings() {
		return settings;
	}
	
	public void setCompression(boolean isCompressed) {
		this.isCompressed = isCompressed;
	}
	
	public String getFileName() {
		return file.getName();
	}
	
	public Map<String, List<String>> getResults(String target) {
		Map<String, List<String>> results = new HashMap<>();
		
		// http://stackoverflow.com/a/27087060
		RowProcessor rowProcessor = new ObjectRowProcessor() {
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

		settings.setProcessor(rowProcessor);
		TsvParser parser = new TsvParser(settings);
		wrapParser(parser::parse);
		
		return results;
	}
	
	public void find(Predicate<Object[]> p) {
		RowProcessor rowProcessor = new ObjectRowProcessor() {
		    @Override
		    public void rowProcessed(Object[] row, ParsingContext context) {
		    	if (!p.test(row)) {
		    		context.stop();
		    	}
		    }
		};
		
		settings.setProcessor(rowProcessor);
		TsvParser parser = new TsvParser(settings);
		wrapParser(parser::parse);
	}
	
	public void find(Consumer<Object[]> cons) {
		RowProcessor rowProcessor = new ObjectRowProcessor() {
		    @Override
		    public void rowProcessed(Object[] row, ParsingContext context) {
		    	cons.accept(row);
		    }
		};
		
		settings.setProcessor(rowProcessor);
		TsvParser parser = new TsvParser(settings);
		wrapParser(parser::parse);
	}
	
	private void wrapParser(Consumer<Reader> cons) {
		try (
			InputStream bis = new BufferedInputStream(new FileInputStream(file));
			InputStream is = isCompressed ? new GzipCompressorInputStream(bis) : bis;
			Reader r = new InputStreamReader(is, StandardCharsets.UTF_8);
		) {
			cons.accept(r);
		} catch (IOException e) {}
	}
	
	public static void main(String[] args) {
		//Map<String, List<String>> res = MorfeuszLookup.getResults("piksel");
		List<String> res = new ArrayList<>();
		MorfeuszLookup morfeuszLookup;
		
		try {
			morfeuszLookup = new MorfeuszLookup(DUMPS.resolve("sgjp-20171029.tab"));
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			return;
		}
		
		morfeuszLookup.setCompression(false);
		
		System.out.println("Reading from file: " + morfeuszLookup.getFileName());
		
		morfeuszLookup.find(arr -> {
			if (arr[1].equals("test")) {
				res.add(Arrays.asList(arr).toString());
			}
		});
		
		System.out.println(String.join("\n", res));
	}
}
