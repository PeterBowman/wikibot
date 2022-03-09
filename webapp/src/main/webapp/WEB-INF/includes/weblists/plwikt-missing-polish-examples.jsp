<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="title" value="Brak polskich przykładów" />
<c:set var="defaultLimit" value="500" />

<c:set var="limit" value="${not empty param.limit ? utils:max(param.limit, 0) : defaultLimit}" />
<c:set var="offset" value="${not empty param.offset ? utils:max(param.offset, 0) : 0}" />

<c:set var="columnThreshold" value="25" />
<c:set var="paginatorLimits" value="100,250,500,1000,2000,5000" />

<t:template title="${title}" firstHeading="${title}" enableJS="true">
    <jsp:attribute name="head">
        <link href="${pageContext.request.contextPath}/styles/tipsy.css" type="text/css" rel="stylesheet">
        <script src="${pageContext.request.contextPath}/scripts/tipsy.js"></script>
        <script>
            window.plwiktMissingPolishExamples = {
                limit: ${limit},
                offset: ${offset},
                columnThreshold: ${columnThreshold}
            };
        </script>
        <script src="${pageContext.request.contextPath}/scripts/plwikt-missing-polish-examples.js"></script>
    </jsp:attribute>
    <jsp:attribute name="contentSub">
        <a href="${pageContext.request.contextPath}/weblists">Powrót do indeksu</a>
    </jsp:attribute>
    <jsp:body>
        <p>
            Spis polskich haseł w Wikisłowniku z niewypełnionym polem <strong>przykłady</strong> linkujących do
            istniejących przykładów w innych hasłach, które można prosto przekleić za pomocą formularza edycji (tzw. 
            <a href="https://pl.wiktionary.org/wiki/Wikis%C5%82ownik:Projekt_formularza" target="_blank"> skrypt ToStera</a>,
            gadżet dostępny w Preferencjach).
            W nawiasach podano liczbę haseł linkujących z podziałem na sekcje. Wyszczególniona lista zostanie wyświetlona
            po najechaniu kursorem na dowolny wynik.
        </p>
        <div id="plwikt-missing-polish-examples-content">
            <p>
                Ostatnia aktualizacja: <span id="plwikt-missing-polish-examples-bottimestamp">${bottimestamp}</span>
                (na podstawie zrzutu z bazy danych z dnia
                <span id="plwikt-missing-polish-examples-dumptimestamp">${dumptimestamp}</span>).
                <c:if test="${total ne 0}">
                    Lista zawiera <strong id="plwikt-missing-polish-examples-total">${total}</strong>
                    ${utils:makePluralPL(total, 'wynik', 'wyniki', 'wyników')}.
                </c:if>
            </p>
            <c:choose>
                <c:when test="${not empty results}">
                    <p id="plwikt-missing-polish-examples-summary">
                        Poniżej wyświetlono co najwyżej <strong id="plwikt-missing-polish-examples-limit">${limit}</strong>
                        ${utils:makePluralPL(limit, 'wynik', 'wyniki', 'wyników')}
                        w zakresie od <strong id="plwikt-missing-polish-examples-start">${offset + 1}</strong>
                        do <strong id="plwikt-missing-polish-examples-end">${utils:min(offset + limit, total)}</strong>.
                    </p>
                    <t:paginator limit="${limit}" offset="${offset}" hasNext="${total gt offset + limit}"
                        limits="${paginatorLimits}" />
                    <ol id="plwikt-missing-polish-examples-results" start="${offset + 1}"
                        <c:if test="${fn:length(results) gt columnThreshold}">class="column-list"</c:if>>
                        <c:forEach var="item" items="${results}">
                            <li>
                                <t:linker hrefPattern="https://pl.wiktionary.org/$1#pl" target="${item.title}" sectionName="polski" />
                                ${' '} <%-- enforce space between elements --%>
                                <span class="plwikt-missing-polish-examples-item"
                                    data-titles="${utils:join(item.backlinkTitles, '|')}"
                                    data-sections="${utils:join(item.backlinkSections, '|')}"
                                 >(${item.backlinks})</span>
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
