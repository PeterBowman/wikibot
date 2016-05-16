<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/sql" prefix="sql" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="heading" value="Weryfikacja typografii przypisów" />

<%-- http://www.coderanch.com/t/177671 --%>
<c:set var="defaultLimit" value="100" scope="request" />
<sql:setDataSource var="verifyCitationsDS" dataSource="jdbc/VerifyCitations" scope="request" />

<jsp:useBean id="cactionsMap" class="java.util.LinkedHashMap" />
<c:set target="${cactionsMap}" property="entries" value="lista" />
<c:set target="${cactionsMap}" property="review-log" value="oznaczanie" />
<c:set target="${cactionsMap}" property="change-log" value="zmiany" />
<c:set target="${cactionsMap}" property="edit-log" value="edycja" />
<c:set target="${cactionsMap}" property="info" value="info" />
<c:set target="${cactionsMap}" property="ranking" value="ranking" />

<t:template title="${heading}" firstHeading="${heading}">
	<jsp:attribute name="cactions">
		<t:cactions basePath="/verify-citations" defaultTab="strona główna" tabs="${cactionsMap}" />
	</jsp:attribute>
	<jsp:attribute name="headerNotice">
		<t:ambox type="warning">
			Strona w trakcie budowy.
		</t:ambox>
	</jsp:attribute>
	<jsp:body>
		<c:set var="subPath" value="${utils:lastPathPart(pageContext.request.servletPath)}" />
		<c:choose>
			<c:when test="${subPath eq 'entries'}">
				<jsp:include page="/WEB-INF/includes/verify-citations/entries.jsp" />
			</c:when>
			<c:when test="${subPath eq 'review-log'}">
				<jsp:include page="/WEB-INF/includes/verify-citations/review-log.jsp" />
			</c:when>
			<c:otherwise>
				<jsp:include page="/WEB-INF/includes/verify-citations/main.jsp" />
			</c:otherwise>
		</c:choose>
	</jsp:body>
</t:template>
