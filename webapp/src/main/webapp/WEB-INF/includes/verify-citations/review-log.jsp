<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/sql" prefix="sql" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib tagdir="/WEB-INF/tags/verify-citations" prefix="vc" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<p>
	Rejest oznaczania wystąpień na <a href="./entries?hideprocessed=on">liście roboczej</a>.
	Dostępne poziomy: „zatwierdzone” oraz „odrzucone”.
</p>

<%-- TODO: filter by timestamp + recalculate offset --%>
<form action="${pageContext.request.contextPath}${pageContext.request.servletPath}" method="GET">
	<fieldset>
		<legend>Rejestr oznaczania</legend>
		<label for="vc-review-log-user">Użytkownik:</label>
		<input id="vc-review-log-user" name="user" size="15" value="${param.user}">
		<label for="vc-review-log-title">Tytuł strony:</label>
		<input id="vc-review-log-title" name="title" size="20" value="${param.title}">
		<input type="checkbox" id="vc-review-log-hide-verified" name="hideverified"
			<c:if test="${not empty param.hideverified}">checked</c:if>>
		<label for="vc-review-log-hide-verified">ukryj zatwierdzone</label>
		<input type="checkbox" id="vc-review-log-hide-rejected" name="hiderejected"
			<c:if test="${not empty param.hiderejected}">checked</c:if>>
		<label for="vc-review-log-hide-rejected">ukryj odrzucone</label>
		<input type="submit" value="Pokaż" >
	</fieldset>
</form>

<c:set var="limit" value="${not empty param.limit ? utils:max(param.limit, 0) : defaultLimit}" />
<c:set var="offset" value="${not empty param.offset ? utils:max(param.offset, 0) : 0}" />

<sql:query var="result" dataSource="${verifyCitationsDS}" startRow="${offset}" maxRows="${limit}">
	SELECT
		entry_id, page_id, page_title, language, field_localized, review_status, reviewer,
		review_timestamp, current_change_id
	FROM
		all_entries
	WHERE
		review_status IS NOT NULL
		<c:if test="${not empty fn:trim(param.user)}">
			AND reviewer = ?
			<sql:param value="${fn:trim(param.user)}" />
		</c:if>
		<c:if test="${not empty fn:trim(param.title)}">
			AND page_title = ?
			<sql:param value="${fn:trim(param.title)}" />
		</c:if>
		<c:if test="${not empty param.hideverified}">
			AND (review_status IS NULL OR review_status IS FALSE)
		</c:if>
		<c:if test="${not empty param.hiderejected}">
			AND (review_status IS NULL OR review_status IS TRUE)
		</c:if>
	ORDER BY
		review_timestamp DESC, entry_id DESC;
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
				<li><vc:review-log row="${row}" /></li>
			</c:forEach>
		</ul>
		<t:paginator limit="${limit}" offset="${offset}" hasNext="${result.limitedByMaxRows}" />
	</c:otherwise>
</c:choose>
