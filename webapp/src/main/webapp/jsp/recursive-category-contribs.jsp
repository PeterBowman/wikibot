<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="heading" value="Aktywność w drzewie kategorii" />
<c:set var="trimmedCategory" value="${fn:replace(param.mainCategory, 'Kategoria:', '')}" />

<t:template title="${heading}" firstHeading="${heading}" enableJS="true">
    <jsp:attribute name="head">
        <link href="styles/recursive-category-contribs.css" type="text/css" rel="stylesheet">
        <link href="styles/suggestions.css" type="text/css" rel="stylesheet">
        <script src="scripts/suggestions.js"></script>
        <script src="scripts/recursive-category-contribs.js"></script>
    </jsp:attribute>
    <jsp:body>
        <p>
            Wyświetla najaktywniejszych edytorów (z pominięciem botów) we wskazanym drzewie kategorii.
            Można ograniczyć wyszukiwanie do konkretnego okresu oraz wykluczyć pojedyncze kategorie,
            przy czym gałęzie drzewa znajdujące się za nimi zostaną zignorowane (tj. łącznie z ich własnymi podkategoriami).
            Administratorów wyróżniono tłustą czcionką.
        </p>
        <form action="${pageContext.request.contextPath}/recursive-category-contribs" method="GET" id="rcc-form">
            <fieldset>
                <legend>Wyszukiwanie w drzewie kategorii</legend>
                <p class="mw-input-with-label">
                    <label for="title">Główna kategoria:</label>
                    <input id="main-category" name="mainCategory" size="71" value="${param.mainCategory}">
                </p>
                <p class="mw-input-with-label">
                    (opcjonalnie)
                    <label for="start-date">Początek:</label>
                    <input id="start-date" name="startDate" type="date" value="${param.startDate}">
                    <label for="end-date">Koniec (włącznie):</label>
                    <input id="end-date" name="endDate" type="date" value="${param.endDate}">
                </p>
                <p class="mw-input-with-label">
                    <label for="ignored-categories">Wykluczenia (każda kategoria w osobnym wierszu):</label>
                    <textarea id="ignored-categories" name="ignoredCategories" rows="5" cols="70">${param.ignoredCategories}</textarea>
                </p>
                <p class="mw-input-with-label">
                    <label for="max-depth">Maksymalna głębokość (0: tylko główna kategoria, puste: bez ograniczenia):</label>
                    <input id="max-depth" name="maxDepth" type="number" min="0" size="3" value="${param.maxDepth}">
                </p>
                <input type="submit" value="Wyślij zapytanie" >
            </fieldset>
        </form>
        <c:choose>
            <c:when test="${not empty error}">
                Błąd: ${error}.
            </c:when>
            <c:when test="${not empty stats}">
                <fmt:setLocale value="pl_PL"/>
                Znaleziono
                <fmt:formatNumber value="${stats.edits}" />&nbsp;${utils:makePluralPL(stats.edits, 'edycję wykonaną', 'edycje wykonane', 'edycji wykonanych')}
                przez
                <fmt:formatNumber value="${fn:length(results)}" />&nbsp;${utils:makePluralPL(fn:length(results), 'użytkownika', 'użytkowników', 'użytkowników')} w
                <fmt:formatNumber value="${stats.articles}" />&nbsp;${utils:makePluralPL(stats.articles, 'artykule', 'artykułach', 'artykułach')} z
                <fmt:formatNumber value="${stats.categories}" />&nbsp;${utils:makePluralPL(stats.categories, 'kategorii', 'kategorii', 'kategorii')}
                w drzewie
                „<t:linker hrefPattern="https://pl.wikipedia.org/$1" target="Kategoria:${trimmedCategory}" display="${trimmedCategory}" />”
                (głębokość: ${stats.depth}).
                <ol>
                    <c:forEach var="entry" items="${results}">
                        <li>
                            <t:linker hrefPattern="https://pl.wikipedia.org/$1" target="Wikipedysta:${entry.key}" display="${entry.key}"
                                customClasses="${sysops.contains(entry.key) ? 'rcc-sysop' : ''}" />:
                            <fmt:formatNumber value="${entry.value}" />
                        </li>
                    </c:forEach>
                </ol>
            </c:when>
        </c:choose>
    </jsp:body>
</t:template>
