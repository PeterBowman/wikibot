<%@ tag description="Generate an entry item for /entries subpage" pageEncoding="UTF-8"
    trimDirectiveWhitespaces="true" %>

<%@ attribute name="row" required="true" type="java.util.SortedMap" %>

<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.functions" prefix="fn" %>

(#${row.entry_id})
<a href="https://pl.wiktionary.org/w/index.php?curid=${row.page_id}" target="_blank">${row.page_title}</a>
•
${row.language} • ${row.field_localized}
<c:if test="${row.is_pending eq 1}">
    .&nbsp;.
    oczekuje na przetworzenie
</c:if>
<c:if test="${not empty row.review_status}">
    .&nbsp;.
    <c:choose>
        <c:when test="${row.review_status eq true}">
            zatwierdzone
        </c:when>
        <c:when test="${row.review_status eq false}">
            odrzucone
        </c:when>
    </c:choose>
    przez
    <a href="review-log?user=${fn:escapeXml(row.reviewer)}">${row.reviewer}</a>
</c:if>
.&nbsp;.
(<a href="diff?entry=${row.entry_id}">szczegóły</a>)
