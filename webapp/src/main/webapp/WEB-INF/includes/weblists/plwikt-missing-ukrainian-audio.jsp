<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.functions" prefix="fn" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="title" value="Ukraińskie hasła bez nagrania wymowy" />
<c:set var="defaultLimit" value="500" />
<c:set var="limit" value="${not empty param.limit ? utils:max(param.limit, 0) : defaultLimit}" />
<c:set var="offset" value="${not empty param.offset ? utils:max(param.offset, 0) : 0}" />
<c:set var="columnThreshold" value="25" />
<c:set var="paginatorLimits" value="100,250,500,1000,2000,5000" />

<t:template title="${title}" firstHeading="${title}">
    <jsp:attribute name="contentSub">
        <a href="${pageContext.request.contextPath}/weblists">Powrót do indeksu</a>
    </jsp:attribute>
    <jsp:body>
        <p>
            Spis
            <a href="https://pl.wiktionary.org/wiki/S%C5%82ownik_j%C4%99zyka_ukrai%C5%84skiego" target="_blank">ukraińskich haseł</a>
            bez
            <a href="https://commons.wikimedia.org/wiki/Category:Ukrainian_pronunciation" target="_blank">nagrania wymowy</a>.
            Wyniki są również dostępne w formie
            <a href="${originalContext}/raw" target="_blank">listy nieprzetworzonej</a>
            (<a href="${originalContext}/download" target="_blank">pobierz</a> jako plik .txt).
        </p>
        <div id="plwikt-missing-ukrainian-audio-content">
            <c:choose>
                <c:when test="${not empty results}">
                    <p id="plwikinews-missing-plwiki-backlinks-summary">
                        Lista zawiera <strong id="plwikt-missing-ukrainian-audio-total">${total}</strong>
                        ${utils:makePluralPL(total, 'wynik', 'wyniki', 'wyników')}.
                        Poniżej wyświetlono co najwyżej <strong id="plwikt-missing-ukrainian-audio-limit">${limit}</strong>
                        ${utils:makePluralPL(limit, 'wynik', 'wyniki', 'wyników')}
                        w zakresie od <strong id="plwikt-missing-ukrainian-audio-start">${offset + 1}</strong>
                        do <strong id="plwikt-missing-ukrainian-audio-end">${utils:min(offset + limit, total)}</strong>.
                    </p>
                    <t:paginator limit="${limit}" offset="${offset}" hasNext="${total gt offset + limit}"
                        limits="${paginatorLimits}" />
                    <ol id="plwikt-missing-ukrainian-audio-results" start="${offset + 1}"
                        <c:if test="${fn:length(results) gt columnThreshold}">class="column-list"</c:if>>
                        <c:forEach var="item" items="${results}">
                            <li>
                                <t:linker hrefPattern="https://pl.wiktionary.org/$1#uk" target="${item}" sectionName="ukraiński" />
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
