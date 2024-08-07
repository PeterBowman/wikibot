<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.sql" prefix="sql" %>
<%@ taglib uri="jakarta.tags.fmt" prefix="fmt" %>
<%@ taglib uri="jakarta.tags.functions" prefix="fn" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib tagdir="/WEB-INF/tags/eom-backlinks" prefix="eombl" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="defaultLimit" value="250" />
<c:set var="columnThreshold" value="25" />

<c:set var="limit" value="${not empty param.limit ? utils:max(param.limit, 0) : defaultLimit}" />
<c:set var="offset" value="${not empty param.offset ? utils:max(param.offset, 0) : 0}" />

<sql:query var="result" dataSource="jdbc/EomBacklinks" startRow="${offset}" maxRows="${limit}">
    SELECT
        title,
        GROUP_CONCAT(morphem ORDER BY position SEPARATOR '|') AS morphems,
        GROUP_CONCAT(type ORDER BY position SEPARATOR '|') AS types
    FROM
        morfeo
    GROUP BY
        title
    HAVING
        <eombl:query-builder />
    ORDER BY
        title COLLATE utf8mb4_esperanto_ci;
</sql:query>

<c:choose>
    <c:when test="${result.rowCount eq 0}">
        <p>Nie znaleziono pozycji odpowiadających zapytaniu.</p>
    </c:when>
    <c:otherwise>
        <%-- TODO: is it possible to accomplish this without launching a new query? --%>
        <sql:query var="count" dataSource="jdbc/EomBacklinks">
            SELECT COUNT(DISTINCT title) AS total FROM morfeo WHERE <eombl:query-builder />;
        </sql:query>
        <c:set var="total" value="${count.rows[0].total}" />
        <p>
            Znaleziono <strong>${total}</strong> ${utils:makePluralPL(total, 'wynik', 'wyniki', 'wyników')}.
            <c:if test="${not empty fn:trim(fn:replace(param.morphem, '|', ''))}">
                Parametry zapytania: <eombl:morphem-analyzer />.
            </c:if>
        </p>
        <t:paginator limit="${limit}" offset="${offset}" hasNext="${result.limitedByMaxRows}" />
        <ol start="${offset + 1}" <c:if test="${result.rowCount gt columnThreshold}">class="column-list"</c:if>>
            <c:forEach var="row" items="${result.rows}">
                <li>
                    <t:linker hrefPattern="https://pl.wiktionary.org/$1#eo" target="${row.title}" sectionName="esperanto" />
                    →
                    <eombl:format-morphems morphems="${row.morphems}" types="${row.types}" />
                </li>
            </c:forEach>
        </ol>
        <t:paginator limit="${limit}" offset="${offset}" hasNext="${result.limitedByMaxRows}" />
    </c:otherwise>
</c:choose>
