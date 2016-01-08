package com.github.wikibot.scripts.misc;

import java.util.HashMap;


public class RussianAdjectivesRegex {
	private String line;
	private static int index = 0;
	
	private String baseform;
	private String comparative;
	private String superlative;
	
	private HashMap<String, String> shortforms;
	private boolean no_sf = false;
	private boolean no_grade = false;
	private boolean no_sup = false;
	
	public RussianAdjectivesRegex(String line) {
		this.line = line;
	}
	
	public String run() {
		obtainBaseForm();
		obtainShortForms();
		obtainGrades();
		
		return buildTemplate();
	}
	
	public String getForm() {
		//obtainBaseForm();
		//obtainShortForms();
		obtainGrades();
		
		//return baseform;
		return "dfd";//shortforms.toString();
	}
	
	private String buildTemplate() {
		StringBuilder sb = new StringBuilder();
		
		sb.append("{{odmiana-przymiotnik-rosyjski|" + baseform);
		
		if (no_grade) {
			sb.append("|brak");
		} else {
			sb.append((comparative == null) ? "" : "|" + comparative);
			
			if (!no_sup) {
				sb.append((superlative == null) ? "" : "|" + superlative);
			}
		}		

		if (shortforms != null) {
			sb.append("\n|krótka forma m lp = " + shortforms.get("lp_m"));
			sb.append("\n|krótka forma f lp = " + shortforms.get("lp_f"));
			sb.append("\n|krótka forma n lp = " + shortforms.get("lp_n"));
			sb.append("\n|krótka forma lm   = " + shortforms.get("lm"));
			sb.append("\n}}");
		} else if (no_sf) {
			sb.append("|krótka forma=nie}}");
		} else {
			sb.append("}}");
		}
		
		return sb.toString();
	}
	
	private void obtainBaseForm() {		
		index = line.indexOf("{{lp}}");
		int end = line.indexOf(",", index);

		String lemma = line.substring(line.indexOf(" ", index) + 1, line.indexOf("|", index));
		String ending = line.substring(line.indexOf("|", index) + 1, end);
		
		baseform = lemma + ending;
	}
	
	private void obtainShortForms() {
		int aux = line.indexOf("krótka forma", index);
		
		if (aux == -1) {
			shortforms = null;
			return;
		} else {
			index = aux;
			shortforms = new HashMap<>();
		}
		
		//System.out.println(index + " " + line.indexOf("krótka forma'' –", index));
		
		if (line.indexOf("krótka forma'' –", index) == index) {
			shortforms = null;
			no_sf = true;
			return;
		}
		
		index = line.indexOf(";", index);
		String lp = line.substring(line.indexOf("{{lp}} ", aux) + 7, index);
		
		aux = line.indexOf(";", index + 1);
		//System.out.println(index + " " + aux + " " + lp);
		String lm = line.substring(line.indexOf("{{lm}} ", index) + 7, (aux == -1) ? line.length() : (index = aux));
		//System.out.println(index + " " + aux + " " + lm);
		
		String[] formnames = {"lp_m", "lp_f", "lp_n"};
		
		if (lp.matches("[\u0000-\uFFFF]+, [\u0000-\uFFFF]+, [\u0000-\uFFFF]+")) {
			int i = 0;
			for (String form : lp.split(", ")) {
				form = form.replaceAll(" ''i'' ", " i ");
				shortforms.put(formnames[i++], form);
			}
		}
		
		String lp_m = shortforms.get("lp_m");
		String lp_f = shortforms.get("lp_f");
		
		if (lp_f.contains("|")) {
			int ind = lp_f.indexOf("|");
			String lemma = lp_f.substring(0, ind);
			shortforms.put("lp_f", lemma + lp_f.substring(ind+1));
			shortforms.put("lp_n", lemma + shortforms.get("lp_n").substring(1));
			shortforms.put("lm", lemma + lm.substring(1));
		} else if (lp_m.contains("|")) {
			int ind = lp_m.indexOf("|");
			String lemma = lp_m.substring(0, ind);
			shortforms.put("lp_m", lemma + lp_m.substring(ind+1));
			shortforms.put("lp_f", lemma + lp_f.substring(1));
			shortforms.put("lp_n", lemma + shortforms.get("lp_n").substring(1));
			shortforms.put("lm", lemma + lm.substring(1));
		} else {
			shortforms.put("lm", lm.replaceAll(" ''i'' ", " i "));
		}
	}
	
	private void obtainGrades() {
		index = line.indexOf("{{stopn|", index);
		//System.out.println(index);
		
		if (index == -1) {
			comparative = superlative = null;
			return;
		}
		
		String contents = line.substring(index + 8, line.indexOf("}}", index));
		//System.out.println(contents);
		
		comparative = contents.substring(0, contents.indexOf("|"));
		superlative = contents.substring(contents.indexOf("|") + 1);
				
		if (comparative.equals("–") || comparative.equals("—")) {
			no_grade = true;
		}
		
		if (superlative.equals("–") || superlative.equals("—")) {
			no_sup = true;
		}
		
		//System.out.println(comparative + " " + superlative);
	}
	
	public static void main(String[] args) {
		RussianAdjectivesRegex test = new RussianAdjectivesRegex("17 возмутительный || : (1.1) {{lp}} возмути́тельн|ый, ~ая, ~ое; {{lm}} ~ые; ''krótka forma'' {{lp}} возмути́тел|ен, ~ьна, ~ьно; {{lm}} ~ьны");
		
		System.out.println(test.run());
	}
}
