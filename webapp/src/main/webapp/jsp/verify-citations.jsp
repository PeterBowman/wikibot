<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="servletPath" value="${pageContext.request.servletPath}" />
<c:set var="basePath" value="/verify-citations" />
<c:set var="heading" value="Weryfikacja typografii przypisów" />

<t:template title="${heading}" firstHeading="${heading}">
	<jsp:attribute name="cactions">
		<t:cactions basePath="${basePath}" defaultTab="strona główna"
			entries="lista" review-log="oznaczanie" edit-log="edycja"
			diff="diff" ranking="ranking" />
	</jsp:attribute>
	<jsp:attribute name="headerNotice">
		<t:ambox type="warning">
			Strona w trakcie budowy.
		</t:ambox>
	</jsp:attribute>
	<jsp:body>
		<c:set var="subPath" value="${utils:lastPathPart(servletPath)}" />
		<c:choose>
			<c:when test="${subPath eq 'entries'}">
				<jsp:include page="/WEB-INF/includes/verify-citations/entries.jsp" />
			</c:when>
			<c:otherwise>
				<jsp:include page="/WEB-INF/includes/verify-citations/main.jsp" />
			</c:otherwise>
		</c:choose>
	</jsp:body>
</t:template>
