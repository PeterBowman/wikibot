<%@ tag description="Print the categories a morphem (or morphems) belongs to" pageEncoding="UTF-8"
	trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/sql" prefix="sql" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<sql:query var="result" dataSource="jdbc/EomBacklinks">
	SELECT
		DISTINCT(morphem) AS morphem,
		type
	FROM
		morfeo
	WHERE
		<c:forTokens var="item" items="${param.morphem}" delims="|" varStatus="status">
			<c:set var="trimmed" value="${fn:trim(item)}" />
			<c:if test="${fn:length(trimmed) ne 0}">
				morphem = '${fn:replace(trimmed, "'", "''")}'
				<c:if test="${not status.last}">OR</c:if>
			</c:if>
			<c:remove var="trimmed" />
		</c:forTokens>
</sql:query>

<c:set var="missingPage" value="0" />
<c:set var="missingSection" value="1" />
<c:set var="normalMorphem" value="2" />
<c:set var="prefixMorphem" value="4" />
<c:set var="suffixMorphem" value="8" />
<c:set var="grammaticalEnding" value="16" />
<c:set var="unknownMorphem" value="32" />

<c:forEach var="row" items="${result.rows}" varStatus="status" >
	<c:set var="normalized" value="${fn:replace(row.morphem, '_', ' ')}" />
	<strong>
		<%-- Cast integer index as string, don't use 'value' attribute --%>
		<c:set var="index">${status.index}</c:set>
		<c:choose>
			<c:when test="${row.type eq missingPage}">
				<c:set var="href" value="w/index.php?action=edit&redlink=1&title=" />
				<c:set var="title" value="(strona nie istnieje)" />
				<c:set var="classVar" value="new" />
			</c:when>
			<c:when test="${row.type eq missingSection}">
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
		</a><%--
		--%><c:remove var="index" /><%--
		--%><c:remove var="href" /><%--
		--%><c:remove var="title" /><%--
		--%><c:remove var="classVar" /><%--
	--%></strong><%--
	--%><c:if test="${row.type ne missingPage and row.type ne missingSection and row.type ne unknownMorphem}">
		<c:set var="isFirst" value="true" />
		(<c:if test="${utils:bitCompare(row.type, normalMorphem)}"><%--
			--%><c:set var="isFirst" value="${false}" /><%--
			--%>morfem<%--
		--%></c:if>
		<c:if test="${utils:bitCompare(row.type, prefixMorphem)}">
			<c:if test="${not isFirst}">,</c:if>
			<c:set var="isFirst" value="${false}" /><%--
			--%>morfem przedrostkowy<%--
		--%></c:if>
		<c:if test="${utils:bitCompare(row.type, suffixMorphem)}">
			<c:if test="${not isFirst}">,</c:if>
			<c:set var="isFirst" value="${false}" /><%--
			--%>morfem przyrostkowy<%--
		--%></c:if>
		<c:if test="${utils:bitCompare(row.type, grammaticalEnding)}">
			<c:if test="${not isFirst}">,</c:if>
			końcówka gramatyczna<%--
		--%></c:if>)<%--
		--%><c:remove var="isFirst" />
		</c:if>
		<c:if test="${not status.last}">, </c:if>
	<c:remove var="normalized" />
</c:forEach>
