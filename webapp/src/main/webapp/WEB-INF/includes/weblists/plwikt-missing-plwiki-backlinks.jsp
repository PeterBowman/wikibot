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
        <script>
            window.plwiktMissingPlwikiBacklinks = {
                defaultLimit: ${defaultLimit},
                limit: ${limit},
                offset: ${offset},
                columnThreshold: ${columnThreshold}
            };
        </script>
        <script src="scripts/plwikt-missing-plwiki-backlinks.js"></script>
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
        </p>
        <div id="plwikt-missing-plwiki-backlinks-content">
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
                    <t:paginator limit="${limit}" offset="${offset}" hasNext="${total gt offset + limit}"
                        limits="${paginatorLimits}" />
                    <ol id="plwikt-missing-plwiki-backlinks-results" start="${offset + 1}"
                        <c:if test="${fn:length(results) gt columnThreshold}">class="column-list"</c:if>>
                        <c:forEach var="item" items="${results}">
                            <li>
                                <t:linker hrefPattern="https://pl.wiktionary.org/$1#pl" target="${item.plwiktTitle}" />
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
                                        <t:linker hrefPattern="https://pl.wiktionary.org/$1#pl" target="${backlink}" />
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
