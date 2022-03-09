<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/sql" prefix="sql" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>

<p>
	Ranking użytkowników uporządkowanych pod względem oznaczonych wystąpień i wprowadzonych zmian.
	Kliknij na podlinkowane liczby w komórkach tabelki, aby przejrzeć szczegółowe rejestry.
</p>

<sql:query var="result" dataSource="${verifyCitationsDS}">
	SELECT
		user, SUM(reviews) AS total_reviews, SUM(changes) AS total_changes
	FROM (
		SELECT
			user, TRUE AS reviews, FALSE AS changes
		FROM
			review_log
		UNION
			ALL
		SELECT
			user, FALSE, TRUE
		FROM
			change_log
		WHERE
			user NOT LIKE '@%'
	    ) AS derived
	GROUP BY
		user
	ORDER BY
		total_reviews DESC, total_changes DESC;
</sql:query>

<table class="wikitable" id="ranking-sortable">
	<thead>
		<tr>
			<th>Użytkownik</th>
			<th>Oznaczone</th>
			<th>Zmienione</th>
		</tr>
	</thead>
	<tbody>
		<c:forEach var="row" items="${result.rows}">
			<tr>
				<td>
					<t:linker hrefPattern="https://pl.wiktionary.org/$1" target="User:${row.user}"
						display="${row.user}" />
				</td>
				<td>
					<a href="review-log?user=${fn:escapeXml(row.user)}">${row.total_reviews}</a>
				</td>
				<td>
					<a href="change-log?user=${fn:escapeXml(row.user)}">${row.total_changes}</a>
				</td>
			</tr>
		</c:forEach>
	</tbody>
</table>
