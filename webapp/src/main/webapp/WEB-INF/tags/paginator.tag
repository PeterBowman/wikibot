<%--
    TODO: implement sorting by oldest/last first
    * http://stackoverflow.com/questions/17522850
    * http://stackoverflow.com/questions/3879248
    * http://stackoverflow.com/questions/712046
--%>
<%@ tag description="Pagination tag for browsing variable-size lists of results" pageEncoding="UTF-8"
    trimDirectiveWhitespaces="true" %>

<%@ attribute name="limit" required="true" %>
<%@ attribute name="offset" required="true" %>
<%@ attribute name="hasNext" required="true" %>
<%@ attribute name="limits" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="limit" value="${utils:max(limit, 0)}" />
<c:set var="offset" value="${utils:max(offset, 0)}" />

<c:if test="${empty limits}">
    <c:set var="limits" value="20,50,100,250,500" />
</c:if>

<p class="paginator">
Zobacz
<c:choose>
    <c:when test="${offset eq 0}">
        (<span class="paginator-prev">poprzednie <span class="paginator-prev-value">${limit}</span></span>
    </c:when>
    <c:otherwise>
        (<span class="paginator-prev"><a href='<t:replace-param offset="${utils:max(offset - limit, 0)}" />'>poprzednie
            <span class="paginator-prev-value">${limit}</span></a></span>
    </c:otherwise>
</c:choose>
| <c:choose>
    <c:when test="${hasNext}">
        <span class="paginator-next"><a href='<t:replace-param offset="${offset + limit}" />'>
            następne <span class="paginator-next-value">${limit}</span></a></span>)
    </c:when>
    <c:otherwise>
        <span class="paginator-next">następne <span class="paginator-next-value">${limit}</span></span>)
    </c:otherwise>
</c:choose>
<span class="paginator-limits">
    <c:forTokens var="item" items="${limits}" delims="," varStatus="status">
        <c:choose>
            <c:when test="${status.first}">(</c:when>
            <c:otherwise> | </c:otherwise>
        </c:choose>
        <a href='<t:replace-param limit="${item}" offset="${offset}" />'>${item}</a><c:if test="${status.last}">)</c:if>
    </c:forTokens>
</span>
</p>
