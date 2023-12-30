package com.github.wikibot.dumps;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.Spliterators;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.Range;
import org.apache.commons.text.StringSubstitutor;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

public class XMLDump {
    private static final Pattern PATT_FILENAME_ID = Pattern.compile("(?<slice>\\d+)\\.(?:txt|xml)(?:-p(?<start>\\d+)p(?<end>\\d+))?");

    private static final String STATUS_JSON = "dumpstatus.json";
    private static final String STATUS_INCR = "status.txt";

    protected final DumpHandler handler;
    protected final String database;
    protected final String dirName;
    protected final List<String> filenames;

    protected Set<String> titles;
    protected SortedSet<Long> ids;

    XMLDump(DumpHandler handler, String database, String dirName, List<String> filenames) {
        this.handler = Objects.requireNonNull(handler);
        this.database = Objects.requireNonNull(database);
        this.dirName = Objects.requireNonNull(dirName);
        this.filenames = Objects.requireNonNull(filenames);

        if (filenames.isEmpty()) {
            throw new AssertionError();
        }
    }

    private Stream<XMLRevision> streamInternal() {
        if (filenames.size() == 1) {
            // preserve SIZED+SUBSIZED characteristics
            return new XMLDumpReader(handler.getInputStream(database, dirName, filenames.get(0))).getStAXReaderStream();
        }

        var stream = filenames.stream()
            .filter(this::tryFilterPageId)
            .map(filename -> handler.getInputStream(database, dirName, filename))
            .map(XMLDumpReader::new)
            .flatMap(XMLDumpReader::getStAXReaderStream);

        var sp = Spliterators.spliteratorUnknownSize(stream.iterator(), StAXDumpReader.BASIC_CHARACTERISTICS);
        return StreamSupport.stream(sp, false);
    }

    protected boolean tryFilterPageId(String filename) {
        if (ids != null) {
            var m = PATT_FILENAME_ID.matcher(filename);

            if (m.find()) {
                var gStart = m.group("start");
                var gEnd = m.group("end");

                if (gStart != null && gEnd != null) {
                    // inclusive, inclusive
                    var range = Range.of(Long.parseLong(gStart), Long.parseLong(gEnd));
                    return Range.of(ids.first(), ids.last()).isOverlappedBy(range);
                }
            }
        }

        return true;
    }

    public Stream<XMLRevision> stream() {
        if (titles != null) {
            return streamInternal().filter(rev -> titles.contains(rev.getTitle()));
        } else if (ids != null) {
            return streamInternal().filter(rev -> ids.contains(rev.getPageid()));
        } else {
            return streamInternal();
        }
    }

    public final String getDescriptiveFilename() {
        var patt = Pattern.compile("\\.xml\\b");

        return filenames.stream()
            .filter(filename -> patt.matcher(filename).find()) // discard index files in multistream dumps
            .map(filename -> filename.replaceFirst("\\d+\\.(\\w+)(?:-p\\d+p\\d+)?", "X.$1")) // partitioned dumps
            .findAny()
            .get();
    }

    public final String getDirectoryName() {
        return dirName;
    }

    private static Pattern makeFilenamePattern(String database, String dirName, String regex) {
        var stringSub = new StringSubstitutor(Map.of("database", database, "date", dirName));
        return Pattern.compile(stringSub.replace(regex));
    }

    private static Optional<XMLDump> fetchAndParseJsonConfig(DumpHandler handler, String database, String dirName, String content, XMLDumpTypes type, XMLDumpFactory factory) {
        var json = new JSONObject(content);
        var jobs = json.getJSONObject("jobs");
        var key = type.optConfigKey().get();
        var namingSchemeRegex = type.getNamingSchemeRegex();

        if (type.optFallback().isPresent()) {
            var fallback = type.optFallback().get();

            if (!jobs.has(key)) {
                key = fallback.optConfigKey().get();
            } else if (!jobs.has(fallback.optConfigKey().get())) {
                namingSchemeRegex = fallback.getNamingSchemeRegex();
            }
        }

        var config = jobs.getJSONObject(key);

        if (config.getString("status").equals("done")) {
            var patt = makeFilenamePattern(database, dirName, namingSchemeRegex);

            var filenames = config.getJSONObject("files").keySet().stream()
                .filter(filename -> patt.matcher(filename).matches())
                .sorted(getFilenameComparator()) // sadly, JSONObject doesn't preserve insertion order
                .toList();

            return Optional.of(factory.create(handler, database, dirName, filenames));
        }

        return Optional.empty();
    }

    private static Comparator<String> getFilenameComparator() {
        return Comparator.comparingInt(filename -> {
            var m = PATT_FILENAME_ID.matcher(filename);

            if (m.find()) {
                var slice = m.group("slice");
                var start = m.group("start");

                if (start != null) {
                    return Integer.parseInt(start);
                } else if (slice != null) {
                    return Integer.parseInt(slice);
                }
            }

            return 0;
        });
    }

    private static Optional<XMLDump> fetchAndParseIncrementalConfig(DumpHandler handler, String database, String dirName, String content, XMLDumpTypes type, XMLDumpFactory factory) {
        if (content.equals("done:all")) {
            var patt = makeFilenamePattern(database, dirName, type.getNamingSchemeRegex());

            var filenames = handler.listDirectoryContents(database, dirName, false).stream()
                .filter(filename -> patt.matcher(filename).matches())
                .toList();

            return Optional.of(factory.create(handler, database, dirName, filenames));
        }

        return Optional.empty();
    }

    static Optional<XMLDump> fetchDirectory(DumpHandler handler, String database, String dirName, XMLDumpTypes type, XMLDumpFactory factory) {
        var patt = makeFilenamePattern(database, dirName, type.getNamingSchemeRegex());
        var allFilenames = handler.listDirectoryContents(database, dirName, false);

        var filtered = allFilenames.stream()
            .filter(filename -> patt.matcher(filename).matches())
            .toList();

        if (filtered.isEmpty() && type.optFallback().isPresent()) {
            var fallback = type.optFallback().get();
            var pattFallback = makeFilenamePattern(database, dirName, fallback.getNamingSchemeRegex());

            filtered = allFilenames.stream()
                .filter(filename -> pattFallback.matcher(filename).matches())
                .toList();
        }

        if (!filtered.isEmpty()) {
            return Optional.of(factory.create(handler, database, dirName, filtered));
        }

        return Optional.empty();
    }

    static Optional<XMLDump> fetchAndParseConfig(DumpHandler handler, String database, String dirName, XMLDumpTypes type, XMLDumpFactory factory) {
        if (type.isIncremental()) {
            return fetchAndParseIncrementalConfig(handler, database, dirName, handler.getFileContent(database, dirName, STATUS_INCR), type, factory);
        } else {
            return fetchAndParseJsonConfig(handler, database, dirName, handler.getFileContent(database, dirName, STATUS_JSON), type, factory);
        }
    }

    public XMLDump filterTitles(Collection<String> titles) {
        if (ids != null) {
            throw new IllegalStateException("Cannot filter titles on a dump that is already filtered by page IDs");
        }

        this.titles = Collections.unmodifiableSet(new HashSet<>(Objects.requireNonNull(titles)));
        return this;
    }

    public XMLDump filterIds(Collection<Long> ids) {
        if (titles != null) {
            throw new IllegalStateException("Cannot filter page IDs on a dump that is already filtered by titles");
        }

        this.ids = Collections.unmodifiableSortedSet(new TreeSet<>(Objects.requireNonNull(ids)));
        return this;
    }
}

class MultistreamXMLDump extends XMLDump {
    MultistreamXMLDump(DumpHandler handler, String database, String dirName, List<String> filenames) {
        super(handler, database, dirName, filenames);
    }

    @Override
    public Stream<XMLRevision> stream() {
        var grouped = filenames.stream()
            .collect(Collectors.groupingBy(
                filename -> filename.replaceFirst("\\d*\\.xml", "").replaceFirst("-index\\d*\\.txt", ""),
                LinkedHashMap::new,
                // don't drop type parameter, i.e. `TreeSet<>`; compiler bug fixed in JDK 20
                // https://github.com/openjdk/jdk/pull/10897
                Collectors.toCollection(() -> new TreeSet<String>(Comparator.reverseOrder()))
            ))
            .values();

        if (grouped.stream().anyMatch(l -> l.size() != 2)) {
            throw new IllegalStateException("Unexpected number of files in grouped multistream dump");
        }

        if (grouped.size() == 1) {
            var el = grouped.iterator().next();

            // preserve SIZED+SUBSIZED characteristics
            return getReader(el.first(), el.last()).getStAXReaderStream();
        }

        var stream = grouped.stream()
            .filter(group -> tryFilterPageId(group.first()))
            .map(group -> getReader(group.first(), group.last()))
            .flatMap(XMLConcatenatedStreamDumpReader::getStAXReaderStream);

        var sp = Spliterators.spliteratorUnknownSize(stream.iterator(), StAXDumpReader.BASIC_CHARACTERISTICS);
        return StreamSupport.stream(sp, false);
    }

    private FileChannel getDumpChannel(String filename) {
        try {
            return FileChannel.open(Paths.get(handler.makePath(database, dirName, filename)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private XMLConcatenatedStreamDumpReader getReader(String main, String index) {
        var dumpChannel = getDumpChannel(main);
        var indexStream = handler.getInputStream(database, dirName, index);

        if (titles != null) {
            return XMLConcatenatedStreamDumpReader.ofTitles(dumpChannel, indexStream, titles);
        } else if (ids != null) {
            return XMLConcatenatedStreamDumpReader.ofPageIds(dumpChannel, indexStream, ids);
        } else {
            throw new IllegalStateException("Missing titles or ids");
        }
    }
}

abstract class XMLDumpFactory {
    abstract XMLDump create(DumpHandler handler, String database, String dirName, List<String> filenames);
}

class XMLDumpFactoryImpl extends XMLDumpFactory {
    @Override
    XMLDump create(DumpHandler handler, String database, String dirName, List<String> filenames) {
        return new XMLDump(handler, database, dirName, filenames);
    }
}

class MultistreamXMLDumpFactoryImpl extends XMLDumpFactory {
    @Override
    XMLDump create(DumpHandler handler, String database, String dirName, List<String> filenames) {
        return new MultistreamXMLDump(handler, database, dirName, filenames);
    }
}

interface DumpHandler {
    String makePath(String database, String date, String filename);
    List<String> listDirectoryContents(String database, String date, boolean isDirectory);
    String getFileContent(String database, String date, String filename);
    InputStream getInputStream(String database, String date, String filename);
}

class LocalDumpHandler implements DumpHandler {
    private final Path path;

    LocalDumpHandler(Path path) {
        this.path = Objects.requireNonNull(path);
    }

    @Override
    public String makePath(String database, String date, String filename) {
        return path.resolve(database).resolve(date).resolve(filename).toString();
    }

    @Override
    public List<String> listDirectoryContents(String database, String date, boolean isDirectory) {
        try (var paths = Files.walk(path.resolve(database).resolve(date), 1)) {
            return paths
                .filter(isDirectory ? Files::isDirectory : Files::isRegularFile)
                .map(p -> p.getFileName().toString())
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("No such database: " + database, e);
        }
    }

    @Override
    public String getFileContent(String database, String date, String filename) {
        try {
            return Files.readString(path.resolve(database).resolve(date).resolve(filename));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public InputStream getInputStream(String database, String date, String filename) {
        System.out.println("Reading " + filename);

        try {
            return Files.newInputStream(path.resolve(database).resolve(date).resolve(filename));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

class RemoteDumpHandler implements DumpHandler {
    private final String baseUrl;

    RemoteDumpHandler(String baseUrl) {
        this.baseUrl = Objects.requireNonNull(baseUrl);
    }

    @Override
    public String makePath(String database, String date, String filename) {
        return (baseUrl + "/" + database + "/" + date + "/" + filename).replaceAll("(?<!:)/{2,}", "/");
    }

    @Override
    public List<String> listDirectoryContents(String database, String date, boolean isDirectory) {
        try {
            return Jsoup.connect(makePath(database, date, "")).get().getElementsByTag("a").stream()
                .map(Element::text)
                .filter(text  -> !isDirectory ^ text.endsWith("/"))
                .map(text -> isDirectory ? text.substring(0, text.length() - 1) : text)
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String getFileContent(String database, String date, String filename) {
        try (var is = new URL(makePath(database, date, filename)).openStream()) {
            return IOUtils.toString(is, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public InputStream getInputStream(String database, String date, String filename) {
        System.out.println("Reading " + filename);

        try {
            return new URL(makePath(database, date, filename)).openStream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
