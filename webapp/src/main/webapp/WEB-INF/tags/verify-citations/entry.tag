<%@ tag description="Generate an entry item for /entries subpage" pageEncoding="UTF-8"
	trimDirectiveWhitespaces="true" %>

<%@ attribute name="row" required="true" type="java.util.SortedMap" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>

(#${row.entry_id})
<a href="https://pl.wiktionary.org/w/index.php?curid=${row.page_id}">${row.page_title}</a>
.&nbsp;.
(${row.language} • ${row.field_localized})
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
	przez:
	<t:linker hrefPattern="https://pl.wiktionary.org/$1" target="User:${row.reviewer}" display="${row.reviewer}" />
</c:if>
.&nbsp;.
(szczegóły)
