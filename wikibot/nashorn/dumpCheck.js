#!/data/project/pbbot/jdk/jdk1.8.0_74/bin/jjs -J-Dnashorn.args=-scripting -strict --language=es6

const File = Java.type("java.io.File");
const Files = Java.type("java.nio.file.Files");
const Paths = Java.type("java.nio.file.Paths");
const FileFilter = Java.type("java.io.FileFilter");
const SimpleDateFormat = Java.type("java.text.SimpleDateFormat");
const Calendar = Java.type("java.util.Calendar");

const JSON_DATA_FILE = new File("${$ENV.HOME}/scripts/dump_config.json");
const DUMPS_HISTORY = ".dumpsrc";
const DUMPS_HISTORY_FILE = new File("${$ENV.HOME}/${DUMPS_HISTORY}");
const PUBLIC_DUMPS = new File($ENV.DUMPS_PUBLIC);
const DATE_FORMAT = new SimpleDateFormat("yyyyMMdd");

const DumpEntry = (function() {
	var staticMembers = {};
	
	var fn = function(opts) {
		validate(opts);
		
		for (let opt in opts) {
			this[opt] = opts[opt];
		}
	}
	
	fn.prototype.serializeData = function() {
		var s = "project=${this.project}; date=${this.date}";
		s += "; dumptypes=${this.dumpTypes.join(',')}";
		
		if (!this.rollDown) {
			s += "; norolldown";
		}
		
		if (this.grid) {
			s += "; grid";
		}
		
		if (this.script) {
			s += "; script=${this.script}";
		}
		
		return s;
	};
	
	function initOpts() {
		return {
			project: "",
			date: "",
			cal: null,
			script: null,
			dumpTypes: "",
			updated: false,
			rollDown: true,
			grid: false,
			targetedFiles: null
		};
	}
	
	staticMembers.parseStoredEntry = function(entry) {
		var opts = initOpts();
		var params = entry.split(/; +/);
		
		for each (let param in params) {
			var split = param.split("=");
			var key = split[0];
			var value = split[1] || "";
			
			switch (key) {
				case "project":
				case "date":
				case "script":
					opts[key] = value;
					
					if (key === "date") {
						opts.cal = makeCalendar(value);
					}
					
					break;
				case "dumptypes":
					opts.dumpTypes = value.split(",");
					break;
				case "norolldown":
					opts.rollDown = false;
					break;
				case "grid":
					opts.grid = true;
					break;
				default:
					log("WARNING: ${entry}");
					log("Ignoring unrecognized config parameter: ${param}");
					break;
			}
		};
		
		return new fn(opts);
	};
	
	staticMembers.parseCommandLine = function(line) {
		var opts = initOpts();
		// TODO: write code
		return opts;
	};
	
	function validate(opts) {
		for (let key in opts) {
			if (opts[key] === undefined || opts[key] === "") {
				log("ERROR: command line parse - missing or empty parameter '${key}'");
				exit(1);
			}
		};
		
		if (opts.dumpTypes.length === 0) {
			log("ERROR: command line parse - empty 'dumpTypes' array");
			exit(1);
		}
		
		if (opts.cal === null) {
			log("ERROR: command line parse - unrecognized 'date' format: ${opts.date}");
			exit(1);
		}
	}
	
	return staticMembers;
}());

// TODO: add support for /incr folders, split into subclasses
const DumpFolder = (function() {
	const INFO_FILE_NAME = "dumpruninfo.txt";
	const SHA1_CHECKSUM_FILE_SUFFIX = "sha1sums.txt";
	
	var store = {};
	
	var fn = function(project, path) {
		if (store[path] !== undefined) {
			return store[path];
		} else {
			store[path] = this;
		}
		
		this.project = project;
		this.path = path;
		this.dir = new File(path);
		this.info = {};
		this.sha1checksums = {};
		
		try {
			loadData.call(this);
			this.dataLoadSuccess = true;
		} catch (e) {
			log("EXCEPTION: ${e.message}");
			this.dataLoadSuccess = false;
		}
	}
	
	fn.prototype.testStatusInfo = function(query) {
		if (Array.isArray(query)) {
			return query.every(this.testStatusInfo, this);
		}
		
		let infoEntry = this.info[query];
		
		if (!infoEntry) {
			if (storedDumpData[query].statusfallback) {
				return this.testStatusInfo(storedDumpData[query].statusfallback);
			} else {
				log("No matching status entry found for dump ${query}");
				return false;
			}
		}
		
		if (infoEntry.status !== "done") {
			log("Not ready yet, status of dump ${query}: ${infoEntry.status}");
			return false;
		}
		
		return true;
	};
	
	fn.prototype.retrieveFiles = function(query) {
		var arr = [];
		
		if (Array.isArray(query)) {
			query.forEach(function(v) arr = arr.concat(this.retrieveFiles(v)), this);
		} else {
			let path = this.dir.getPath();
			let fileName = buildFileName.call(this, query);
			
			if (storedDumpData[query].multi) {
				let temp = iterateCombinedDumps(path, fileName);
				
				if (temp.length === 0 && storedDumpData[query].dumpfallback) {
					arr = arr.concat(this.retrieveFiles(storedDumpData[query].dumpfallback));
				} else {
					arr = arr.concat(temp);
				}
			} else {
				let file = new File("${path}/${fileName}");
				
				if (file.exists()) {
					arr.push(file);
				}
			}
		}
		
		return arr;
	};
	
	fn.prototype.testChecksum = function(file) {
		var fileName = file.getName();
		var checksum = this.sha1checksums[fileName];
		
		if (checksum === "undefined") {
			log("No sha1 checksum found for file ${fileName}");
			return false;
		}
		
		if (!$EXEC("echo '${checksum} ${fileName}' | sha1sum -c --status; echo $?")) {
			log("sha1 checksum doesn't match for file ${fileName} (or sha1sum command failed)");
			return false;
		} else {
			return true;
		}
	};
	
	function loadData() {
		var sha1checksum = "${this.project}-${this.dir.getName()}-${SHA1_CHECKSUM_FILE_SUFFIX}";
		var fInfo = new File("${this.path}/${INFO_FILE_NAME}");
		var fChecksums = new File("${this.path}/${sha1checksum}");
		
		if (!fInfo.exists()) {
			throw new Error("No info file found: ${fInfo.getPath()}");
		}
		
		if (!fChecksums.exists()) {
			throw new Error("No sha1 checksum file found: ${fChecksums.getPath()}");
		}
		
		getDumpInfo.call(this, fInfo);
		getChecksums.call(this, fChecksums);
	};
	
	function getDumpInfo(file) {
		var info = this.info;
		
		readLinesFromFile(file, function(line) {
			var split = line.split("; ");
			var key = split[0].replace(/^name:/, "");
			var status = split[1].replace(/^status:/, "");
			var updated = split[2].replace(/^updated:/, "");
			
			info[key] = {
				status: status,
				updated: updated
			};
		});
	}

	function getChecksums(file) {
		var sha1checksums = this.sha1checksums;
		
		readLinesFromFile(file, function(line) {
			var split = line.split(/ +/);
			sha1checksums[split[1]] = split[0];
		});
	}
	
	function buildFileName(dumpFileType) {
		var dumpData = storedDumpData[dumpFileType];
		var fileName = dumpData.name;
		var exts = dumpData.exts;
		
		if (dumpData.multi) {
			return "${this.project}-${this.dir.getName()}-${fileName}$1.${exts.join('.')}";
		} else {
			return "${this.project}-${this.dir.getName()}-${fileName}.${exts.join('.')}";
		}
	}
	
	function iterateCombinedDumps(path, fileNameFormat) {
		var i = 1;
		var arr = [];
		var f;
		
		while ((f = new File("${path}/${fileNameFormat.replace('$1', i)}")).exists()) {
			arr.push(f);
			i++;
		}
		
		return arr;
	}
	
	return fn;
}());

function log(message) {
	print("${new Date().toUTCString()}  ${message}");
}

function readLinesFromFile(file, func) {
	Files.lines(Paths.get(file))
		.filter(function(line) !line.isEmpty() && !line.startsWith("#"))
		.forEach(func);
}

function makeCalendar(timestamp) {
	try {
		let cal = Calendar.getInstance();
		cal.setTime(DATE_FORMAT.parse(timestamp));
		return cal;
	} catch (e) {
		return null;
	}
}

function loadJSON() {
	if (!JSON_DATA_FILE.exists()) {
		log("ERROR: JSON file is missing - ${JSON_DATA_FILE.getPath()}");
		exit(2);
	}
	
	let json = readFully(JSON_DATA_FILE.getPath());
	
	try {
		let parsed = JSON.parse(json);
		log("JSON data loaded, ${Object.keys(parsed).length} entries found");
		return parsed;
	} catch (e) {
		log("ERROR: JSON parse, check JSON file - ${JSON_DATA_FILE.getPath()}");
		exit(1);
	}
}

function retrieveDumpFolders(parentDir, startCal) {
	var fileDirs = parentDir.listFiles(new FileFilter(function(file) file.isDirectory()));
	var arr = [];
	
	for each (let fileDir in fileDirs) {
		let cal = makeCalendar(fileDir.getName());
		
		if (cal === null) {
			log("Omitting directory ${fileDir.getName()} (parse error)");
			continue;
		}
		
		if (!cal.after(startCal)) {
			log("Omitting directory ${fileDir.getName()} (older date)");
			continue;
		}
		
		arr.push({
			dumpFolder: new DumpFolder(parentDir.getName(), fileDir.getPath()),
			cal: cal
		});
	}
	
	arr.sort(function(a, b) -a.cal.compareTo(b.cal));
	
	return arr.map(function(item) item.dumpFolder);
}

function processDumpFolder(entry, dumpFolder) {
	if (!dumpFolder.testStatusInfo(entry.dumpTypes)) {
		return;
	}
	
	let targetedFiles = dumpFolder.retrieveFiles(entry.dumpTypes);
	
	if (targetedFiles.length === 0) {
		log("WARNING: check procedures succeeded, but no matching files were found");
		return;
	}
	
	if (!targetedFiles.every(function(file) dumpFolder.testChecksum(file))) {
		return;
	}
	
	log("Marked ${targetedFiles.length} files for further processing");
	
	entry.updated = modified = true;
	entry.date = dumpFolder.dir.getName();
	entry.targetedFiles = targetedFiles; 
}

# --------------------------------------------------
# Main execution block
# --------------------------------------------------

log("---------------------------------");
log("Starting script...");

let storedDumpData = loadJSON();
let entries, modified;

# Load entries

if (arguments.length === 0) {
	if (!DUMPS_HISTORY_FILE.exists()) {
		log("ERROR: file ${DUMPS_HISTORY} is missing! Aborting...");
		exit(2);
	}
	
	let stored = [];
	readLinesFromFile(DUMPS_HISTORY_FILE, function(line) stored.push(line));
	
	entries = stored.map(DumpEntry.parseStoredEntry);
	log("Dump history retrieved (${Object.keys(entries).length} items)");
} else {
	let commandLine = arguments.join(" ");
	entries = [DumpEntry.parseCommandLine(commandLine)];
}

# Process entries

for each (let entry in entries) {
	let dir = new File(PUBLIC_DUMPS + "/${entry.project}");
	
	log("Processing directory ${dir.getPath()}");
	
	if (!dir.exists() || !dir.isDirectory()) {
		log("Unable to read from main directory");
		continue;
	}
	
	let dumpFolders = retrieveDumpFolders(dir, entry.cal);
	
	if (dumpFolders.length === 0) {
		log("No directories are available to process this entry");
		continue;
	} else {
		log("${dumpFolders.length} subdirectories found");
	}
	
	for each (let dumpFolder in dumpFolders) {
		if (!dumpFolder.dataLoadSuccess) {
			log("Ignoring ${dumpFolder.path} (data load error)");
		} else {
			log("Reading from ${dumpFolder.path}");
			processDumpFolder(entry, dumpFolder);
		}
		
		if (entry.updated || !entry.rollDown) {
			break;
		}
	}
}

# Save and apply changes

if (modified) {
	let output = [];
	let jobs = [];
	
	log("Processing updated data...");
	
	for each (let entry in entries) {
		output.push(entry.serializeData());
		
		if (entry.updated && entry.script) {
			let job = "";
			let args = entry.targetedFiles.map(function(file) file.getPath());
			
			if (entry.grid) {
				// FIXME: this is broken, see http://stackoverflow.com/a/30696663
				// See also http://alvinalexander.com/java/java-exec-system-command-pipeline-pipe
				//job += "jsub -sync y ";
			}
			
			job += entry.script + " ";
			job += args.join(" ");
			jobs.push(job);
		}
	}
	
	log("Writing output to ${DUMPS_HISTORY_FILE.getPath()}");
	
	const stringArr = Java.to(output, "java.lang.String[]");
	const Arrays = Java.type("java.util.Arrays");
	const StandardOpenOption = Java.type("java.nio.file.StandardOpenOption");
	
	try {
		Files.write(Paths.get(DUMPS_HISTORY_FILE), Arrays.asList(stringArr), StandardOpenOption.WRITE);
	} catch (e) {
		log("EXCEPTION: ${e.message}");
		exit(2);
	}
	
	jobs.forEach(function(job, i) {
		log("Submitting job ${i + 1}/${jobs.length}");
		print(job);
		$EXEC(job);
		print($ERR);
		print($OUT);
	});
} else {
	log("No changes detected!");
}

log("Exiting...");
