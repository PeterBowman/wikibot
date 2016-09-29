<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/sql" prefix="sql" %>

<sql:query var="resultCommon" dataSource="jdbc/plwikt-common">
	SELECT
		CASE WHEN type LIKE '%.update' THEN timestamp END AS updated,
	    CASE WHEN type LIKE '%.edit' THEN timestamp END AS edited
	FROM
		execution_log
	WHERE
		type LIKE 'tasks.plwikt.CitationTypography.%';
</sql:query>

<sql:query var="result1" dataSource="${verifyCitationsDS}">
	SELECT
		COUNT(*) AS total_entries,
		COUNT(DISTINCT page_title) AS total_pages,
	    SUM(IF(is_pending, 1, 0)) AS total_pending,
	    SUM(IF(review_status, 1, 0)) AS total_verified,
	    SUM(IF(NOT review_status, 1, 0)) AS total_rejected
	FROM
		all_entries;
</sql:query>

<sql:query var="result2" dataSource="${verifyCitationsDS}">
	SELECT
		COUNT(*) AS total_changes,
	    SUM(IF(editor NOT LIKE '@%', 1, 0)) AS manual_changes,
	    COUNT(DISTINCT editor) - 1 AS total_editors,
    	COUNT(DISTINCT reviewer) AS total_reviewers
	FROM
		all_changes;
</sql:query>

<sql:query var="result3" dataSource="${verifyCitationsDS}">
	SELECT
		COUNT(*) AS total_edits,
	    COUNT(DISTINCT change_log_id) AS distinct_edits
	FROM
		edit_log;
</sql:query>

<sql:query var="result4" dataSource="${verifyCitationsDS}">
	SELECT
		COUNT(DISTINCT user) AS total_users
	FROM (
		SELECT user
		FROM change_log
		WHERE user NOT LIKE '@%'
		UNION ALL
		SELECT USER
		FROM review_log
	) AS derived;
</sql:query>

<c:set var="rowCommon" value="${resultCommon.rows[0]}" />
<c:set var="row1" value="${result1.rows[0]}" />
<c:set var="row2" value="${result2.rows[0]}" />
<c:set var="row3" value="${result3.rows[0]}" />
<c:set var="row4" value="${result4.rows[0]}" />

<table class="wikitable mw-statistics-table floatright">
	<caption>Statystyki</caption>
	<tr>
		<th colspan="2">Wystąpienia</th>
	</tr>
	<tr>
		<td><a href="verify-citations/entries?hideprocessed=on">Oczekujące na przetworzenie</a></td>
		<td class="mw-statistics-numbers">${row1.total_pending}</td>
	</tr>
	<tr>
		<td><a href="verify-citations/entries">Wszystkie</a></td>
		<td class="mw-statistics-numbers">${row1.total_entries}</td>
	</tr>
	<tr>
		<td>Wszystkie (jednakowe strony)</td>
		<td class="mw-statistics-numbers">${row1.total_pages}</td>
	</tr>
	<tr>
		<th colspan="2">Weryfikacja</th>
	</tr>
	<tr>
		<td>Zatwierdzone wystąpienia</td>
		<td class="mw-statistics-numbers">${row1.total_verified}</td>
	</tr>
	<tr>
		<td>Odrzuczone wystąpienia</td>
		<td class="mw-statistics-numbers">${row1.total_rejected}</td>
	</tr>
	<tr>
		<th colspan="2">Zmiany</th>
	</tr>
	<tr>
		<td><a href="verify-citations/change-log">Ręcznie wprowadzone</a></td>
		<td class="mw-statistics-numbers">${row2.manual_changes}</td>
	</tr>
	<tr>
		<td><a href="verify-citations/change-log?showgenerated=on">Wszystkie</a></td>
		<td class="mw-statistics-numbers">${row2.total_changes}</td>
	</tr>
	<tr>
		<th colspan="2">Edycje</th>
	</tr>
	<tr>
		<td>Jednakowe wystąpienia</td>
		<td class="mw-statistics-numbers">${row3.distinct_edits}</td>
	</tr>
	<tr>
		<td><a href="verify-citations/edit-log">Wszystkie</a></td>
		<td class="mw-statistics-numbers">${row3.total_edits}</td>
	</tr>
	<tr>
		<th colspan="2">Użytkownicy</th>
	</tr>
	<tr>
		<td>Weryfikatorzy</td>
		<td class="mw-statistics-numbers">${row2.total_reviewers}</td>
	</tr>
	<tr>
		<td>Edytorzy</td>
		<td class="mw-statistics-numbers">${row2.total_editors}</td>
	</tr>
	<tr>
		<td><a href="verify-citations/ranking">Wszyscy</a></td>
		<td class="mw-statistics-numbers">${row4.total_users}</td>
	</tr>
</table>
