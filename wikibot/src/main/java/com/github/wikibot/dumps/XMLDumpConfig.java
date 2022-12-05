package com.github.wikibot.dumps;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

public final class XMLDumpConfig {
    private static final Path DEFAULT_PATH = Paths.get("./data/dumps/");
    private static final String DEFAULT_BASE_URL = "https://dumps.wikimedia.org";

    private final String database;
    private Path path;
    private String baseUrl;
    private boolean useLatest;
    private LocalDate refDate;
    private boolean useExactDate;
    private XMLDumpTypes type;

    public XMLDumpConfig(String database) {
        this.database = Objects.requireNonNull(database);
    }

    public XMLDumpConfig local(Path path) {
        this.path = Objects.requireNonNull(path);
        return this;
    }

    public XMLDumpConfig local() {
        return local(DEFAULT_PATH);
    }

    public XMLDumpConfig remote(String baseUrl) {
        this.baseUrl = Objects.requireNonNull(baseUrl);
        return this;
    }

    public XMLDumpConfig remote() {
        return remote(DEFAULT_BASE_URL);
    }

    public XMLDumpConfig latest() {
        this.useLatest = true;
        return this;
    }

    public XMLDumpConfig after(LocalDate date) {
        refDate = Objects.requireNonNull(date);
        return this;
    }

    public XMLDumpConfig after(String date) {
        var parsed = DateTimeFormatter.BASIC_ISO_DATE.parse(Objects.requireNonNull(date));
        return after(LocalDate.from(parsed));
    }

    public XMLDumpConfig at(LocalDate date) {
        refDate = Objects.requireNonNull(date);
        useExactDate = true;
        return this;
    }

    public XMLDumpConfig at(String date) {
        var parsed = DateTimeFormatter.BASIC_ISO_DATE.parse(Objects.requireNonNull(date));
        return at(LocalDate.from(parsed));
    }

    public XMLDumpConfig type(XMLDumpTypes type) {
        this.type = Objects.requireNonNull(type);
        return this;
    }

    public XMLDumpConfig type(String label) {
        return type(XMLDumpTypes.valueOf(Objects.requireNonNull(label)));
    }

    public Optional<XMLDump> fetch() {
        if (type == null) {
            throw new IllegalStateException("No dump type specified");
        }

        var isIncr = type.optConfigKey().isEmpty(); // TODO: move to XMLDump?
        var isMultistream = type.optConfigKey().filter(key -> key.contains("multistream")).isPresent();

        if (isIncr && baseUrl != null) {
            throw new IllegalStateException("Incremental dumps are not available remotely");
        }

        if (isMultistream && baseUrl != null) {
            throw new IllegalStateException("Multistream dumps are not available remotely");
        }

        if (useLatest && refDate != null) {
            throw new IllegalStateException("Cannot specify both latest option and a reference date");
        }

        final DumpHandler handler;
        final XMLDumpFactory factory;

        if (path != null) {
            var resolvedPath = isIncr ? path.resolve("incr") : path.resolve("public");
            handler = new LocalDumpHandler(resolvedPath);
        } else if (baseUrl != null) {
            handler = new RemoteDumpHandler(baseUrl);
        } else {
            throw new IllegalStateException("No source (local or remote) specified");
        }

        if (isMultistream) {
            factory = new MultistreamXMLDumpFactoryImpl();
        } else {
            factory = new XMLDumpFactoryImpl();
        }

        if (useLatest) {
            return XMLDump.fetchDirectory(handler, database, "latest", type, factory);
        }

        var dates = handler.listDirectoryContents(database, true).stream()
            .<TemporalAccessor>mapMulti((dirName, consumer) -> {
                try {
                    consumer.accept(DateTimeFormatter.BASIC_ISO_DATE.parse(dirName));
                } catch (DateTimeParseException e) {}
            })
            .map(LocalDate::from)
            .sorted(Comparator.reverseOrder())
            .toList();

        if (useExactDate) {
            if (!dates.contains(refDate)) {
                return Optional.empty();
            } else {
                var temp = refDate.format(DateTimeFormatter.BASIC_ISO_DATE);
                return XMLDump.fetchAndParseConfig(handler, database, temp, type, factory);
            }
        } else {
            return dates.stream()
                .filter(date -> refDate == null || date.isAfter(refDate))
                .map(date -> date.format(DateTimeFormatter.BASIC_ISO_DATE))
                .map(dirName -> XMLDump.fetchAndParseConfig(handler, database, dirName, type, factory))
                .filter(Optional::isPresent)
                .findFirst()
                .get();
        }
    }

    public static void main(String[] args) throws Exception {
        var dump = new XMLDumpConfig("plwiktionary").type(XMLDumpTypes.PAGES_ARTICLES).remote().fetch().get();
        System.out.println(dump.getDescriptiveFilename());

        try (var stream = dump.stream()) {
            System.out.printf("Total count: %d%n", stream.count());
        }
    }
}
