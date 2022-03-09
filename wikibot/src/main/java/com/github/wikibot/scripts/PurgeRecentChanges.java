package com.github.wikibot.scripts;

public final class PurgeRecentChanges {
    /*public static final boolean ENABLE_BOTS = true;
    public static final boolean FORCE_MASSPURGE = false;
    public static final boolean FORCE_MULTIFETCHING = true;
    public static final boolean ALLOW_CUSTOMFETCH = true;
    public static final int DIFF_BATCH_SIZE = 50;
    public static final int PURGE_BATCH_SIZE = 100;

    private static final String location = "./data/scripts/PurgeRecentChanges/";

    public static int rejected = 0;

    public static List<String> getDiffs(Wikibot wb, List<Long> revids) throws IOException {
        List<String> list = new ArrayList<>(revids.size());
        String[] revidsStrings = revids.stream().map(Objects::toString).toArray(String[]::new);
        Map<String, String> request = new HashMap<>();
        request.put("prop", "revisions");
        request.put("rvdiffto", "prev");


        request.put("revids", wb.constructTitleString(revidsStrings, DIFF_BATCH_SIZE));

        StringBuilder sb_url = new StringBuilder(wb.getQuery());
        sb_url.append("&format=xml");
        sb_url.append("&prop=revisions");
        sb_url.append("&rvdiffto=prev");
        sb_url.append("&revids=");

        String url = sb_url.toString();
        int size = revids.size();
        List<Long> notcached = new ArrayList<>(size);

        for (int i = 0; i*DIFF_BATCH_SIZE < size; i++) {
            StringBuilder sb = new StringBuilder(DIFF_BATCH_SIZE*12);
            int limit = Math.min(DIFF_BATCH_SIZE*(i+1), size);

            for (int j = i*DIFF_BATCH_SIZE; j < limit; j++) {
                sb.append(Long.toString(revids.get(j)) + ((j == limit - 1) ? "": "|"));
            }

            String fetched = wb.fetch(url + URLEncoder.encode(sb.toString(), "UTF-8"), "getDiffs");
            fetched = wb.decode(fetched);

            if (fetched.contains("<error "))
                throw new UnsupportedOperationException("Error en lectura de diffs");

            for (int c = fetched.indexOf("<page "); c != -1; c = fetched.indexOf("<page ", ++c)) {
                String title = wb.parseAttribute(fetched, "title", c);
                Long revid = Long.parseLong(wb.parseAttribute(fetched, "revid", c));

                int a = fetched.indexOf("<diff ", c);
                int b = fetched.indexOf("</rev>", a);
                String diff = fetched.substring(a, b);

                if (diff.contains("notcached=\"\""))
                    notcached.add(revid);
                else if (diff.contains("<td class=\"diff-addedline\"><div>''") || diff.contains("<td class=\"diff-addedline\"><div>== ")) {
                    list.add(title);
                }
            }
        }

        rejected = notcached.size();

        if (rejected > 0) {
            System.out.println(rejected + " revisiones rechazadas (notcached)");

            for (Long revid : notcached) {
                String fetched = wb.fetch(url + Long.toString(revid), "getDiffs");
                fetched = wb.decode(fetched);
                String title = wb.parseAttribute(fetched, "title", 0);

                if (fetched.contains("<td class=\"diff-addedline\"><div>''") || fetched.contains("<td class=\"diff-addedline\"><div>== ")) {
                    list.add(title);
                }
            }
        }

        return list;
    }

    public static List<String> addCustom(List<String> logs, int count) {
        List<String> list = new ArrayList<>();

        list.add("Wikisłownik:Strona główna");

        for (String page : list) {
            logs.add(++count + ". " + page + " (CUSTOM)");
        }

        return list;
    }

    public static void main(String[] args) {
        File f_lastdate = new File(location + "last_date.ser");
        String lasttimestamp = "";

        if (!f_lastdate.exists()) {
            File f_pickdate = new File(location + "pick_date.txt");

            if (f_pickdate.exists()) {
                try {
                    BufferedReader br = new BufferedReader(new FileReader(f_pickdate));
                    String line = br.readLine();
                    br.close();

                    if (!line.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$")) {
                        System.out.println("Formato de fecha incorrecto. Introducido: " + line + ", esperado: yyyy-mm-ddThh:mm:ssZ");
                        return;
                    }

                    System.out.println("Fecha extraída del archivo \"pick_date.txt\": " + (lasttimestamp = line));
                } catch (IOException e) {
                    e.printStackTrace();
                }
            } else {
                lasttimestamp = Wikibot.getWikiTimestamp();
                Misc.serialize(lasttimestamp, f_lastdate);
                return;
            }
        } else {
            lasttimestamp = Misc.deserialize(f_lastdate);
        }

        PLWikt wiki = new PLWikt();
        String endtimestamp = Wikibot.getWikiTimestamp();

        try {
            Login.login(wiki, false);

            int rcoptions = Wikibot.HIDE_REDIRECT;

            if (ENABLE_BOTS) {
                rcoptions |= Wikibot.HIDE_BOT;
            }

            Revision[] revs = wiki.recentChanges(lasttimestamp, endtimestamp, rcoptions, 11, true, 0);

            int revcount = revs.length;
            int newcount = 0;
            int editcount = 0;
            int count = 0;

            List<String> allrevids = new ArrayList<>(revcount);
            List<String> al_logs = new ArrayList<>(revcount);
            List<Long> edited_diffs = new ArrayList<>(revcount);

            long start1 = System.currentTimeMillis();

            for (int cont = 0; cont < revs.length; cont++) {
                Revision rev = revs[cont];
                String page = rev.getPage();
                if (rev.isNew()) {
                    allrevids.add(page);
                    al_logs.add(++count + ". " + page + " (NEW)");
                    newcount++;
                } else if (FORCE_MASSPURGE) {
                    allrevids.add(page);
                    al_logs.add(++count + ". " + page + " (EDIT)");
                    editcount++;
                } else if (!FORCE_MULTIFETCHING) {
                    String diff = rev.diff(Wikibot.PREVIOUS_REVISION);
                    if (diff.contains("<td class=\"diff-addedline\"><div>''") || diff.contains("<td class=\"diff-addedline\"><div>== ")) {
                        allrevids.add(page);
                        al_logs.add(++count + ". " + page + " (EDIT)");
                        editcount++;
                    }
                } else {
                    edited_diffs.add(rev.getRevid());
                }
            }

            if (!FORCE_MASSPURGE && FORCE_MULTIFETCHING) {
                List<String> added_pages = getDiffs(wiki, edited_diffs);
                allrevids.addAll(added_pages);
                edited_diffs = null;
                editcount = added_pages.size();

                for (String page : added_pages) {
                    al_logs.add(++count + ". " + page + " (EDIT)");
                }
            }

            int seconds1 = (int) (System.currentTimeMillis() - start1) / 1000;
            int minutes1 = (int) Math.floor(seconds1/60);

            List<String> custom_pages = new ArrayList<>();

            if (ALLOW_CUSTOMFETCH) {
                custom_pages.addAll(addCustom(al_logs, count));
            }

            int custom = custom_pages.size();
            allrevids.addAll(custom_pages);

            revs = null;
            String[] purgelist = allrevids.toArray(new String[allrevids.size()]);
            allrevids = null;

            final int batch = PURGE_BATCH_SIZE;
            long start2 = System.currentTimeMillis();

            for (int i = 0; i*batch < purgelist.length; i++) {
                wiki.purge(true, Arrays.copyOfRange(purgelist, i*batch, Math.min(batch*(i+1), purgelist.length)));
            }

            int seconds2 = (int) (System.currentTimeMillis() - start2) / 1000;
            int minutes2 = (int) Math.floor(seconds2/60);

            String time1 = minutes1 + "m " + ((minutes1 != 0) ? seconds1 % (minutes1*60) : seconds1) + "s";
            String time2 = minutes2 + "m " + ((minutes2 != 0) ? seconds2 % (minutes2*60) : seconds2) + "s";

            System.out.println("Se han purgado " + (newcount+editcount) + " revisiones de un total de " + revcount + " (" + newcount + " páginas nuevas, " + editcount + " de " + (revcount-newcount) + " ediciones)");

            if (ALLOW_CUSTOMFETCH) {
                System.out.println("Purgas personalizadas: " + custom + ", nuevo total: " + (newcount+editcount+custom));
            }

            System.out.println("Bots incluidos: " +	(ENABLE_BOTS ? "sí" : "no") +
                (FORCE_MASSPURGE ? ", PURGA COMPLETA" : (", escaneo rápido: " + (FORCE_MULTIFETCHING ? "sí" : "no"))));
            System.out.println("Tiempo transcurrido: " + time1 + " (diffs), " + time2 + " (purge)");
            System.out.println("Media: " + purgelist.length + " páginas / " + seconds2 + " s = " + ((float) purgelist.length/seconds2));

            if (rejected != 0) {
                System.out.println("Ediciones revisadas (notcached): " + rejected);
            }

            Misc.serialize(endtimestamp, f_lastdate);
            PrintWriter pw = new PrintWriter(new File(location + "logs.txt"));

            pw.println("Inicio: " + lasttimestamp + ", fin: " + endtimestamp);
            pw.println("Revisiones: " + revcount + ", purgadas: " + (newcount + editcount) + " (" + newcount + " páginas nuevas, " + editcount + " de " + (revcount-newcount) + " ediciones)");

            if (ALLOW_CUSTOMFETCH) {
                pw.println("Purgas personalizadas: " + custom + ", nuevo total: " + (newcount+editcount+custom));
            }

            pw.println("Bots incluidos: " +	(ENABLE_BOTS ? "sí" : "no") +
                (FORCE_MASSPURGE ? ", PURGA COMPLETA" : (", escaneo rápido: " + (FORCE_MULTIFETCHING ? "sí" : "no"))));
            pw.println("Tiempo total empleado: " + time1 + " (diffs), " + time2 + " (purga)");
            pw.println("Ediciones revisadas (notcached): " + rejected);
            pw.println("");

            for (String row : al_logs) {
                pw.println(row);
            }

            pw.close();

            System.out.println("Logs actualizados");
        } catch (FailedLoginException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            wiki.logout();
        }
    }*/
}
