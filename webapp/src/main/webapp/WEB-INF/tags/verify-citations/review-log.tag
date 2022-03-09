<%@ tag description="Generate an entry item for /entries subpage" pageEncoding="UTF-8"
	trimDirectiveWhitespaces="true" %>

<%@ attribute name="row" required="true" type="java.util.SortedMap" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>

<fmt:formatDate value="${row.review_timestamp}" pattern="HH:mm, d MMM yyyy" />
.&nbsp;.
<a href="?user=${fn:escapeXml(row.reviewer)}">${row.reviewer}</a>
<c:choose>
	<c:when test="${row.review_status eq true}">
		zatwierdza
	</c:when>
	<c:otherwise>
		odrzuca
	</c:otherwise>
</c:choose>
wersję #${row.change_log_id} wystąpienia
<a href="?entry=%23${row.entry_id}">#${row.entry_id}</a>
(<a href="https://pl.wiktionary.org/w/index.php?curid=${row.page_id}" target="_blank">${row.page_title}</a> •
${row.language} • ${row.field_localized})
(<a href="diff?entry=${row.entry_id}&changeid=${row.change_log_id}">szczegóły</a>)
