<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/sql" prefix="sql" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>

<c:set var="servletPath" value="${pageContext.request.servletPath}" />
<c:set var="basePath" value="/verify-citations" />

<t:template title="Weryfikacja typografii przypisów" firstHeading="Weryfikacja typografii przypisów">
	<jsp:attribute name="cactions">
		<t:cactions targetPath="${basePath}" currentPath="${servletPath}" label="strona główna" />
		<t:cactions targetPath="${basePath}/entries" currentPath="${servletPath}" label="lista" />
		<t:cactions targetPath="${basePath}/review-log" currentPath="${servletPath}" label="oznaczanie" />
		<t:cactions targetPath="${basePath}/edit-log" currentPath="${servletPath}" label="edycja" />
		<t:cactions targetPath="${basePath}/diff" currentPath="${servletPath}" label="diff" />
		<t:cactions targetPath="${basePath}/ranking" currentPath="${servletPath}" label="ranking" />
	</jsp:attribute>
	<jsp:attribute name="headerNotice">
		<t:ambox type="warning">
			Strona w trakcie budowy.
		</t:ambox>
	</jsp:attribute>
	<jsp:body>
		<c:choose>
			<c:when test="">
				
			</c:when>
			<c:otherwise>
				<%-- TODO: include action or directive? --%>
				<%@ include file="/WEB-INF/includes/verify-citations/main.jsp" %>
			</c:otherwise>
		</c:choose>
	</jsp:body>
</t:template>
