<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/sql" prefix="sql" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib tagdir="/WEB-INF/tags/verify-citations" prefix="vc" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<p>
	Lista wszystkich wystąpień wykrytych w hasłach i zapisanych w bazie.
	Wystąpienie oczekujące na przetworzenie to takie, które znajduje się na osobnej liście
	roboczej tak długo, jak hasło w Wikisłowniku nie zostanie naprawione zgodnie z
	<a href="./">założeniami tego narzędzia</a> – ręcznie lub za pośrednictwem bota – bądź
	oznaczone jako „odrzucone”.
</p>

<form action="${pageContext.request.contextPath}${pageContext.request.servletPath}" method="GET">
	<fieldset>
		<legend>Lista wystąpień</legend>
		<span class="mw-input-with-label">
			<label for="vc-entries-entry">Tytuł strony lub identyfikator wystąpienia${entryInputInfoTag}:</label>
			<input id="vc-entries-entry" name="entry" size="20" value="${param.entry}">
		</span>
		Ukryj:
		<span class="mw-input-with-label">
			<input type="checkbox" id="vc-entries-hideprocessed" name="hideprocessed"
				<c:if test="${not empty param.hideprocessed}">checked</c:if>>
			<label for="vc-entries-hideprocessed">przetworzone</label>
		</span>
		<span class="mw-input-with-label">
			<input type="checkbox" id="vc-entries-hide-verified" name="hideverified"
				<c:if test="${not empty param.hideverified}">checked</c:if>>
			<label for="vc-entries-hide-verified">zatwierdzone</label>
		</span>
		<span class="mw-input-with-label">
			<input type="checkbox" id="vc-entries-hide-rejected" name="hiderejected"
				<c:if test="${not empty param.hiderejected}">checked</c:if>>
			<label for="vc-entries-hide-rejected">odrzucone</label>
		</span>
		<input type="submit" value="Pokaż" >
	</fieldset>
</form>

<c:set var="limit" value="${not empty param.limit ? utils:max(param.limit, 0) : defaultLimit}" />
<c:set var="offset" value="${not empty param.offset ? utils:max(param.offset, 0) : 0}" />

<sql:query var="result" dataSource="${verifyCitationsDS}" startRow="${offset}" maxRows="${limit}">
	SELECT
		entry_id, page_id, page_title, language, field_localized, is_pending, review_status, reviewer
	FROM
		all_entries
	WHERE
		TRUE
		<c:set var="trimmedEntry" value="${fn:trim(param.entry)}" />
		<c:if test="${not empty trimmedEntry}">
			<c:choose>
				<c:when test="${fn:startsWith(trimmedEntry, '#')}">
					AND entry_id = ${fn:substringAfter(trimmedEntry, '#')}
				</c:when>
				<c:otherwise>
					AND page_title = ?
					<sql:param value="${trimmedEntry}" />
				</c:otherwise>
			</c:choose>
		</c:if>
		<c:remove var="trimmedEntry" />
		<c:if test="${not empty param.hideprocessed}">
			AND is_pending IS TRUE
		</c:if>
		<c:if test="${not empty param.hideverified}">
			AND (review_status IS NULL OR review_status IS FALSE)
		</c:if>
		<c:if test="${not empty param.hiderejected}">
			AND (review_status IS NULL OR review_status IS TRUE)
		</c:if>
	ORDER BY
		entry_id DESC;
</sql:query>

<c:choose>
	<c:when test="${result.rowCount eq 0}">
		<p>Nie znaleziono pozycji odpowiadających zapytaniu.</p> 
	</c:when>
	<c:otherwise>
		<t:paginator limit="${limit}" offset="${offset}" hasNext="${result.limitedByMaxRows}" />
		<ul>
			<c:forEach var="row" items="${result.rows}">
				<c:if test="${row.is_pending eq 1}">
					<c:set var="classVar" value="vc-entry-pending" />
				</c:if>
				<c:choose>
					<c:when test="${row.review_status eq true}">
						<c:set var="classVar" value="${classVar} vc-entry-verified" />
					</c:when>
					<c:when test="${row.review_status eq false}">
						<c:set var="classVar" value="${classVar} vc-entry-rejected" />
					</c:when>
				</c:choose>
				<li <c:if test="${not empty classVar}">class="${fn:trim(classVar)}"</c:if>>
					<vc:entry row="${row}" />
				</li>
				<c:remove var="classVar" />
			</c:forEach>
		</ul>
		<t:paginator limit="${limit}" offset="${offset}" hasNext="${result.limitedByMaxRows}" />
	</c:otherwise>
</c:choose>
