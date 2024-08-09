<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.functions" prefix="fn" %>

<p>
    Podgląd zmian (diff) pomiędzy wskazaną wersją wystąpienia (wygenerowaną bądź wprowadzoną
    przez użytkownika) a jego wersją pierwotną (pobraną z bazy).
</p>

<form action="${pageContext.request.contextPath}${pageContext.request.servletPath}" method="GET">
    <fieldset>
        <legend>Podgląd zmian</legend>
        <span class="mw-input-with-label">
            <label for="vc-diff-entry">Identyfikator wystąpienia:</label>
            <input id="vc-diff-entry" name="entry" size="10" value="${param.entry}">
        </span>
        <span class="mw-input-with-label">
            <label for="vc-diff-change-id">Wersja zmiany (domyślnie wersja bieżąca):</label>
            <input id="vc-diff-change-id" name="changeid" size="10" value="${param.changeid}">
        </span>
        <input type="submit" value="Pokaż" >
    </fieldset>
</form>

<c:if test="${not empty fn:trim(param.entry)}">
    <jsp:include page="/WEB-INF/includes/verify-citations/diff-query.jsp" />
</c:if>
