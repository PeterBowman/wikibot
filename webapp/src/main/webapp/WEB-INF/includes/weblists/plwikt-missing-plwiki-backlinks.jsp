<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="title" value="Brak Wikisłownika w Wikipedii " />
<c:set var="defaultLimit" value="500" />

<c:set var="limit" value="${not empty param.limit ? utils:max(param.limit, 0) : defaultLimit}" />
<c:set var="offset" value="${not empty param.offset ? utils:max(param.offset, 0) : 0}" />

<c:set var="columnThreshold" value="25" />
<c:set var="paginatorLimits" value="100,250,500,1000,2000,5000" />

<t:template title="${title}" firstHeading="${title}" enableJS="true">
    <jsp:attribute name="head">
        <link href="${pageContext.request.contextPath}/styles/tipsy.css" type="text/css" rel="stylesheet">
        <script src="${pageContext.request.contextPath}/scripts/tipsy.js"></script>
        <script src="${pageContext.request.contextPath}/scripts/definition-popups.js"></script>
        <script>
            window.plwiktMissingPlwikiBacklinks = {
                defaultLimit: ${defaultLimit},
                limit: ${limit},
                offset: ${offset},
                columnThreshold: ${columnThreshold}
            };
        </script>
        <script src="${pageContext.request.contextPath}/scripts/plwikt-missing-plwiki-backlinks.js"></script>
    </jsp:attribute>
    <jsp:attribute name="contentSub">
        <a href="${pageContext.request.contextPath}/weblists">Powrót do indeksu</a>
    </jsp:attribute>
    <jsp:body>
        <p>
            Spis polskich haseł w Wikisłowniku transkludujących szablon
            {{<a href="https://pl.wiktionary.org/wiki/Szablon:wikipedia" target="_blank">wikipedia</a>}},
            których docelowy artykuł (po rozwiązaniu przekierowań) w Wikipedii bądź nie istnieje, bądź nie linkuje
            z powrotem do tego samego hasła w WS.
            Najechanie kursorem na dowolny link prowadzący do Wikisłownika poskutkuje wyświetleniem okienka z
            wyciągiem pola znaczeń odpowiedniego hasła tamże.
        </p>
        <div id="plwikt-missing-plwiki-backlinks-content">
            <table id="plwikt-missing-plwiki-backlinks-stats" class="wikitable mw-statistics floatright">
                <caption>Statystyka</caption>
                <tr>
                    <th>transkluzji {{wikipedia}} (w sumie)</th>
                    <td id="plwikt-missing-plwiki-backlinks-stats-totalTemplateTransclusions" class="mw-statistics-numbers">
                        ${stats['totalTemplateTransclusions']}
                    </td>
                </tr>
                <tr>
                    <th>transkluzji {{wikipedia}} (tylko hasła polskie)</th>
                    <td id="plwikt-missing-plwiki-backlinks-stats-targetedTemplateTransclusions" class="mw-statistics-numbers">
                        ${stats['targetedTemplateTransclusions']}
                    </td>
                </tr>
                <tr>
                    <th>jednakowych artykułów docelowych w Wikipedii</th>
                    <td id="plwikt-missing-plwiki-backlinks-stats-targetedArticles" class="mw-statistics-numbers">
                        ${stats['targetedArticles']}
                    </td>
                </tr>
                <tr>
                    <th>istniejących artykułów w Wikipedii</th>
                    <td id="plwikt-missing-plwiki-backlinks-stats-foundArticles" class="mw-statistics-numbers">
                        ${stats['foundArticles']}
                    </td>
                </tr>
                <tr>
                    <th>przekierowań w Wikipedii</th>
                    <td id="plwikt-missing-plwiki-backlinks-stats-foundRedirects" class="mw-statistics-numbers">
                        ${stats['foundRedirects']}
                    </td>
                </tr>
                <tr>
                    <th>jednakowych haseł w Wikisłowniku</th>
                    <td id="plwikt-missing-plwiki-backlinks-stats-filteredTitles" class="mw-statistics-numbers">
                        ${stats['filteredTitles']}
                    </td>
                </tr>
            </table>
            <p>
                Ostatnia aktualizacja: <span id="plwikt-missing-plwiki-backlinks-timestamp">${timestamp}</span>.
                <c:if test="${total ne 0}">
                    Lista zawiera <strong id="plwikt-missing-plwiki-backlinks-total">${total}</strong>
                    ${utils:makePluralPL(total, 'wynik', 'wyniki', 'wyników')}.
                </c:if>
            </p>
            <c:choose>
                <c:when test="${not empty results}">
                    <p id="plwikt-missing-plwiki-backlinks-summary">
                        Poniżej wyświetlono co najwyżej <strong id="plwikt-missing-plwiki-backlinks-limit">${limit}</strong>
                        ${utils:makePluralPL(limit, 'wynik', 'wyniki', 'wyników')}
                        w zakresie od <strong id="plwikt-missing-plwiki-backlinks-start">${offset + 1}</strong>
                        do <strong id="plwikt-missing-plwiki-backlinks-end">${utils:min(offset + limit, total)}</strong>.
                    </p>
                    <p>
                        Obsługiwane szablony w Wikipedii:
                        <span id="plwikt-missing-plwiki-backlinks-templates">
	                        <c:forTokens var="template" items="${fn:join(templates, ',')}" delims="," varStatus="status">
	                            <t:linker hrefPattern="https://pl.wikipedia.org/$1" target="Szablon:${template}" display="${template}" />
	                            <c:out value="${status.last ? '.' : ', '}" />
	                        </c:forTokens>
                        </span>
                    </p>
                    <div style="clear: right;"></div>
                    <t:paginator limit="${limit}" offset="${offset}" hasNext="${total gt offset + limit}"
                        limits="${paginatorLimits}" />
                    <ol id="plwikt-missing-plwiki-backlinks-results" start="${offset + 1}"
                        <c:if test="${fn:length(results) gt columnThreshold}">class="column-list"</c:if>>
                        <c:forEach var="item" items="${results}">
                            <li>
                                <t:linker hrefPattern="https://pl.wiktionary.org/$1#pl" target="${item.plwiktTitle}" sectionName="polski" />
                                <c:if test="${not empty item.plwikiRedir}">
                                    ↔
                                    <t:linker hrefPattern="https://pl.wikipedia.org/$1" target="${item.plwikiRedir}"
                                        display="w:${item.plwikiRedir}" testRedirection="${true}" />
                                </c:if>
                                ↔
                                <t:linker hrefPattern="https://pl.wikipedia.org/$1" target="${item.plwikiTitle}"
                                    display="w:${item.plwikiTitle}" testMissingPage="${item.missingPlwikiArticle}" />
                                <c:if test="${not empty item.plwiktBacklinks}">
                                    • <i>linkuje do:</i> 
                                    <c:forEach var="backlink" items="${item.plwiktBacklinks}">
                                        <t:linker hrefPattern="https://pl.wiktionary.org/$1#pl" target="${backlink}" sectionName="polski" />
                                    </c:forEach>
                                </c:if>
                            </li>
                        </c:forEach>
                    </ol>
                    <t:paginator limit="${limit}" offset="${offset}" hasNext="${total gt offset + limit}"
                        limits="${paginatorLimits}" />
                </c:when>
                <c:otherwise>
                    <p>Brak wyników.</p>
                </c:otherwise>
            </c:choose>
        </div>
    </jsp:body>
</t:template>
