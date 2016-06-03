<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/sql" prefix="sql" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib tagdir="/WEB-INF/tags/verify-citations" prefix="vc" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<p>
	Rejest edycji wykonanych przez bota.
</p>

<%-- TODO: filter by timestamp + recalculate offset --%>
<form action="${pageContext.request.contextPath}${pageContext.request.servletPath}" method="GET">
	<fieldset>
		<legend>Rejestr edycji bota</legend>
		<span class="mw-input-with-label">
			<label for="vc-edit-log-entry">Tytuł strony lub identyfikator wystąpienia${entryInputInfoTag}:</label>
			<input id="vc-edit-log-entry" name="entry" size="20" value="${param.entry}">
		</span>
		<input type="submit" value="Pokaż" >
	</fieldset>
</form>

<c:set var="limit" value="${not empty param.limit ? utils:max(param.limit, 0) : defaultLimit}" />
<c:set var="offset" value="${not empty param.offset ? utils:max(param.offset, 0) : 0}" />

<sql:query var="result" dataSource="${verifyCitationsDS}" startRow="${offset}" maxRows="${limit}">
	SELECT
		entry.entry_id, entry.page_id, page_title, language, localized, edit_timestamp, edit_log_id, rev_id
	FROM
		edit_log
			INNER JOIN entry ON entry.entry_id = edit_log.entry_id
			INNER JOIN page_title ON page_title.page_id = entry.page_id
			INNER JOIN field ON field.field_id = entry.field_id
	WHERE
		TRUE
		<c:set var="trimmedEntry" value="${fn:trim(param.entry)}" />
		<c:if test="${not empty trimmedEntry}">
			<c:choose>
				<c:when test="${fn:startsWith(trimmedEntry, '#')}">
					AND entry.entry_id = ${fn:substringAfter(trimmedEntry, '#')}
				</c:when>
				<c:otherwise>
					AND page_title = ?
					<sql:param value="${trimmedEntry}" />
				</c:otherwise>
			</c:choose>
		</c:if>
		<c:remove var="trimmedEntry" />
	ORDER BY
		edit_log_id DESC;
</sql:query>

<c:choose>
	<c:when test="${result.rowCount eq 0}">
		<p>Nie znaleziono pozycji odpowiadających zapytaniu.</p> 
	</c:when>
	<c:otherwise>
		<%-- TODO: allow to sort by oldest first --%>
		<t:paginator limit="${limit}" offset="${offset}" hasNext="${result.limitedByMaxRows}" />
		<ul>
			<c:forEach var="row" items="${result.rows}">
				<li><vc:edit-log row="${row}" /></li>
			</c:forEach>
		</ul>
		<t:paginator limit="${limit}" offset="${offset}" hasNext="${result.limitedByMaxRows}" />
	</c:otherwise>
</c:choose>
