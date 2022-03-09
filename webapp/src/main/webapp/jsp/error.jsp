<%@ page language="java" pageEncoding="UTF-8" isErrorPage="true" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c"%>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>

<c:set var="code" value="${pageContext.errorData.statusCode}" />

<t:template firstHeading="Błąd: ${not empty requestScope.errMsg ? requestScope.errMsg : code}" title="Błąd">
	<c:choose>
		<c:when test="${not empty requestScope.errMsg}">
			<p>
				Wróć do poprzedniej strony lub przejdź do
				<a href="${pageContext.request.contextPath}">strony głównej</a>.
			</p>
		</c:when>
		<c:when test="${code eq 404}">
			<p>
				Podana strona nie istnieje.
				Przejdź do <a href="${pageContext.request.contextPath}">strony głównej</a>.
				${param.hola}
			</p>
		</c:when>
		<c:otherwise>
			<p style="white-space: pre-wrap;"><c:out value="${pageContext.exception}" /></p>
			<p>
				<c:forEach var="trace" items="${pageContext.exception.stackTrace}">
					<p>${trace}</p>
				</c:forEach>
			</p>
		</c:otherwise>
	</c:choose>
</t:template>