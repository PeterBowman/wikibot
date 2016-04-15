<%@ tag description="Output morphem formatter" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ attribute name="morphems" required="true" %>
<%@ attribute name="types" required="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>

<jsp:useBean id="targetMap" class="java.util.HashMap" />
<jsp:useBean id="missingPageMap" class="java.util.HashMap" />
<jsp:useBean id="missingSectionMap" class="java.util.HashMap" />

<c:forTokens var="target" items="${param.morphem}" delims="|">
	<c:set var="trimmed" value="${fn:trim(target)}" />
	<c:if test="${fn:length(trimmed) ne 0}">
		<c:set target="${targetMap}" property="${trimmed}" value="${true}" />
	</c:if>
	<c:remove var="trimmed" />
</c:forTokens>

<c:forTokens var="type" items="${types}" delims="|" varStatus="status">
	<c:choose>
		<c:when test="${type eq 0}">
			<c:set target="${missingPageMap}" property="${status.index}" value="${true}" />
		</c:when>
		<c:when test="${type eq 1}">
			<c:set target="${missingSectionMap}" property="${status.index}" value="${true}" />
		</c:when>
	</c:choose>
</c:forTokens>

<c:forTokens var="morphem" items="${morphems}" delims="|" varStatus="status">
	<c:set var="normalized" value="${fn:replace(morphem, '_', ' ')}" />
	<c:choose>
		<c:when test="${targetMap[normalized]}">
			<span class="eom-backlinks-hl">${normalized}</span><c:if test="${not status.last}">•</c:if>
		</c:when>
		<c:otherwise>
			<%-- Cast integer index as string, don't use 'value' attribute --%>
			<c:set var="index">${status.index}</c:set>
			<c:choose>
				<c:when test="${missingPageMap[index]}">
					<c:set var="href" value="w/index.php?action=edit&redlink=1&title=" />
					<c:set var="title" value="(strona nie istnieje)" />
					<c:set var="classVar" value="new" />
				</c:when>
				<c:when test="${missingSectionMap[index]}">
					<c:set var="href" value="wiki/" />
					<c:set var="title" value="(brak sekcji esperanto (morfem))" />
					<c:set var="classVar" value="false-blue" />
				</c:when>
				<c:otherwise>
					<c:set var="href" value="wiki/" />
				</c:otherwise>
			</c:choose>
			<a href="https://pl.wiktionary.org/${href}${fn:escapeXml(normalized)}#eom" 
				title='${normalized}<c:if test="${not empty title}">${" "}${title}</c:if>'
				<c:if test="${not empty classVar}">class="${classVar}"</c:if>><%--
				--%>${normalized}
			</a><c:if test="${not status.last}">•</c:if>
			<c:remove var="index" />
			<c:remove var="href" />
			<c:remove var="title" />
			<c:remove var="classVar" />
		</c:otherwise>
	</c:choose>
	<c:remove var="normalized" />
</c:forTokens>
