<%@ tag description="Generate a log item for /change-log subpage" pageEncoding="UTF-8"
    trimDirectiveWhitespaces="true" %>

<%@ attribute name="row" required="true" type="java.util.SortedMap" %>

<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib prefix="fmt" uri="jakarta.tags.fmt" %>
<%@ taglib uri="jakarta.tags.functions" prefix="fn" %>

<fmt:formatDate value="${row.change_timestamp}" pattern="HH:mm, d MMM yyyy" />
.&nbsp;.
<c:choose>
    <c:when test="${fn:startsWith(row.editor, '@')}">
        wygenerowano
    </c:when>
    <c:otherwise>
        <a href="?user=${fn:escapeXml(row.editor)}">${row.editor}</a>
        tworzy
    </c:otherwise>
</c:choose>
wersję #${row.change_log_id} wystąpienia
<a href="?entry=%23${row.entry_id}&showgenerated=on">#${row.entry_id}</a>
(<a href="https://pl.wiktionary.org/w/index.php?curid=${row.page_id}" target="_blank">${row.page_title}</a> •
${row.language} • ${row.field_localized})
(<a href="diff?entry=${row.entry_id}&changeid=${row.change_log_id}">szczegóły</a>)
