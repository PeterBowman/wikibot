package com.github.wikibot.parsing;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;

import com.github.wikibot.dumps.XMLRevision;
import com.github.wikibot.main.Wikibot;
import com.github.wikibot.utils.PageContainer;

public class Page extends AbstractPage<Section> implements Serializable {
    private static final long serialVersionUID = 4466484557279321889L;

    public Page(String title, String text) {
        super(title);

        if (text != null && !text.isEmpty()) {
            extractSections(text);
        }
    }

    public static Page wrap(PageContainer page) {
        return new Page(page.title(), page.text());
    }

    public static Page wrap(XMLRevision xml) {
        return new Page(xml.getTitle(), xml.getText());
    }

    public static Page store(String title, String text) {
        return new Page(title, text);
    }

    public static Page create(String title) {
        Page page = new Page(title, null);
        page.setTrailingNewlines(1);
        return page;
    }

    protected final void extractSections(String text) {
        super.extractSections(text, Section::new);
    }

    /*@Override
    public Section getSection(String header) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Section addSection(String header) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean addSection(Section section) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean removeSection(String header) {
        // TODO Auto-generated method stub
        return false;
    }

    @Override
    public boolean hasSection(String header) {
        // TODO Auto-generated method stub
        return false;
    }*/

    @SuppressWarnings("unused")
    public static void main(String[] args) throws IOException {
        Wikibot wb = Wikibot.newSession("pl.wiktionary.org");
        String text = wb.getPageText(List.of("Wikisłownik:Zasady tworzenia haseł")).get(0);
        Page page = Page.store("Wikisłownik:Zasady tworzenia haseł", text);
        System.out.println("");
    }
}
