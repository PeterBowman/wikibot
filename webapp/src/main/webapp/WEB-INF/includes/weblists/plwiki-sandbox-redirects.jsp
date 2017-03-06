<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

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
			Strony przeniesione z przestrzeni głównej do przestrzeni „Wikipedysta:” z opisem
			„artykuł należy dopracować”.
		</p>
		<c:choose>
			<c:when test="${not empty results}">
				<t:paginator limit="${limit}" offset="${offset}" hasNext="${hasNext}" />
				<ol start="${offset + 1}">
					<c:forEach var="item" items="${results}">
						<li>
							<t:linker hrefPattern="https://pl.wikipedia.org/$1" target="${item[1]}" />
							←
							<t:linker hrefPattern="https://pl.wikipedia.org/$1" target="${item[0]}" />
						</li>
					</c:forEach>
				</ol>
				<t:paginator limit="${limit}" offset="${offset}" hasNext="${hasNext}" />
			</c:when>
			<c:otherwise>
				<p>Brak wyników.</p>
			</c:otherwise>
		</c:choose>
	</jsp:body>
</t:template>
