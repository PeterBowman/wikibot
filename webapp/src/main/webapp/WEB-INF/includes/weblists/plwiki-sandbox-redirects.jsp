<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<fmt:setLocale value="pl_PL" />
<fmt:setTimeZone value="Europe/Warsaw" />

<c:set var="title" value="Strony przeniesione do brudnopisu" />
<c:set var="defaultLimit" value="100" />
<c:set var="limit" value="${not empty param.limit ? utils:max(param.limit, 0) : defaultLimit}" />
<c:set var="offset" value="${not empty param.offset ? utils:max(param.offset, 0) : 0}" />

<t:template title="${title}" firstHeading="${title}">
	<jsp:attribute name="contentSub">
		<a href="${pageContext.request.contextPath}/weblists">Powrót do indeksu</a>
	</jsp:attribute>
	<jsp:body>
		<p>
			Strony przeniesione z przestrzeni głównej do przestrzeni „Wikipedysta:”.
		</p>
		<c:choose>
			<c:when test="${not empty results}">
				<t:paginator limit="${limit}" offset="${offset}" hasNext="${hasNext}" />
				<ul>
					<c:forEach var="item" items="${results}">
						<li>
							<fmt:formatDate value="${item['timestamp']}" pattern="HH:mm, d MMM yyyy" />
							&nbsp;
							<t:linker
								hrefPattern="https://pl.wikipedia.org/$1"
								target="${item['target']}"
								testMissingPage="${not item['targetExists']}"
								display="${item['targetDisplay']}" />
							←
							<t:linker
								hrefPattern="https://pl.wikipedia.org/$1"
								target="${item['source']}"
								testMissingPage="${not item['sourceExists']}" />
						</li>
					</c:forEach>
				</ul>
				<t:paginator limit="${limit}" offset="${offset}" hasNext="${hasNext}" />
			</c:when>
			<c:otherwise>
				<p>Brak wyników.</p>
			</c:otherwise>
		</c:choose>
	</jsp:body>
</t:template>
