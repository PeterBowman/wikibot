<%@ tag description="c-actions toolbar section" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ attribute name="targetPath" required="true" %>
<%@ attribute name="currentPath" required="true" %>
<%@ attribute name="label" required="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>

<c:if test="${targetPath eq currentPath}">
	<c:set value="selected" var="classVar" />
</c:if>

<li class="${classVar}">
	<a href="${pageContext.request.contextPath}${targetPath}">${label}</a>
</li>