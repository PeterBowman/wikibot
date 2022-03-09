package com.github.wikibot.parsing;

import com.github.wikibot.parsing.eswikt.Page;
import com.github.wikibot.utils.PageContainer;

public class Editor extends AbstractEditor {

    public Editor(Page page) {
        super(page.getTitle(), page.toString());
    }

    public Editor(PageContainer pc) {
        super(pc.getTitle(), pc.getText());
    }

    @Override
    public void check() {
        // TODO Auto-generated method stub

    }

}
