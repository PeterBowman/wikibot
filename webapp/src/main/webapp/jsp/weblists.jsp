<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="title" value="Listy automatyczne" />
<c:set var="subPath" value="${utils:lastPathPart(pageContext.request.servletPath)}" />

<t:template title="${title}" firstHeading="${title}">
	<jsp:attribute name="contentSub">
		<c:if test="${subPath ne 'weblists'}">
			<a href="${pageContext.request.contextPath}/weblists">Powrót do indeksu</a>
		</c:if>
	</jsp:attribute>
	<jsp:body>
		<c:choose>
			<c:when test="${subPath eq 'plwikt-polish-masculine-nouns'}">
				<jsp:include page="/WEB-INF/includes/weblists/plwikt-polish-masculine-nouns.jsp" />
			</c:when>
			<c:otherwise>
				<p>
					Generowane przez automat, okresowo lub na bieżąco odświeżane listy stron.
				</p>
				<ul>
					<li>
						<a href="${pageContext.request.contextPath}/weblists/plwikt-polish-masculine-nouns">
							(plwiktionary) polskie rzeczowniki rodzaju męskiego
						</a>
					</li>
				</ul>
			</c:otherwise>
		</c:choose>
	</jsp:body>
</t:template>
