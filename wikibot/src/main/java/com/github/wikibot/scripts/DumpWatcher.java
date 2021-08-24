package com.github.wikibot.scripts;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.json.JSONObject;

import com.github.wikibot.parsing.Utils;

public final class DumpWatcher {
	private static final Path DUMPS_HISTORY = Paths.get("./.dumpsrc");
	private static final Path DUMPS_PENDING = Paths.get("./dumps_pending");
	private static final Path PUBLIC_DUMPS = Paths.get("./data/dumps/public/");
	private static final DateFormat DATE_FORMAT = new SimpleDateFormat("yyyyMMdd");
	
	private static final JSONObject DUMP_CONFIG;
	
	static {
		var source = Utils.loadResource("/dump-config.json", DumpWatcher.class);
		DUMP_CONFIG = new JSONObject(source);
	}
	
	public static void main(String[] args) throws Exception {
		System.out.println("Starting script: " + LocalDateTime.now());
		
		final List<DumpEntry> entries;

		if (args.length == 0) {
			var lines = readLinesFromFile(DUMPS_HISTORY);
			entries = lines.stream().map(DumpEntry::parseStoredEntry).collect(Collectors.toList());
			System.out.printf("Dump history retrieved (%d items)%n", entries.size());
		} else {
			var line = String.join(" ", args);
			entries = List.of(DumpEntry.parseCommandLine(line));
		}
		
		var modified = false;
		
		for (var entry : entries) {
			var parentDir = PUBLIC_DUMPS.resolve(entry.project);
			
			System.out.println("Processing entry for job " + entry.script);
			
			if (!Files.isDirectory(parentDir)) {
				throw new IOException("Unable to access " + parentDir);
			}
			
			var dumpDirs = retrieveDumpDirectories(parentDir, entry.date);
			
			if (dumpDirs.isEmpty()) {
				System.out.println("No directories are available to process this entry");
				continue;
			}
			
			for (var dumpDir : dumpDirs) {
				if (dumpDir.dataLoadSuccess) {
					System.out.println("Reading from " + dumpDir.directory);
					modified |= processDumpDirectory(entry, dumpDir);
				} else {
					System.out.printf("Ignoring %s (data load error)%n", dumpDir.directory);
				}
				
				if (entry.updated || !entry.rolldown) {
					break;
				}
			}
		}
		
		if (modified) {
			System.out.println("Processing updated data...");
			
			var output = new ArrayList<String>();
			var jobs = new ArrayList<String>();
			
			for (var entry : entries) {
				output.add(entry.serializeData());
				
				if (entry.updated && !entry.script.isBlank()) {
					var paths = entry.targetedFiles.stream().map(Path::toString).collect(Collectors.toList());
					var job = entry.script + " " + String.join(" ", paths);
					jobs.add(job);
					System.out.println(job);
				}
			}
			
			System.out.println("Writing output to " + DUMPS_HISTORY);
			Files.write(DUMPS_HISTORY, output, StandardOpenOption.WRITE);
			
			System.out.printf("Registering %d job(s) into %s%n", jobs.size(), DUMPS_PENDING);
			Files.write(DUMPS_PENDING, jobs, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		} else {
			System.out.println("No changes detected");
		}
	}
	
	private static Date makeDate(String s) {
		try {
			return DATE_FORMAT.parse(s);
		} catch (ParseException e) {
			return null;
		}
	}
	
	private static List<String> readLinesFromFile(Path path) throws IOException {
		return Files.readAllLines(path).stream()
			.filter(line -> !line.isBlank() && !line.startsWith("#"))
			.collect(Collectors.toList());
	}
	
	private static List<DumpDirectory> retrieveDumpDirectories(Path parentDir, Date startDate) throws IOException {
		var map = new TreeMap<Date, DumpDirectory>(Collections.reverseOrder());
		
		try (var dirStream = Files.newDirectoryStream(parentDir, Files::isDirectory)) {
			for (var dir : dirStream) {
				var dirName = dir.getFileName().toString();
				
				if (dirName.equals("latest")) {
					continue;
				}
				
				var date = makeDate(dirName);
				
				if (date == null || !date.after(startDate)) {
					continue;
				}
				
				map.put(date, DumpDirectory.makeInstance(parentDir.getFileName().toString(), dir));
			}
		}
		
		return new ArrayList<>(map.values());
	}
	
	private static boolean processDumpDirectory(DumpEntry entry, DumpDirectory dir) {
		if (!dir.testStatusInfo(entry.dumpTypes)) {
			return false;
		}
		
		var targetedFiles = dir.retrieveFiles(entry.dumpTypes);
		
		if (targetedFiles.isEmpty()) {
			System.out.println("WARNING: check procedures succeeded, but no matching files were found");
			return false;
		}
		
		if (!targetedFiles.stream().allMatch(dir::testChecksum)) {
			return false;
		}
		
		System.out.printf("Marked %d file(s) for further processing%n", targetedFiles.size());
		
		entry.updated = true;
		entry.date = makeDate(dir.directory.getFileName().toString());
		entry.targetedFiles = targetedFiles;
		
		return true;
	}
	
	static class DumpEntry {
		String project;
		Date date;
		String script;
		List<String> dumpTypes;
		boolean updated;
		boolean rolldown;
		List<Path> targetedFiles;
		
		private DumpEntry() {
			project = "";
			date = null;
			script = null;
			dumpTypes = null;
			updated = false;
			rolldown = true;
			targetedFiles = null;
		}
		
		String serializeData() {
			var s = String.format("project=%s; date=%s", project, DATE_FORMAT.format(date));
			s += "; dumptypes=" + String.join(",", dumpTypes);
			
			if (!rolldown) {
				s += "; norolldown";
			}
			
			if (script != null) {
				s += "; script=" + script;
			}
			
			return s;
		}
		
		static DumpEntry parseStoredEntry(String entry) {
			var e = new DumpEntry();
			var params = entry.split("; +");
			
			for (var param : params) {
				var split = param.split("=");
				var key = split[0];
				var value = split.length > 1 ? split[1] : "";
				
				switch (key) {
					case "project":
						e.project = value;
						break;
					case "date":
						e.date = makeDate(value);
						break;
					case "script":
						e.script = value;
						break;
					case "dumptypes":
						e.dumpTypes = Arrays.asList(value.split(","));
						break;
					case "norolldown":
						e.rolldown = false;
						break;
					default:
						System.out.printf("WARNING: %s%n", entry);
						System.out.printf("Ignoring unrecognized config parameter: %s%n", param);
						break;
				}
			}
			
			e.validate();
			return e;
		}
		
		static DumpEntry parseCommandLine(String line) {
			// TODO
			var e = new DumpEntry();
			e.validate();
			return e;
		}
		
		private void validate() {
			Objects.requireNonNull(project);
			Objects.requireNonNull(script);
			Objects.requireNonNull(dumpTypes);
			
			if (dumpTypes.isEmpty()) {
				throw new IllegalArgumentException("command line parse - empty 'dumpTypes' array");
			}
			
			if (date == null) {
				throw new IllegalArgumentException("command line parse - unrecognized 'date' format");
			}
		}
	}
	
	static class DumpDirectory {
		// TODO: add support for /incr dirs, split into subclasses
		private static String INFO_FILE_NAME = "dumpruninfo.txt";
		private static String SHA1_CHECKSUM_FILE = "sha1sums.txt";

		String project;
		Path directory;
		boolean dataLoadSuccess;
		
		Map<String, String> sha1checksums;
		Map<String, String> dumpStatus;
		
		static Map<Path, DumpDirectory> store = new HashMap<>();
		
		private DumpDirectory() {}
		
		static DumpDirectory makeInstance(String project, Path subdir) {
			var dir = new DumpDirectory();
			
			if (store.containsKey(subdir)) {
				return store.get(subdir);
			} else {
				store.put(subdir, dir);
			}
			
			dir.project = project;
			dir.directory = subdir;
			dir.sha1checksums = new HashMap<>();
			dir.dumpStatus = new HashMap<>();
			
			try {
				dir.loadData();
				dir.dataLoadSuccess = true;
			} catch (Exception e) {
				e.printStackTrace();
				dir.dataLoadSuccess = false;
			}
			
			return dir;
		}
		
		boolean testStatusInfo(List<String> query) {
			return query.stream().allMatch(this::testStatusInfo);
		}
		
		boolean testStatusInfo(String query) {
			if (!dumpStatus.containsKey(query)) {
				var config = DUMP_CONFIG.getJSONObject(query);
				
				if (config.has("statusfallback")) {
					return testStatusInfo(config.getString("statusfallback"));
				} else {
					System.out.println("No matching status entry found for dump " + query);
					return false;
				}
			}
			
			var status = dumpStatus.get(query);
			
			if (!status.equals("done")) {
				System.out.printf("Not ready yet, status of dump %s: %s%n", query, status);
				return false;
			}
			
			return true;
		}
		
		List<Path> retrieveFiles(List<String> query) {
			return query.stream()
				.flatMap(q -> retrieveFiles(q).stream())
				.collect(Collectors.toList());
		}
		
		List<Path> retrieveFiles(String query) {
			var filename = buildFileName(query);
			var config = DUMP_CONFIG.getJSONObject(query);
			var paths = new ArrayList<Path>();
			
			if (config.optBoolean("multi")) {
				var temp = iterateCombinedDumps(directory, filename);
				
				if (temp.isEmpty() && config.has("dumpfallback")) {
					paths.addAll(retrieveFiles(config.getString("dumpfallback")));
				} else {
					paths.addAll(temp);
				}
			} else {
				var file = directory.resolve(filename);
				paths.add(file);
			}
			
			return paths;
		}
		
		boolean testChecksum(Path path) {
			var filename = path.getFileName().toString();
			
			if (!sha1checksums.containsKey(filename)) {
				System.out.println("No sha1 checksum found for file " + filename);
				return false;
			}
			
			MessageDigest digest;
			
			try {
				digest = MessageDigest.getInstance("SHA-1");
			} catch (NoSuchAlgorithmException e) {
				e.printStackTrace();
				return false;
			}
			
			try (var input = new DigestInputStream(Files.newInputStream(path), digest)) {
				var bytes = new byte[8192];
				
				while (input.read(bytes) > 0) {}
				
				var hex = bytesToHexString(digest.digest());
				
				if (!hex.equals(sha1checksums.get(filename))) {
					System.out.println("SHA1 checksum doesn't match for file " + filename);
					return false;
				}
			} catch (IOException e) {
				System.out.println("Unable to obtain checksum: " + e.getMessage());
				return false;
			}
			
			return true;
		}
		
		private static String bytesToHexString(byte[] bytes) {
			var sb = new StringBuilder();
			
			for (var b : bytes) {
				int value = b & 0xFF;
				
				if (value < 16) {
					// if value less than 16, then it's hex String will be only
					// one character, so we need to append a character of '0'
					sb.append("0");
				}
				
				sb.append(Integer.toHexString(value).toLowerCase());
			}
			
			return sb.toString();
		}
		
		private void loadData() throws IOException {
			var sha1checksum = String.format("%s-%s-%s", project, directory.getFileName(), SHA1_CHECKSUM_FILE);
			var fInfo = directory.resolve(INFO_FILE_NAME);
			var fChecksums = directory.resolve(sha1checksum);
			
			if (!Files.exists(fInfo)) {
				throw new FileNotFoundException("No info file found: " + fInfo);
			}
			
			if (!Files.exists(fChecksums)) {
				throw new FileNotFoundException("No SHA1 checksum file found: " + fChecksums);
			}
			
			getDumpInfo(fInfo);
			getChecksums(fChecksums);
		}
		
		private void getDumpInfo(Path path) throws IOException {
			readLinesFromFile(path).forEach(line -> {
				var split = line.split("; ");
				var key = split[0].replaceFirst("^name:", "");
				var status = split[1].replaceFirst("^status:", "");
				@SuppressWarnings("unused")
				var updated = split[2].replaceFirst("^updated:", "");
				
				dumpStatus.put(key, status);
			});
		}
		
		private void getChecksums(Path path) throws IOException {
			readLinesFromFile(path).stream()
				.map(line -> line.split(" +"))
				.forEach(pair -> sha1checksums.put(pair[1], pair[0]));
		}
		
		private String buildFileName(String dumpFileType) {
			var config = DUMP_CONFIG.getJSONObject(dumpFileType);
			var filename = config.getString("name");
			
			var exts = config.getJSONArray("exts").toList().stream()
				.map(Object::toString)
				.collect(Collectors.joining("."));
			
			if (config.optBoolean("multi")) {
				return String.format("%s-%s-%s$1.%s", project, directory.getFileName(), filename, exts);
			} else {
				return String.format("%s-%s-%s.%s", project, directory.getFileName(), filename, exts);
			}
		}
		
		private List<Path> iterateCombinedDumps(Path path, String fileNameFormat) {
			var list = new ArrayList<Path>();
			
			for (var i = 0; ; i++) {
				var filename = fileNameFormat.replace("$1", Integer.toString(i));
				var p = path.resolve(filename);
				
				if (Files.exists(p)) {
					list.add(p);
				} else {
					break;
				}
			}
			
			return list;
		}
	}
}
