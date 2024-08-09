<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.sql" prefix="sql" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>
<%@ taglib uri="jakarta.tags.functions" prefix="fn" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="hasChangeId" value="${not empty fn:trim(param.changeid)}" />

<sql:query var="result" dataSource="${verifyCitationsDS}">
    SELECT
        <%-- FIXME: some column aliases are no longer recognized after joining `edit_log` --%>
        <%-- This affects: `editor`, `field_localized`, `current_change_id` --%>
        entry_id, page_id, page_title, language, field_localized, editor, change_timestamp,
        is_pending, review_status, reviewer, review_timestamp, current_change_id,
        source_text, edited_text, all_changes.change_log_id,
        edit_log_id, rev_id, edit_timestamp
    FROM
        all_changes
            LEFT JOIN edit_log ON edit_log.change_log_id = all_changes.change_log_id
    WHERE
        entry_id = ${param.entry}
        <c:choose>
            <c:when test="${hasChangeId}">
                AND all_changes.change_log_id = ${fn:trim(param.changeid)}
            </c:when>
            <c:otherwise>
                AND all_changes.change_log_id = current_change_id
            </c:otherwise>
        </c:choose>
    ORDER BY
        edit_log_id DESC
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
                target="_blank">${row.page_title}</a></strong>,
            sekcja <strong>${row.language}</strong>,
            pole <strong>${row.localized}</strong>.
        </p>
        <p>
            Wystąpienie #${row.entry_id}, różnice ze zmianą
            <c:choose>
                <c:when test="${hasChangeId and row.change_log_id ne row.current_change_id}">
                    #${row.change_log_id}:
                </c:when>
                <c:otherwise>
                    #${row.edited_line_id} (obecna):
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
                    <c:when test="${fn:startsWith(row.user, '@')}">
                        Wygenerowano automatycznie.
                    </c:when>
                    <c:otherwise>
                        Zmiana autorstwa
                        <a href="change-log?user=${fn:escapeXml(row.user)}">${row.user}</a>.
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
            <li>
                Ostatnia edycja:
                <c:choose>
                    <c:when test="${not empty row.edit_log_id}">
                        <fmt:formatDate value="${row.edit_timestamp}" pattern="HH:mm, d MMM yyyy" />
                        (<a href="https://pl.wiktionary.org/w/index.php?diff=${row.rev_id}"
                            target="_blank">diff</a>).
                    </c:when>
                    <c:otherwise>
                        nigdy.
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
