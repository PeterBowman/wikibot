<%@ tag description="Pagination tag for browsing variable-size lists of results" pageEncoding="UTF-8"
	trimDirectiveWhitespaces="true" %>

<%@ attribute name="limit" required="true" %>
<%@ attribute name="offset" required="true" %>
<%@ attribute name="hasNext" required="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="limit" value="${utils:max(limit, 0)}" />
<c:set var="offset" value="${utils:max(offset, 0)}" />

<p>
Zobacz
<c:choose>
	<c:when test="${offset eq 0}">
		(poprzednie ${limit}
	</c:when>
	<c:otherwise>
		(<a href='<t:replace-param offset="${utils:max(offset - limit, 0)}" />'>poprzednie ${limit}</a>
	</c:otherwise>
</c:choose>
| <c:choose>
	<c:when test="${hasNext}">
		<a href='<t:replace-param offset="${offset + limit}" />'>następne ${limit})</a>
	</c:when>
	<c:otherwise>
		następne ${limit})
	</c:otherwise>
</c:choose>
(<a href='<t:replace-param limit="20" offset="${offset}" />'>20</a>
| <a href='<t:replace-param limit="50" offset="${offset}" />'>50</a>
| <a href='<t:replace-param limit="100" offset="${offset}" />'>100</a>
| <a href='<t:replace-param limit="250" offset="${offset}" />'>250</a>
| <a href='<t:replace-param limit="500" offset="${offset}" />'>500</a>)
</p>
