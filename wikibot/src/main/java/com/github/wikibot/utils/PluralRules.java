package com.github.wikibot.utils;

import com.github.plural4j.Plural;

// https://github.com/plural4j/plural4j
// https://localization-guide.readthedocs.io/en/latest/l10n/pluralforms.html

public class PluralRules {
	public static final Plural.Rule POLISH = new Plural.Rule(3) {
		@Override
		public int getPluralWordFormIdx(int n) {
			return (n == 1 ? 0 : n % 10 >= 2 && n % 10 <= 4 && (n % 100 < 10 || n % 100 >= 20) ? 1 : 2);
		}
	};
}
