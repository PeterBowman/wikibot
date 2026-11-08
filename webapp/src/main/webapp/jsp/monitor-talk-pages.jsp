<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.functions" prefix="fn" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="title" value="Monitorowanie stron dyskusji w Wikisłowniku" />
<c:set var="defaultLimit" value="500" />

<c:set var="limit" value="${not empty param.limit ? utils:max(param.limit, 0) : defaultLimit}" />
<c:set var="offset" value="${not empty param.offset ? utils:max(param.offset, 0) : 0}" />

<c:set var="columnThreshold" value="25" />
<c:set var="paginatorLimits" value="100,250,500,1000,2000,5000" />

<t:template title="${title}" firstHeading="${title}" enableJS="true">
    <jsp:attribute name="head">
        <script>
            window.monitorTalkPages = {
                defaultLimit: ${defaultLimit},
                limit: ${limit},
                offset: ${offset},
                currentRedirsOn: ${param.onlyredirs eq 1},
                currentRedlinksOn: ${param.onlymissing eq 1},
                currentDisambigsOn: ${param.onlydisambigs eq 1},
                columnThreshold: ${columnThreshold}
            };
        </script>
        <script src="${pageContext.request.contextPath}/scripts/monitor-talk-pages.js"></script>
    </jsp:attribute>
    <jsp:body>
        <p>
            Spis polskich haseł w Wikisłowniku transkludujących szablon
            {{<a href="https://pl.wiktionary.org/wiki/Szablon:wikipedia" target="_blank">wikipedia</a>}},
            których docelowy artykuł (po rozwiązaniu przekierowań) w Wikipedii bądź nie istnieje, bądź nie linkuje
            z powrotem do tego samego hasła w WS. Najechanie kursorem na dowolny link poskutkuje wyświetleniem okienka
            z wyciągiem pola znaczeń w Wikisłowniku bądż wprowadzenia do artykułu w Wikipedii.
        </p>
        <p>
            Ostatnia aktualizacja: <span id="monitor-talk-pages-timestamp">${timestamp}</span>.
            <c:if test="${total ne 0}">
                Lista zawiera <strong id="monitor-talk-pages-total">${total}</strong>
                ${utils:makePluralPL(total, 'wynik', 'wyniki', 'wyników')}.
            </c:if>
        </p>
        <table id="monitor-talk-pages-stats" class="wikitable mw-statistics floatright" style="margin-left: 1em;">
            <caption>Statystyka</caption>
            <tr>
                <th>wszystkich stron dyskusji</th>
                <td id="monitor-talk-pages-stats-pages" class="mw-statistics-numbers">
                    ${stats.pages}
                </td>
            </tr>
            <tr>
                <th>…w tym osieroconych</th>
                <td id="monitor-talk-pages-stats-orphaned" class="mw-statistics-numbers">
                    ${stats.orphaned}
                </td>
            </tr>
            <tr>
                <th>wszystkich wątków</th>
                <td id="monitor-talk-pages-stats-threads" class="mw-statistics-numbers">
                    ${stats.threads}
                </td>
            </tr>
            <tr>
                <th>…w tym nowych</th>
                <td id="monitor-talk-pages-stats-threads-new" class="mw-statistics-numbers">
                    ${stats['new']}
                </td>
            </tr>
            <tr>
                <th>…w tym aktywnych</th>
                <td id="monitor-talk-pages-stats-threads-ongoing" class="mw-statistics-numbers">
                    ${stats.ongoing}
                </td>
            </tr>
            <tr>
                <th>…w tym zaległych</th>
                <td id="monitor-talk-pages-stats-threads-stale" class="mw-statistics-numbers">
                    ${stats.stale}
                </td>
            </tr>
            <tr>
                <th>…w tym załatwionych</th>
                <td id="monitor-talk-pages-stats-threads-resolved" class="mw-statistics-numbers">
                    ${stats.resolved}
                </td>
            </tr>
        </table>
        <form action="${pageContext.request.contextPath}/monitor-talk-pages" method="GET" id="mtp-form">
            <fieldset>
                <legend>Monitorowanie stron dyskusji</legend>
                <p class="mw-input-with-label">
                    Przestrzeń nazw:
                    <select name="ns" id="ns">
                        <option value="" selected>pokaż wszystko</option>
                        <option value="1">Dyskusja</option>
                        <option value="3">Dyskusja wikisłownikarza</option>
                        <option value="5">Wikidyskusja</option>
                        <option value="7">Dyskusja pliku</option>
                        <option value="9">Dyskusja MediaWiki</option>
                        <option value="11">Dyskusja szablonu</option>
                        <option value="13">Dyskusja pomocy</option>
                        <option value="15">Dyskusja kategorii</option>
                        <option value="101">Dyskusja aneksu</option>
                        <option value="103">Dyskusja indeksu</option>
                        <option value="105">Dyskusja portalu</option>
                        <option value="829">Dyskusja modułu</option>
                    </select>
                </p>
                <p class="mw-input-with-label" id="monitor-talk-pages-language" style="display: none;">
                    <label for="lang">Powiązane hasło zawiera tę sekcję językową:</label>
                    <input id="lang" name="lang" size="20" value="">
                </p>
                <p class="mw-input-with-label">
                    Wyświetl:
                    <input type="radio" id="state-all" name="state" value="all" checked>
                    <label for="state-all">wszystkie</label>
                    <input type="radio" id="state-new" name="state" value="new">
                    <label for="state-new">nowe</label>
                    <input type="radio" id="state-ongoing" name="state" value="ongoing">
                    <label for="state-ongoing">aktywne</label>
                    <input type="radio" id="state-stale" name="state" value="stale">
                    <label for="state-stale">zaległe</label>
                    <input type="radio" id="state-resolved" name="state" value="resolved">
                    <label for="state-resolved">załatwione</label>
                </p>
                <p class="mw-input-with-label">
                    Sortuj według:
                    <input type="radio" id="sort-date" name="sort" value="date" checked>
                    <label for="sort-date">daty ostatniego wpisu</label>
                    <input type="radio" id="sort-title" name="sort" value="title">
                    <label for="sort-title">nazwy strony</label>
                </p>
                <p class="mw-input-with-label">
                    W kolejności:
                    <input type="radio" id="order-asc" name="order" value="asc">
                    <label for="order-asc">rosnącej</label>
                    <input type="radio" id="order-desc" name="order" value="desc" checked>
                    <label for="order-desc">malejącej</label>
                </p>
                <p class="mw-input-with-label">
                    <label for="orphaned">Ogranicz do osieroconych stron dyskusji</label>
                    <input type="checkbox" id="orphaned" name="orphaned" value="false">
                </p>
                <p class="mw-input-with-label" id="monitor-talk-pages-stale-days">
                    <label for="stale-days">Po ilu dniach uznać wątek za zaległy:</label>
                    <input id="stale-days" name="threshold" size="5" value="${stats.threshold}">
                </p>
                <input type="submit" value="Wyślij zapytanie" >
            </fieldset>
        </form>
        <div id="monitor-talk-pages-content">
            <c:choose>
                <c:when test="${not empty results}">
                    <p id="monitor-talk-pages-summary">
                        Poniżej wyświetlono co najwyżej <strong id="monitor-talk-pages-limit">${limit}</strong>
                        ${utils:makePluralPL(limit, 'wynik', 'wyniki', 'wyników')}
                        w zakresie od <strong id="monitor-talk-pages-start">${offset + 1}</strong>
                        do <strong id="monitor-talk-pages-end">${utils:min(offset + limit, total)}</strong>.
                    </p>
                    <!-- <p id="monitor-talk-pages-filter">
                        Filtruj według wyników w Wikipedii:
                        <a href='<t:replace-param onlyredirs="${param.onlyredirs eq 1 ? 0 : 1}" offset="0" />' class="redirect"
                            style="text-decoration: underline;">przekierowania</a>
                        <span class="monitor-talk-pages-filter-switch">
                            <c:choose>
                                <c:when test="${param.onlyredirs eq 1}"><strong>(włączone)</strong></c:when>
                                <c:otherwise>(wyłączone)</c:otherwise>
                            </c:choose>
                        </span>,
                        <a href='<t:replace-param onlymissing="${param.onlymissing eq 1 ? 0 : 1}" offset="0" />' class="new"
                            style="text-decoration: underline;">brakujące</a>
                        <span class="monitor-talk-pages-filter-switch">
                            <c:choose>
                                <c:when test="${param.onlymissing eq 1}"><strong>(włączone)</strong></c:when>
                                <c:otherwise>(wyłączone)</c:otherwise>
                            </c:choose>
                        </span>,
                        <a href='<t:replace-param onlydisambigs="${param.onlydisambigs eq 1 ? 0 : 1}" offset="0" />' class="disambig"
                            style="text-decoration: underline;">ujednoznacznienia</a>
                        <span class="monitor-talk-pages-filter-switch">
                            <c:choose>
                                <c:when test="${param.onlydisambigs eq 1}"><strong>(włączone)</strong></c:when>
                                <c:otherwise>(wyłączone)</c:otherwise>
                            </c:choose>
                        </span>.
                    </p> -->
                    <div style="clear: right;"></div>
                    <t:paginator limit="${limit}" offset="${offset}" hasNext="${total gt offset + limit}" limits="${paginatorLimits}" />
                    <ol id="monitor-talk-pages-results" start="${offset + 1}">
                        <c:forEach var="item" items="${results}">
                            <li>
                                <t:linker hrefPattern="https://pl.wiktionary.org/$1" target="${item.talkPage}" />
                                <c:if test="${not empty item.threadTitle}">
                                    (<t:linker hrefPattern="https://pl.wiktionary.org/$1"
                                               target="${item.talkPage}#${item.threadTitle}"
                                               display="# ${item.threadTitle}" />)
                                </c:if>
                                <c:if test="${not empty item.lastTimestamp}">
                                    • ${item.lastTimestamp}
                                </c:if>
                            </li>
                        </c:forEach>
                    </ol>
                    <t:paginator limit="${limit}" offset="${offset}" hasNext="${total gt offset + limit}" limits="${paginatorLimits}" />
                </c:when>
                <c:otherwise>
                    <p>Brak wyników.</p>
                </c:otherwise>
            </c:choose>
        </div>
    </jsp:body>
</t:template>
