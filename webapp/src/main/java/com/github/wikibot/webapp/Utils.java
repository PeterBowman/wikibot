package com.github.wikibot.webapp;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedList;

import org.bitbucket.cowwoc.diffmatchpatch.DiffMatchPatch;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Tag;

public final class Utils {
    public static String makePluralPL(int value, String singular, String nominative, String genitive) {
        if (value == 1) {
            return singular;
        }

        String strValue = Integer.toString(value);

        switch (strValue.charAt(strValue.length() - 1)) {
            case '2':
            case '3':
            case '4':
                if (strValue.length() > 1 && strValue.charAt(strValue.length() - 2) == '1') {
                    return genitive;
                } else {
                    return nominative;
                }
            default:
                return genitive;
        }
    }

    public static boolean bitCompare(int bitmask, int target) {
        return (bitmask & target) == target;
    }

    public static String lastPathPart(String path) {
        return path.substring(path.lastIndexOf("/") + 1);
    }

    public static String getDiff(String first, String second) {
        DiffMatchPatch dmp = new DiffMatchPatch();
        LinkedList<DiffMatchPatch.Diff> diffs = dmp.diffMain(first, second);
        dmp.diffCleanupSemantic(diffs);

        Document doc = new Document("");
        doc.outputSettings().prettyPrint(false);

        Element table = new Element(Tag.valueOf("table"), "");
        table.addClass("diff");

        Element td1 = new Element(Tag.valueOf("td"), "");
        td1.addClass("diff-deletedline");

        Element td2 = new Element(Tag.valueOf("td"), "");
        td2.addClass("diff-addedline");

        Element div1 = new Element(Tag.valueOf("div"), "");
        Element div2 = new Element(Tag.valueOf("div"), "");

        for (DiffMatchPatch.Diff diff : diffs) {
            switch (diff.operation) {
                case EQUAL:
                    div1.appendText(diff.text);
                    div2.appendText(diff.text);
                    break;
                case DELETE:
                    Element del = new Element(Tag.valueOf("del"), "");
                    del.addClass("diffchange").addClass("diffchange-inline");
                    del.text(diff.text);
                    div1.appendChild(del);
                    break;
                case INSERT:
                    Element ins = new Element(Tag.valueOf("ins"), "");
                    ins.addClass("diffchange").addClass("diffchange-inline");
                    ins.text(diff.text);
                    div2.appendChild(ins);
                    break;
                default:
                    return null;
            }

        }

        td1.appendChild(div1);
        td2.appendChild(div2);

        Element marker1 = new Element(Tag.valueOf("td"), "");
        marker1.addClass("diff-marker").text("−");

        Element marker2 = new Element(Tag.valueOf("td"), "");
        marker2.addClass("diff-marker").text("+");

        table.appendChild(new Element(Tag.valueOf("tr"), "").appendChild(marker1).appendChild(td1));
        table.appendChild(new Element(Tag.valueOf("tr"), "").appendChild(marker2).appendChild(td2));

        return doc.appendChild(table).outerHtml();
    }

    public static String encodeParam(String str) {
        return URLEncoder.encode(str, StandardCharsets.UTF_8);
    }

    // https://stackoverflow.com/a/296752
    public static String join(Iterable<?> elements, CharSequence separator) {
        StringBuilder buf = new StringBuilder();

        if (elements != null) {
            if (separator == null) {
                separator = " ";
            }

            for (Object o : elements) {
                if (buf.length() > 0) {
                    buf.append(separator);
                }

                buf.append(o);
            }
        }

        return buf.toString();
    }
}
