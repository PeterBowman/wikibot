<%@ tag description="Generate an entry item for /entries subpage" pageEncoding="UTF-8"
	trimDirectiveWhitespaces="true" %>

<%@ attribute name="row" required="true" type="java.util.SortedMap" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>

<fmt:formatDate value="${row.review_timestamp}" pattern="HH:mm, d MMM yyyy" />
.&nbsp;.
<t:linker hrefPattern="https://pl.wiktionary.org/$1" target="User:${row.reviewer}" display="${row.reviewer}" />
<c:choose>
	<c:when test="${row.review_status eq true}">
		zatwierdza
	</c:when>
	<c:otherwise>
		odrzuca
	</c:otherwise>
</c:choose>
wersję #${row.current_change_id} wystąpienia #${row.entry_id}
(<a href="https://pl.wiktionary.org/w/index.php?curid=${row.page_id}">${row.page_title}</a> •
${row.language} • ${row.field_localized})
(szczegóły)
