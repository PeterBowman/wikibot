<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="subPath" value="${utils:lastPathPart(pageContext.request.servletPath)}" />

<c:choose>
	<c:when test="${subPath eq 'plwikt-polish-masculine-nouns'}">
		<jsp:forward page="/WEB-INF/includes/weblists/plwikt-polish-masculine-nouns.jsp" />
	</c:when>
	<c:when test="${subPath eq 'plwiki-sandbox-redirects'}">  <%-- UNUSED, see web.xml --%>
		<jsp:forward page="/weblists/plwiki-sandbox-redirects" />
	</c:when>
	<c:otherwise>
		<jsp:forward page="/WEB-INF/includes/weblists/index.jsp" />
	</c:otherwise>
</c:choose>
