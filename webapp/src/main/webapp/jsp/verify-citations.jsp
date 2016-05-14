<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>

<c:set var="servletPath" value="${pageContext.request.servletPath}" />
<c:set var="basePath" value="/verify-citations" />

<t:template title="Weryfikacja typografii przypisów" firstHeading="Weryfikacja typografii przypisów">
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
		<c:choose>
			<c:when test="${servletPath eq '/verify-citations/entries'}">
				<p>test</p>
			</c:when>
			<c:otherwise>
				<%-- TODO: include action or directive? --%>
				<%@ include file="/WEB-INF/includes/verify-citations/main.jsp" %>
			</c:otherwise>
		</c:choose>
	</jsp:body>
</t:template>
