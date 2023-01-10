<%@ tag description="Helper tag for building SQL query bits" pageEncoding="UTF-8"
    trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>

<c:choose>
    <c:when test="${empty fn:trim(fn:replace(param.morphem, '|', ''))}">
        TRUE
    </c:when>
    <c:otherwise>
        <c:forTokens var="item" items="${param.morphem}" delims="|" varStatus="status">
            <c:set var="trimmed" value="${fn:trim(item)}" />
            <c:if test="${fn:length(trimmed) ne 0}">
                title IN (
                    SELECT
                        DISTINCT(title)
                    FROM
                        morfeo
                    WHERE
                        morphem = '${fn:replace(trimmed, "'", "''")}'
                )
                <c:if test="${not status.last}">AND</c:if>
            </c:if>
        </c:forTokens>
    </c:otherwise>
</c:choose>
