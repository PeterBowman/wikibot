<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/sql" prefix="sql" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="heading" value="Weryfikacja typografii przypisów" />

<%-- http://www.coderanch.com/t/177671 --%>
<c:set var="defaultLimit" value="100" scope="request" />
<sql:setDataSource var="verifyCitationsDS" dataSource="jdbc/VerifyCitations" scope="request" />

<fmt:setLocale value="pl_PL" scope="request" />
<fmt:setTimeZone value="Europe/Madrid" scope="request" />

<c:set var="entryInputInfoTag" scope="request">
	<sup><span title="Poprzedzony znakiem '#', np. #1234" style="cursor: help;">(?)</span></sup>
</c:set>

<jsp:useBean id="cactionsMap" class="java.util.LinkedHashMap" />
<c:set target="${cactionsMap}" property="entries" value="lista" />
<c:set target="${cactionsMap}" property="review-log" value="oznaczanie" />
<c:set target="${cactionsMap}" property="change-log" value="zmiany" />
<c:set target="${cactionsMap}" property="edit-log" value="edycja" />
<c:set target="${cactionsMap}" property="diff" value="diff" />
<c:set target="${cactionsMap}" property="ranking" value="ranking" />

<c:set var="contextPath" value="${pageContext.request.contextPath}" />
<c:set var="subPath" value="${utils:lastPathPart(pageContext.request.servletPath)}" />

<t:template title="${heading}" firstHeading="${heading}" enableJS="true">
	<jsp:attribute name="head">
		<c:if test="${
			subPath eq 'entries' or subPath eq 'review-log' or
			subPath eq 'change-log' or subPath eq 'edit-log'
		}">
			<link href="${contextPath}/styles/suggestions.css" type="text/css" rel="stylesheet">
			<script src="${contextPath}/scripts/suggestions.js"></script>
		</c:if>
		<c:if test="${subPath eq 'diff'}">
			<link href="${contextPath}/styles/diff.css" type="text/css" rel="stylesheet">
		</c:if>
		<c:if test="${subPath eq 'ranking'}">
			<script src="//tools-static.wmflabs.org/static/jquery-tablesorter/2.0.5/jquery.tablesorter.min.js"></script>
		</c:if>
		<link href="${contextPath}/styles/verify-citations.css" type="text/css" rel="stylesheet">
		<script src="${contextPath}/scripts/verify-citations.js"></script>
	</jsp:attribute>
	<jsp:attribute name="cactions">
		<t:cactions basePath="/verify-citations" defaultTab="strona główna" tabs="${cactionsMap}" />
	</jsp:attribute>
	<jsp:attribute name="headerNotice">
		<t:ambox type="warning">
			Narzędzie znajduje się w fazie ukończenia i testów. Przedstawiona zawartość bazy danych
			nie jest ostateczna i może ulec zmianie lub całkowitym skasowaniu w dowolnej chwili.
		</t:ambox>
	</jsp:attribute>
	<jsp:body>
		<c:choose>
			<c:when test="${subPath eq 'entries'}">
				<jsp:include page="/WEB-INF/includes/verify-citations/entries.jsp" />
			</c:when>
			<c:when test="${subPath eq 'review-log'}">
				<jsp:include page="/WEB-INF/includes/verify-citations/review-log.jsp" />
			</c:when>
			<c:when test="${subPath eq 'change-log'}">
				<jsp:include page="/WEB-INF/includes/verify-citations/change-log.jsp" />
			</c:when>
			<c:when test="${subPath eq 'edit-log'}">
				<jsp:include page="/WEB-INF/includes/verify-citations/edit-log.jsp" />
			</c:when>
			<c:when test="${subPath eq 'diff'}">
				<jsp:include page="/WEB-INF/includes/verify-citations/diff.jsp" />
			</c:when>
			<c:when test="${subPath eq 'ranking'}">
				<jsp:include page="/WEB-INF/includes/verify-citations/ranking.jsp" />
			</c:when>
			<c:otherwise>
				<jsp:include page="/WEB-INF/includes/verify-citations/main.jsp" />
			</c:otherwise>
		</c:choose>
	</jsp:body>
</t:template>
