<%@ tag description="c-actions toolbar section" pageEncoding="UTF-8" dynamic-attributes="tabs"
	trimDirectiveWhitespaces="true" %>

<%@ attribute name="basePath" required="true" %>
<%@ attribute name="defaultTab" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>

<c:set var="contextPath" value="${pageContext.request.contextPath}" />
<c:set var="servletPath" value="${pageContext.request.servletPath}" />

<c:if test="${not empty defaultTab}">
	<li <c:if test="${servletPath eq basePath}">class="selected"</c:if>>
		<a href="${contextPath}${basePath}">${defaultTab}</a>
	</li>
</c:if>

<c:forEach var="tab" items="${tabs}">
	<c:set var="tabPath" value="${basePath}/${tab.key}" />
	<li <c:if test="${servletPath eq tabPath}">class="selected"</c:if>>
		<a href="${contextPath}${tabPath}">${tab.value}</a>
	</li>
</c:forEach>
