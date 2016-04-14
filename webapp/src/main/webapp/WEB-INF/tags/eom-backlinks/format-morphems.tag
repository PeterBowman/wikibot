<%@ tag description="Output morphem formatter" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ attribute name="morphems" required="true" %>
<%@ attribute name="targets" required="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>

<jsp:useBean id="targetMap" class="java.util.HashMap" />

<c:forTokens var="target" items="${targets}" delims="|">
	<c:set var="trimmed" value="${fn:trim(target)}" />
	<c:if test="${fn:length(trimmed) ne 0}">
		<c:set target="${targetMap}" property="${trimmed}" value="${true}" />
	</c:if>
</c:forTokens>

<c:forTokens var="morphem" items="${morphems}" delims="|" varStatus="status">
	<c:set var="trimmed" value="${fn:trim(morphem)}" />
	<c:choose>
		<c:when test="${targetMap[trimmed]}">
			<span class="eom-backlinks-hl">${trimmed}</span><c:if test="${not status.last}">•</c:if>
		</c:when>
		<c:otherwise>
			<a href="https://pl.wiktionary.org/wiki/${fn:escapeXml(trimmed)}#eom"><%--
				--%>${fn:replace(trimmed, '_', ' ')}
			</a><c:if test="${not status.last}">•</c:if>
		</c:otherwise>
	</c:choose>
</c:forTokens>
