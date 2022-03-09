<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/sql" prefix="sql" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib tagdir="/WEB-INF/tags/verify-citations" prefix="vc" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<p>
	Rejest zmian w stosunku do wersji pierwotnej danego wystąpienia. Lista zawiera zarówno zmiany
	wprowadzone przez użytkowników, jak i wygenerowane przez automat (zaznaczywszy tę opcję).
</p>

<form action="${pageContext.request.contextPath}${pageContext.request.servletPath}" method="GET">
	<fieldset>
		<legend>Rejestr zmian</legend>
		<span class="mw-input-with-label">
			<label for="vc-change-log-user">Użytkownik:</label>
			<input id="vc-change-log-user" name="user" size="15" value="${param.user}" data-api-type="user">
		</span>
		<span class="mw-input-with-label">
			<label for="vc-change-log-entry">Tytuł strony lub identyfikator wystąpienia${entryInputInfoTag}:</label>
			<input id="vc-change-log-entry" name="entry" size="20" value="${param.entry}" data-api-type="title">
		</span>
		<span class="mw-input-with-label">
			<input type="checkbox" id="vc-change-log-show-generated" name="showgenerated"
				<c:if test="${not empty param.showgenerated}">checked</c:if>>
			<label for="vc-change-log-show-generated">pokaż wygenerowane</label>
		</span>
		<input type="submit" value="Pokaż" >
	</fieldset>
</form>

<c:set var="limit" value="${not empty param.limit ? utils:max(param.limit, 0) : defaultLimit}" />
<c:set var="offset" value="${not empty param.offset ? utils:max(param.offset, 0) : 0}" />

<sql:query var="result" dataSource="${verifyCitationsDS}" startRow="${offset}" maxRows="${limit}">
	SELECT
		entry_id, page_id, page_title, language, field_localized, editor, change_timestamp, change_log_id
	FROM
		all_changes
	WHERE
		TRUE
		<c:if test="${not empty fn:trim(param.user)}">
			AND editor = ?
			<sql:param value="${fn:trim(param.user)}" />
		</c:if>
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
		<c:if test="${empty param.showgenerated}">
			AND editor NOT LIKE '@%'
		</c:if>
	ORDER BY
		change_log_id DESC;
</sql:query>

<c:choose>
	<c:when test="${result.rowCount eq 0}">
		<p>Nie znaleziono pozycji odpowiadających zapytaniu.</p> 
	</c:when>
	<c:otherwise>
		<t:paginator limit="${limit}" offset="${offset}" hasNext="${result.limitedByMaxRows}" />
		<ul>
			<c:forEach var="row" items="${result.rows}">
				<li><vc:change-log row="${row}" /></li>
			</c:forEach>
		</ul>
		<t:paginator limit="${limit}" offset="${offset}" hasNext="${result.limitedByMaxRows}" />
	</c:otherwise>
</c:choose>
