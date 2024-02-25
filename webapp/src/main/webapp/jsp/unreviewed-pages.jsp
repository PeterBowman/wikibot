<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="title" value="Brak Wikinews w Wikipedii" />
<c:set var="defaultProject" value="pl.wiktionary.org" />
<c:set var="defaultLimit" value="500" />
<c:set var="limit" value="${not empty param.limit ? utils:max(param.limit, 0) : defaultLimit}" />
<c:set var="offset" value="${not empty param.offset ? utils:max(param.offset, 0) : 0}" />
<c:set var="columnThreshold" value="25" />
<c:set var="paginatorLimits" value="100,250,500,1000,2000,5000" />

<t:template title="${title}" firstHeading="${title}" enableJS="true">
    <jsp:attribute name="head">
        <link href="styles/suggestions.css" type="text/css" rel="stylesheet">
        <script src="scripts/suggestions.js"></script>
        <script src="scripts/unreviewed-pages.js"></script>
    </jsp:attribute>
    <jsp:body>
        <p>
            Usprawnienie strony specjalnej „Nieprzejrzane strony” poprzez zniesienie limitu wyników.
            Narzędzie obsługuje wyłącznie przestrzeń główną.
        </p>
        <form action="${pageContext.request.contextPath}/unreviewed-pages" method="GET">
            <fieldset>
                <legend>Lista nieprzejrzanych stron</legend>
                <p class="mw-input-with-label">
                    <label for="project">Projekt:</label>
                    <select id="project" name="project">
                        <option value="plwiktionary" <c:if test="${empty param.project or param.project eq 'plwiktionary'}">selected</c:if>>pl.wiktionary.org</option>
                        <option value="plwiki" <c:if test="${param.project eq 'plwiki'}">selected</c:if>>pl.wikipedia.org</option>
                    </select>
                </p>
                <p class="mw-input-with-label">
                    <label for="category">Kategoria:</label>
                    <input id="category" name="category" size="50">
                </p>
                <p class="mw-input-with-label">
                    <input type="submit" value="Wyślij zapytanie" >
                </p>
            </fieldset>
        </form>
        <c:if test="${not empty results}">
            <p id="unreviewed-pages-summary">
                Lista zawiera <strong id="unreviewed-pages-total">${total}</strong>
                ${utils:makePluralPL(total, 'wynik', 'wyniki', 'wyników')}<c:if test="${not empty param.category}">
                    dla kategorii <t:linker hrefPattern="https://${domain}/$1" target="Kategoria:${param.category}" display="${param.category}" />
                </c:if>.
                Poniżej wyświetlono co najwyżej <strong id="unreviewed-pages-limit">${limit}</strong>
                ${utils:makePluralPL(limit, 'wynik', 'wyniki', 'wyników')}
                w zakresie od <strong id="unreviewed-pages-start">${offset + 1}</strong>
                do <strong id="unreviewed-pages-end">${utils:min(offset + limit, total)}</strong>.
            </p>
            <t:paginator limit="${limit}" offset="${offset}" hasNext="${total gt offset + limit}" limits="${paginatorLimits}" />
            <ol id="unreviewed-pages-results" start="${offset + 1}"
                <c:if test="${fn:length(results) gt columnThreshold}">class="column-list"</c:if>>
                <c:forEach var="item" items="${results}">
                    <li>
                        <t:linker hrefPattern="https://${domain}/$1" target="${item.title}" /> (${item.days}&nbsp;${utils:makePluralPL(item.days, 'dzień', 'dni', 'dni')})
                    </li>
                </c:forEach>
            </ol>
            <t:paginator limit="${limit}" offset="${offset}" hasNext="${total gt offset + limit}" limits="${paginatorLimits}" />
        </c:if>
    </jsp:body>
</t:template>
