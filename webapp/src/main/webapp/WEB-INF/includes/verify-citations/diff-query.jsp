<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/sql" prefix="sql" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="hasChangeId" value="${not empty fn:trim(param.changeid)}" />

<sql:query var="result" dataSource="${verifyCitationsDS}">
	SELECT
		entry_id, page_id, page_title, language, field_localized, editor, change_timestamp,
		is_pending, review_status, reviewer, review_timestamp, current_change_id,
		source_text, edited_text, change_log_id
	FROM
		all_changes
	WHERE
		entry_id = ${param.entry}
		<c:choose>
			<c:when test="${hasChangeId}">
				AND change_log_id = ${fn:trim(param.changeid)}
			</c:when>
			<c:otherwise>
				AND change_log_id = current_change_id
			</c:otherwise>
		</c:choose>
	LIMIT
		1;
</sql:query>

<c:choose>
	<c:when test="${result.rowCount eq 0}">
		<p>Nie znaleziono pozycji odpowiadających zapytaniu.</p> 
	</c:when>
	<c:otherwise>
		<c:set var="row" value="${result.rows[0]}" />
		<p>
			Hasło <strong><a href="https://pl.wiktionary.org/w/index.php?curid=${row.page_id}"
				>${row.page_title}</a></strong>,
			sekcja <strong>${row.language}</strong>,
			pole <strong>${row.field_localized}</strong>.
		</p>
		<p>
			Wystąpienie #${row.entry_id}, różnice ze zmianą
			<c:choose>
				<c:when test="${hasChangeId and row.change_log_id ne row.current_change_id}">
					#${row.change_log_id}:
				</c:when>
				<c:otherwise>
					#${row.current_change_id} (obecna):
				</c:otherwise>
			</c:choose>
		</p>
		<c:set var="diff" value="${utils:getDiff(row.source_text, row.edited_text)}" />
		<c:choose>
			<c:when test="${not empty diff}">
				${diff}
			</c:when>
			<c:otherwise>
				<p>Nie wykryto różnic.</p>
			</c:otherwise>
		</c:choose>
		<h2>Szczegóły</h2>
		<ul>
			<li>
				Wystąpienie oczekuje na przetworzenie:
				<c:choose>
					<c:when test="${row.is_pending eq 1}">tak.</c:when>
					<c:otherwise>nie.</c:otherwise>
				</c:choose>
			</li>
			<li>
				<c:choose>
					<c:when test="${fn:startsWith(row.editor, '@')}">
						Wygenerowano automatycznie.
					</c:when>
					<c:otherwise>
						Zmiana autorstwa
						<a href="change-log?user=${fn:escapeXml(row.editor)}">${row.editor}</a>.
					</c:otherwise>
				</c:choose>
				Sygnatura czasowa:
				<fmt:formatDate value="${row.change_timestamp}" pattern="HH:mm, d MMM yyyy" />.
			</li>
			<li>
				Stan weryfikacji:
				<c:choose>
					<c:when test="${empty row.review_status}">
						niezweryfikowane.
					</c:when>
					<c:otherwise>
						<c:choose>
							<c:when test="${row.review_status}">
								zatwierdzone
							</c:when>
							<c:otherwise>
								odrzuczone
							</c:otherwise>
						</c:choose>
						przez
						<a href="review-log?user=${fn:escapeXml(row.reviewer)}">${row.reviewer}</a>.
						Sygnatura czasowa:
						<fmt:formatDate value="${row.review_timestamp}" pattern="HH:mm, d MMM yyyy" />.
					</c:otherwise>
				</c:choose>
			</li>
		</ul>
		<h2>Powiązane rejestry operacji</h2>
		<ul>
			<li><a href="review-log?entry=%23${row.entry_id}">Rejestr oznaczania</a></li>
			<li><a href="change-log?entry=%23${row.entry_id}&showgenerated=on">Rejestr zmian</a></li>
			<li><a href="edit-log?entry=%23${row.entry_id}">Rejestr edycji</a></li>
		</ul>
	</c:otherwise>
</c:choose>
