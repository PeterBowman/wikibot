package com.github.wikibot.tasks.plwikt;

import java.io.IOException;
import java.io.Serializable;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.security.auth.login.CredentialException;
import javax.security.auth.login.LoginException;

import org.apache.commons.collections4.map.ListOrderedMap;
import org.wikipedia.Wiki;
import org.wikiutils.ParseUtils;

import com.github.wikibot.main.Wikibot;
import com.github.wikibot.parsing.ParsingException;
import com.github.wikibot.parsing.plwikt.Field;
import com.github.wikibot.parsing.plwikt.FieldTypes;
import com.github.wikibot.parsing.plwikt.Page;
import com.github.wikibot.parsing.plwikt.Section;
import com.github.wikibot.utils.Inflector;
import com.github.wikibot.utils.Login;
import com.github.wikibot.utils.PageContainer;
import com.thoughtworks.xstream.XStream;

public final class PolishSurnamesInflection {
    private static final Path LOCATION = Paths.get("./data/tasks.plwikt/PolishSurnamesInflection/");

    private static final String SURNAME_CATEGORY = "Język polski - nazwiska";

    private static final String MASCULINE_SURNAME_TEMPLATE_NAME = "polskie nazwisko męskie";
    private static final String FEMININE_SURNAME_TEMPLATE_NAME = "polskie nazwisko żeńskie";

    private static final String DECLINABLE_NOUN_TEMPLATE_NAME = "odmiana-rzeczownik-polski";
    private static final String INDECLINABLE_NOUN_TEMPLATE_NAME = "nieodm-rzeczownik-polski";

    private static final String MASCULINE_ADJ_INFLECTION_TEMPLATE_NAME_KI = "odmiana-męskie-nazwisko-polskie-ki";
    private static final String FEMININE_ADJ_INFLECTION_TEMPLATE_NAME_KA = "odmiana-żeńskie-nazwisko-polskie-ka";

    private static final String MASCULINE_ADJ_INFLECTION_TEMPLATE_NAME_NY = "odmiana-męskie-nazwisko-polskie-ny";
    private static final String FEMININE_ADJ_INFLECTION_TEMPLATE_NAME_NA = "odmiana-żeńskie-nazwisko-polskie-na";

    private static final String EDIT_SUMMARY = "uzupełnienie odmiany na podstawie " + Inflector.MAIN_URL;

    private static final String LOG_TARGET_PAGE = "Wikipedysta:PBbot/odmiana polskich nazwisk";
    private static final String ERROR_SUBPAGE = "/błędy";

    private static final int URL_CONSECUTIVE_REQUEST_THROTTLE_MS = 30000;
    private static final int URL_BATCH_REQUEST_THROTTLE_MS = 60000;
    private static final int IO_THROTTLE_MS = 120000;

    private static final Pattern P_DEF;
    private static final Pattern P_NUM;
    private static final Pattern P_NUMS;

    private static final String LOG_INTRO;

    private static final Wikibot wb = Wikibot.newSession("pl.wiktionary.org");

    private static enum SurnameGender {
        MASCULINE,
        FEMININE
    }

    static {
        P_DEF = Pattern.compile("^: *( *\\((\\d)\\.(\\d)\\) *).+");
        P_NUM = Pattern.compile("(\\d+)(?:\\.(\\d+)(?:[-–](\\d+))?)?", Pattern.MULTILINE);
        Pattern temp = Pattern.compile(P_NUM.pattern() + "|(?:" + P_NUM.pattern() + ",? *)+");
        P_NUMS = Pattern.compile("^(?=(?:: *+)?((?:\\( *(?:" + temp.pattern() + ") *\\),? *)+) *(?!\\( *\\d))", Pattern.MULTILINE);

        LOG_INTRO = // TODO
            "Spis wyjątków, ktore napotkał automat w trakcie weryfikowania odmiany polskich nazwisk. " +
            //"Działa w podwójnym trybie: wstawia brakujące tabelki i weryfikuje zawartość istniejących. " +
            "Dane pochodzą z witryny " + Inflector.MAIN_URL + " " +
            String.format("([[%s%s|podstrona z błędami]])", LOG_TARGET_PAGE, ERROR_SUBPAGE) + ". " +
            "Rozpoznawane są wyłącznie te hasła, które zawierają następujące szablony w polu znaczeń: " +
            Stream.of(MASCULINE_SURNAME_TEMPLATE_NAME, FEMININE_SURNAME_TEMPLATE_NAME)
                .map(templateName -> String.format("{{s|%s}}", templateName))
                .collect(Collectors.joining(", ")) + ". " +
            "Zmiany wykonane ręcznie na tej stronie nie zostaną uwzględnione przez bota.";
    }

    public static void main(String[] args) throws Exception {
        Login.login(wb);

        Path fStorage = LOCATION.resolve("storage.xml");
        Path fHistory = LOCATION.resolve("history.xml");

        Set<Item> storage = deserializeSet(fStorage);
        Set<Item> history = deserializeSet(fHistory);

        List<LogEntry> logs = new ArrayList<>();

        List<PageContainer> pages = wb.getContentOfCategorymembers(SURNAME_CATEGORY, Wiki.MAIN_NAMESPACE);

        wb.setThrottle(5000);
        wb.setMarkMinor(false);

        try {
            pages.forEach(pc -> doWork(pc, storage, history, logs));
        } finally {
            Files.writeString(fStorage, new XStream().toXML(storage));
            Files.writeString(fHistory, new XStream().toXML(history));
        }

        Collator collator = Collator.getInstance(new Locale("pl", "PL"));
        collator.setStrength(Collator.SECONDARY);
        Collections.sort(logs, new LogEntryComparator(collator));

        storeLogs(logs);
    }

    private static Set<Item> deserializeSet(Path path) {
        if (Files.exists(path)) {
            @SuppressWarnings("unchecked")
            var set = (Set<Item>) new XStream().fromXML(path.toFile());
            return set;
        } else {
            return new HashSet<>(5000);
        }
    }

    private static void doWork(PageContainer pc, Set<Item> storage, Set<Item> history, List<LogEntry> logs) {
        InflectionStructure is = new InflectionStructure(pc.getTitle(), storage);
        FieldEditor fe;

        try {
            fe = extractAndValidateData(pc, is);
        } catch (RuntimeException e) {
            LogEntry le = new LogEntry(pc.getTitle(), e.getMessage());
            logs.add(le);
            return;
        }

        try {
            maybeFetchInflection(storage, is, logs);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        try {
            is.isEditedMasculine = processInflection(is.getView(SurnameGender.MASCULINE), fe, storage, history);
        } catch (RuntimeException e) {
            String msg = String.format("%s (zn. %s)", e.getMessage(), is.masculineNum);
            LogEntry le = new LogEntry(pc.getTitle(), msg);
            logs.add(le);
        }

        try {
            is.isEditedFeminine = processInflection(is.getView(SurnameGender.FEMININE), fe, storage, history);
        } catch (RuntimeException e) {
            String msg = String.format("%s (zn. %s)", e.getMessage(), is.feminineNum);
            LogEntry le = new LogEntry(pc.getTitle(), msg);
            logs.add(le);
        }

        if (is.isEditedMasculine || is.isEditedFeminine) {
            try {
                wb.edit(pc.getTitle(), fe.getPageContainer().toString(), EDIT_SUMMARY, pc.getTimestamp());
                is.insertIntoSet(history);
            } catch (CredentialException | ConcurrentModificationException e) {
                e.printStackTrace();
            } catch (AssertionError | LoginException e) {
                throw new RuntimeException(e);
            } catch (IOException e) {
                e.printStackTrace();

                try {
                    Thread.sleep(IO_THROTTLE_MS);
                } catch (InterruptedException ee) {}
            }
        }
    }

    private static FieldEditor extractAndValidateData(PageContainer pc, InflectionStructure is) {
        Field definitions, inflection;

        try {
            Page p = Page.wrap(pc);
            Section s = p.getPolishSection().get();
            definitions = s.getField(FieldTypes.DEFINITIONS).get();
            inflection = s.getField(FieldTypes.INFLECTION).get();
        } catch (ParsingException e) {
            throw new RuntimeException("wystąpił błąd podczas parsowania wikitekstu");
        } catch (NoSuchElementException e) {
            throw new RuntimeException("brak sekcji polskiej lub pól „znaczenia” / „odmiana”");
        }

        try {
            String content = definitions.getContent();

            if (!ParseUtils.getTemplates(MASCULINE_SURNAME_TEMPLATE_NAME, content).isEmpty()) {
                is.masculineNum = extractDefinitionNumber(content, MASCULINE_SURNAME_TEMPLATE_NAME);
            }

            if (!ParseUtils.getTemplates(FEMININE_SURNAME_TEMPLATE_NAME, content).isEmpty()) {
                is.feminineNum = extractDefinitionNumber(content, FEMININE_SURNAME_TEMPLATE_NAME);
            }
        } catch (NoSuchElementException e) {
            String escaped = String.format("<nowiki>{{%s}}</nowiki>", e.getMessage());
            throw new RuntimeException("nie udało się wydobyć numeru znaczenia dla szablonu " + escaped);
        }

        if (is.masculineNum == null && is.feminineNum == null) {
            throw new RuntimeException("nie rozpoznano żadnego szablonu nazwisk w polu znaczeń");
        }

        try {
            return FieldEditor.parse(inflection);
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("nieobsługiwany format wikitekstu w polu odmiany");
        }
    }

    private static MeaningNumber extractDefinitionNumber(String text, String targetTemplate) {
        return Pattern.compile("\n").splitAsStream(text)
            .filter(line -> !ParseUtils.getTemplates(targetTemplate, line).isEmpty())
            .map(P_DEF::matcher)
            .filter(Matcher::find)
            .map(m -> new MeaningNumber(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))))
            .findAny()
            .orElseThrow(() -> new NoSuchElementException(targetTemplate));
    }

    private static void maybeFetchInflection(Set<Item> storage, InflectionStructure is, List<LogEntry> logs) throws IOException {
        Map<Inflector, Item> fetchMap = new HashMap<>();

        fillFetchMap(is.getView(SurnameGender.MASCULINE), fetchMap, storage);
        fillFetchMap(is.getView(SurnameGender.FEMININE), fetchMap, storage);

        if (fetchMap.isEmpty()) {
            return;
        }

        for (Map.Entry<Inflector, Item> entry : fetchMap.entrySet()) {
            Inflector inflector = entry.getKey();
            Item item = entry.getValue();

            System.out.printf("Fetching %s (%s)...%n", item.surname, item.gender);

            try {
                inflector.fetchEntry();
                item.cases = inflector.getInflection();
                storage.add(item);
            } catch (UnknownHostException | SocketException e) {
                throw e;
            } catch (IOException e) {
                e.printStackTrace();
            } catch (Inflector.InflectorException e) {
                LogEntry le = new ErrorLogEntry(is.surname, e.getExtendedMessage());
                logs.add(le);
            } finally {
                try {
                    Thread.sleep(URL_CONSECUTIVE_REQUEST_THROTTLE_MS);
                } catch (InterruptedException e) {}
            }
        }

        try {
            Thread.sleep(URL_BATCH_REQUEST_THROTTLE_MS);
        } catch (InterruptedException e) {}
    }

    private static void fillFetchMap(InflectionStructure.View view, Map<Inflector, Item> map, Set<Item> set) {
        if (view.mn != null) {
            if (!set.contains(view.singular)) {
                map.put(new Inflector(view.surname, view.singular.gender), view.singular);
            }

            if (!set.contains(view.plural)) {
                map.put(new Inflector(view.surname, view.plural.gender), view.plural);
            }
        }
    }

    private static boolean processInflection(InflectionStructure.View view, FieldEditor fe,
            Set<Item> storage, Set<Item> history) {
        if (view.mn != null && storage.contains(view.singular) && storage.contains(view.plural)) {
            if (fe.containsMeaning(view.mn)) {
                String chunk = fe.retrieveChunk(view.mn);
                compareTemplateData(chunk, view.singular.cases, view.plural.cases);
            } else if (!history.contains(view.singular) && !history.contains(view.plural)) {
                String chunk = generateTemplate(view.singular.cases, view.plural.cases);
                boolean inserted = fe.insertChunk(view.mn, chunk); // should never be false, see containsMeaning()

                if (inserted) {
                    fe.updateFieldContent();
                    return false; // TODO: allow edit mode
                }
            }
        }

        return false;
    }

    private static void compareTemplateData(String text, Inflector.Cases singular, Inflector.Cases plural) {
        List<String> declinableTemplates = ParseUtils.getTemplates(DECLINABLE_NOUN_TEMPLATE_NAME, text);
        List<String> indeclinableTemplates = ParseUtils.getTemplates(INDECLINABLE_NOUN_TEMPLATE_NAME, text);
        List<String> masculineAdjTemplatesKi = ParseUtils.getTemplates(MASCULINE_ADJ_INFLECTION_TEMPLATE_NAME_KI, text);
        List<String> feminineAdjTemplatesKa = ParseUtils.getTemplates(FEMININE_ADJ_INFLECTION_TEMPLATE_NAME_KA, text);
        List<String> masculineAdjTemplatesNy = ParseUtils.getTemplates(MASCULINE_ADJ_INFLECTION_TEMPLATE_NAME_NY, text);
        List<String> feminineAdjTemplatesNa = ParseUtils.getTemplates(FEMININE_ADJ_INFLECTION_TEMPLATE_NAME_NA, text);

        if (
            declinableTemplates.isEmpty() && indeclinableTemplates.isEmpty() &&
            masculineAdjTemplatesKi.isEmpty() && feminineAdjTemplatesKa.isEmpty() &&
            masculineAdjTemplatesNy.isEmpty() && feminineAdjTemplatesNa.isEmpty()
        ) {
            throw new RuntimeException("odmiana dla danego znaczenia istnieje, lecz brak szablonu");
        }

        if (
            declinableTemplates.size() + indeclinableTemplates.size() +
            masculineAdjTemplatesKi.size() + feminineAdjTemplatesKa.size() +
            masculineAdjTemplatesNy.size() + feminineAdjTemplatesNa.size() > 1
        ) {
            throw new RuntimeException("więcej niż jeden szablon odmiany");
        }

        if (!indeclinableTemplates.isEmpty()) {
            if (singular.isIndeclinable() && plural.isIndeclinable()) {
                return;
            } else {
                throw new RuntimeException("użyto szablonu dla rzeczowników nieodmiennych mimo odmienności nazwiska");
            }
        }

        Map<String, String> params;

        if (
            !masculineAdjTemplatesKi.isEmpty() || !feminineAdjTemplatesKa.isEmpty() ||
            !masculineAdjTemplatesNy.isEmpty() || !feminineAdjTemplatesNa.isEmpty()
        ) {
            params = new HashMap<>(14, 1);

            String nominative = singular.getNominative();
            String starter = nominative.substring(0, nominative.length() - 2);

            final boolean isMasculine = !masculineAdjTemplatesKi.isEmpty() || !masculineAdjTemplatesNy.isEmpty();
            final boolean isKiKaEnding = !masculineAdjTemplatesKi.isEmpty() || !feminineAdjTemplatesKa.isEmpty();

            StringQuadOperator buildCase = (mascKi, femKa, mascNy, femNa) ->
                starter + (isMasculine ? (isKiKaEnding ? mascKi : mascNy) : (isKiKaEnding ? femKa : femNa));

            params.put("Mianownik lp", buildCase.apply("ki", "ka", "ny", "na"));
            params.put("Dopełniacz lp", buildCase.apply("kiego", "kiej", "nego", "nej"));
            params.put("Celownik lp", buildCase.apply("kiemu", "kiej", "nemu", "nej"));
            params.put("Biernik lp", buildCase.apply("kiego", "ką", "nego", "ną"));
            params.put("Narzędnik lp", buildCase.apply("kim", "ką", "nym", "ną"));
            params.put("Miejscownik lp", buildCase.apply("kim", "kiej", "nym", "nej"));
            params.put("Wołacz lp", buildCase.apply("ki", "ka", "ny", "na"));
            params.put("Mianownik lm", buildCase.apply("cy", "kie", "ni", "ne")); // obviate depreciative form
            params.put("Dopełniacz lm", buildCase.apply("kich", "kich", "nych", "nych"));
            params.put("Celownik lm", buildCase.apply("kim", "kim", "nym", "nym"));
            params.put("Biernik lm", buildCase.apply("kich", "kie", "nych", "ne"));
            params.put("Narzędnik lm", buildCase.apply("kimi", "kimi", "nymi", "nymi"));
            params.put("Miejscownik lm", buildCase.apply("kich", "kich", "nych", "nych"));
            params.put("Wołacz lm", buildCase.apply("cy", "kie", "ni", "ne"));
        } else {
            params = ParseUtils.getTemplateParametersWithValue(declinableTemplates.get(0));
        }

        // TODO: add tests for depreciative forms and variants

        testCase("Mianownik lp", singular.getNominative(), params);
        testCase("Dopełniacz lp", singular.getGenitive(), params);
        testCase("Celownik lp", singular.getDative(), params);
        testCase("Biernik lp", singular.getAccusative(), params);
        testCase("Narzędnik lp", singular.getInstrumental(), params);
        testCase("Miejscownik lp", singular.getLocative(), params);
        testCase("Wołacz lp", singular.getVocative(), params);
        testCase("Mianownik lm", plural.getNominative(), params);
        testCase("Dopełniacz lm", plural.getGenitive(), params);
        testCase("Celownik lm", plural.getDative(), params);
        testCase("Biernik lm", plural.getAccusative(), params);
        testCase("Narzędnik lm", plural.getInstrumental(), params);
        testCase("Miejscownik lm", plural.getLocative(), params);
        testCase("Wołacz lm", plural.getVocative(), params);
    }

    private static void testCase(String paramName, String targetCase, Map<String, String> params) {
        String value = params.getOrDefault(paramName, "").trim();

        if (value.isEmpty()) {
            throw new RuntimeException("brakujący lub niewypełniony przypadek „" + paramName + "”");
        }

        value = value.replaceAll("\\{\\{[^\\}]+?\\}\\}", "");
        value = value.replaceAll("(?i)<ref\\b[^>]+?(?<=/)>", "");
        value = value.replaceAll("(?i)<ref\\b[^>]*?>[^<]*?</ref *>", "");

        String msg = String.format(
            "nieprawidłowa forma lub błąd odczytu dla przypadka „%s” (wartość: <nowiki>%s</nowiki>, oczekiwano: %s)",
            paramName, value, targetCase
        );

        Set<String> set = Pattern.compile(Inflector.CASE_SEPARATOR).splitAsStream(targetCase).collect(Collectors.toSet());

        Pattern.compile("/|<(?i:br) */?>|,").splitAsStream(value)
            .map(String::trim)
            .filter(set::contains)
            .findAny()
            .orElseThrow(() -> new RuntimeException(msg));
    }

    private static String generateTemplate(Inflector.Cases singular, Inflector.Cases plural) {
        if (singular.isIndeclinable() && plural.isIndeclinable()) {
            return String.format("{{%s}}", INDECLINABLE_NOUN_TEMPLATE_NAME);
        }

        StringBuilder sb = new StringBuilder(1000);

        sb.append("{{").append(DECLINABLE_NOUN_TEMPLATE_NAME).append("\n");

        sb.append("|Mianownik lp = ").append(singular.getNominative()).append("\n");
        sb.append("|Dopełniacz lp = ").append(singular.getGenitive()).append("\n");
        sb.append("|Celownik lp = ").append(singular.getDative()).append("\n");
        sb.append("|Biernik lp = ").append(singular.getAccusative()).append("\n");
        sb.append("|Narzędnik lp = ").append(singular.getInstrumental()).append("\n");
        sb.append("|Miejscownik lp = ").append(singular.getLocative()).append("\n");
        sb.append("|Wołacz lp = ").append(singular.getVocative()).append("\n");

        sb.append("|Mianownik lm = ");

        String depreciative = plural.getDepreciative();

        if (depreciative != null) {
            sb.append("{{ndepr}} ").append(plural.getNominative()).append(" / {{depr}} ").append(depreciative);
        } else {
            sb.append(plural.getNominative());
        }

        sb.append("\n");

        sb.append("|Dopełniacz lm = ").append(plural.getGenitive()).append("\n");
        sb.append("|Celownik lm = ").append(plural.getDative()).append("\n");
        sb.append("|Biernik lm = ").append(plural.getAccusative()).append("\n");
        sb.append("|Narzędnik lm = ").append(plural.getInstrumental()).append("\n");
        sb.append("|Miejscownik lm = ").append(plural.getLocative()).append("\n");

        sb.append("|Wołacz lm = ");

        if (depreciative != null) {
            sb.append("{{ndepr}} ").append(plural.getVocative()).append(" / {{depr}} ").append(depreciative);
        } else {
            sb.append(plural.getVocative());
        }

        sb.append("\n").append("}}");

        return sb.toString();
    }

    private static void storeLogs(List<LogEntry> logs) throws Exception {
        Path fLogsHash = LOCATION.resolve("logs-hash.txt");
        Path fErrorsHash = LOCATION.resolve("errors-hash.txt");

        List<? extends LogEntry> errors = logs.stream()
            .filter(log -> log instanceof ErrorLogEntry)
            .toList();

        logs.removeAll(errors);

        final String timestamp = "Ostatnia aktualizacja: ~~~~~.";

        if (!Files.exists(fLogsHash) || Integer.parseInt(Files.readString(fLogsHash)) != logs.hashCode()) {
            String text = LOG_INTRO + "\n\n" + timestamp + "\n----\n" + logs.stream()
                .map(LogEntry::getWikitext)
                .collect(Collectors.joining("\n"));

            wb.setMarkBot(false);
            wb.edit(LOG_TARGET_PAGE, text, "aktualizacja");

            Files.writeString(fLogsHash, Integer.toString(logs.hashCode()));
        }

        if (!Files.exists(fErrorsHash) || Integer.parseInt(Files.readString(fErrorsHash)) != errors.hashCode()) {
            String text = timestamp + "\n\n" + errors.stream()
                .map(LogEntry::getWikitext)
                .collect(Collectors.joining("\n"));

            wb.setMarkBot(true);
            wb.edit(LOG_TARGET_PAGE + ERROR_SUBPAGE, text, "aktualizacja");

            Files.writeString(fErrorsHash, Integer.toString(errors.hashCode()));
        }
    }

    private static class InflectionStructure {
        final String surname;

        final Item masculineSingular;
        final Item masculinePlural;
        final Item feminineSingular;
        final Item femininePlural;

        MeaningNumber masculineNum;
        MeaningNumber feminineNum;

        boolean isEditedMasculine;
        boolean isEditedFeminine;

        InflectionStructure(String surname, Set<Item> storage) {
            this.surname = surname;

            masculineSingular = initializeItem(Inflector.Gender.MASCULINE_SINGULAR, storage);
            masculinePlural = initializeItem(Inflector.Gender.MASCULINE_PLURAL, storage);
            feminineSingular = initializeItem(Inflector.Gender.FEMININE_SINGULAR, storage);
            femininePlural = initializeItem(Inflector.Gender.FEMININE_PLURAL, storage);
        }

        private Item initializeItem(Inflector.Gender gender, Set<Item> storage) {
            Item item = new Item(surname, gender);

            if (storage.contains(item)) {
                item.cases = storage.stream()
                    .filter(item::equals)
                    .map(i -> i.cases)
                    .findAny()
                    .orElse(null);
            }

            return item;
        }

        View getView(SurnameGender gender) {
            return switch (gender) {
                case MASCULINE -> new View(surname, masculineSingular, masculinePlural, masculineNum);
                case FEMININE -> new View(surname, feminineSingular, femininePlural, feminineNum);
                default -> throw new UnsupportedOperationException(gender.toString());
            };
        }

        void insertIntoSet(Set<Item> set) {
            if (isEditedMasculine) {
                set.add(masculineSingular);
                set.add(masculinePlural);
            }

            if (isEditedFeminine) {
                set.add(feminineSingular);
                set.add(femininePlural);
            }
        }

        @Override
        public String toString() {
            return String.format(
                "[%s %s: %s, %s || %s %s: %s, %s]",
                surname, masculineNum, masculineSingular, masculinePlural,
                surname, feminineNum, feminineSingular, femininePlural
            );
        }

        record View (String surname, Item singular, Item plural, MeaningNumber mn) {}
    }

    private static class MeaningNumber implements Comparable<MeaningNumber> {
        Integer primary;
        Integer secondary;

        MeaningNumber(Integer primary, Integer secondary) {
            Objects.requireNonNull(primary);
            this.primary = primary;
            this.secondary = secondary;
        }

        boolean contains(MeaningNumber mn) {
            if (!primary.equals(mn.primary)) {
                return false;
            }

            if (secondary == null) {
                return true;
            }

            return secondary.equals(mn.secondary);
        }

        boolean containedIn(MeaningNumber mn) {
            if (!primary.equals(mn.primary)) {
                return false;
            }

            if (mn.secondary == null) {
                return true;
            }

            return secondary.equals(mn.secondary);
        }

        @Override
        public int compareTo(MeaningNumber mn) {
            if (primary.intValue() > mn.primary.intValue()) {
                return 1;
            } else if (primary.intValue() < mn.primary.intValue()) {
                return -1;
            }

            if (secondary == null && mn.secondary != null) {
                return -1;
            } else if (secondary != null && mn.secondary == null) {
                return 1;
            } else if (secondary == null && mn.secondary == null) {
                return 0;
            }

            return Integer.compare(secondary.intValue(), mn.secondary.intValue());
        }

        @Override
        public int hashCode() {
            return primary.hashCode() + Optional.ofNullable(secondary).map(v -> v.hashCode()).orElse(-1);
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            } else if (o instanceof MeaningNumber mn) {
                return primary.equals(mn.primary) && secondary.equals(mn.secondary);
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            if (secondary == null) {
                return String.format("(%d)", primary);
            } else {
                return String.format("(%d.%d)", primary, secondary);
            }
        }
    }

    private static class FieldEditor {
        Field field;
        ListOrderedMap<List<MeaningNumber>, String> data;

        private FieldEditor(Field field, ListOrderedMap<List<MeaningNumber>, String> data) {
            this.field = field;
            this.data = data;

            validateNumbering();
            validateChunks();
        }

        static FieldEditor parse(Field field) {
            ListOrderedMap<List<MeaningNumber>, String> data = new ListOrderedMap<>();
            String content = field.getContent();

            if (content.isBlank()) {
                return new FieldEditor(field, data);
            }

            for (String chunk : P_NUMS.split(field.getContent(), 0)) {
                Matcher m1 = P_NUMS.matcher(chunk);
                m1.find();
                String target = m1.group(1);
                Matcher m2 = P_NUM.matcher(target);
                List<MeaningNumber> mns = new ArrayList<>();

                while (m2.find()) {
                    Integer primary, secondary, range;

                    try {
                        primary = Integer.parseInt(m2.group(1));
                    } catch (NumberFormatException e) {
                        primary = null; // will never happen, catch anyway
                    }

                    try {
                        secondary = Integer.parseInt(m2.group(2));
                    } catch (NumberFormatException e) {
                        MeaningNumber mn = new MeaningNumber(primary, null);
                        mns.add(mn);
                        continue;
                    }

                    try {
                        range = Integer.parseInt(m2.group(3));
                    } catch (NumberFormatException e) {
                        range = secondary;
                    }

                    for (int n = secondary, limit = range + 1; n < limit; n++) {
                        MeaningNumber mn = new MeaningNumber(primary, n);
                        mns.add(mn);
                    }
                }

                data.put(mns, chunk.trim());
            }

            return new FieldEditor(field, data);
        }

        private void validateNumbering() {
            String msg = "nieprawidłowa kolejność numeracji w polu „" + field.getFieldType().localised() + "”";
            MeaningNumber last = null;

            for (List<MeaningNumber> mns : data.keySet()) {
                for (int i = 0; i < mns.size(); i++) {
                    MeaningNumber current = mns.get(i);

                    if (last != null) {
                        int comparison = current.compareTo(last);

                        if (comparison == 0) {
                            msg += " " + String.format("(powtórzone „%s”)", current);
                            throw new ValidationException(msg);
                        } else if (comparison == -1) {
                            msg += " " + String.format("(„%s” względem „%s”)", current, last);
                            throw new ValidationException(msg);
                        }
                    }

                    last = current;
                }

                last = mns.get(0);
            }
        }

        private void validateChunks() {
            String msg = "nieprawidłowo sformatowany tekst w polu „" + field.getFieldType().localised() + "”";

            for (Map.Entry<List<MeaningNumber>, String> entry : data.entrySet()) {
                String chunk = entry.getValue();

                if (!chunk.contains("\n")) {
                    continue;
                }

                String substring = chunk.substring(chunk.indexOf("\n") + 1);

                boolean anyMatch = Pattern.compile("\n").splitAsStream(substring)
                    .anyMatch(line -> line.startsWith(":"));

                if (anyMatch) {
                    msg += " " + String.format("(dodatkowe wcięcie pod numerem %s)", entry.getKey());
                    throw new ValidationException(msg);
                }
            }
        }

        boolean containsMeaning(MeaningNumber mn) {
            return data.keyList().stream()
                .flatMap(Collection::stream)
                .anyMatch(mn::containedIn);
        }

        String retrieveChunk(MeaningNumber targetMn) {
            for (Map.Entry<List<MeaningNumber>, String> entry : data.entrySet()) {
                for (MeaningNumber mn : entry.getKey()) {
                    if (targetMn.containedIn(mn)) {
                        return entry.getValue();
                    }
                }
            }

            return null;
        }

        boolean insertChunk(MeaningNumber targetMn, String chunk) {
            chunk = String.format(": %s %s", targetMn, chunk);

            for (int i = 0; i < data.size(); i++) {
                List<MeaningNumber> mns = data.get(i);

                if (targetMn.compareTo(mns.get(0)) == -1) {
                    data.put(i, Arrays.asList(targetMn), chunk);
                    return true;
                }

                for (MeaningNumber mn : mns) {
                    if (mn.contains(targetMn)) {
                        return false;
                    }
                }
            }

            data.put(Arrays.asList(targetMn), chunk);
            return true;
        }

        void updateFieldContent() {
            String content = data.values().stream().collect(Collectors.joining("\n"));
            field.editContent(content, true);
        }

        Page getPageContainer() {
            return (Page) field.getContainingSection().get().getContainingPage().get();
        }

        @Override
        public String toString() {
            return field.toString();
        }
    }

    private static class Item implements Serializable {
        private static final long serialVersionUID = -4380428860632163511L;

        String surname;
        Inflector.Gender gender;
        Inflector.Cases cases;

        Item (String surname, Inflector.Gender gender) {
            this.surname = surname;
            this.gender = gender;
        }

        @Override
        public int hashCode() {
            return surname.hashCode() + gender.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            } else if (o instanceof Item i) {
                return surname.equals(i.surname) && gender.equals(i.gender);
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            if (cases != null) {
                return String.format("[%s, %s%n%s%n]", surname, gender, cases);
            } else {
                return String.format("[%s, %s, %s]", surname, gender, cases);
            }
        }
    }

    private static class LogEntry {
        String title;
        String message;

        LogEntry(String title, String message) {
            this.title = title;
            this.message = message;
        }

        String getWikitext() {
            return String.format("# [[%s]]: %s", title, message);
        }

        @Override
        public int hashCode() {
            return title.hashCode() + message.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (o == this) {
                return true;
            } else if (o instanceof LogEntry le) {
                return title.equals(le.title) && message.equals(le.message);
            } else {
                return false;
            }
        }

        @Override
        public String toString() {
            return String.format("%s: %s", title, message);
        }
    }

    private static class ErrorLogEntry extends LogEntry {
        ErrorLogEntry(String title, String message) {
            super(title, message);
        }
    }

    private static class LogEntryComparator implements Comparator<LogEntry> {
        Collator collator;

        LogEntryComparator(Collator collator) {
            this.collator = collator;
            collator.setStrength(Collator.TERTIARY);
        }

        @Override
        public int compare(LogEntry le1, LogEntry le2) {
            if (le1.title.equals(le2.title)) {
                return le1.message.compareTo(le2.message);
            } else {
                return collator.compare(le1.title, le2.title);
            }
        }
    }

    @FunctionalInterface
    private static interface StringQuadOperator {
        String apply(String arg1, String arg2, String arg3, String arg4);
    }
}

class ValidationException extends RuntimeException {
    private static final long serialVersionUID = 8751387824855503124L;

    public ValidationException(String msg) {
        super(msg);
    }
}
