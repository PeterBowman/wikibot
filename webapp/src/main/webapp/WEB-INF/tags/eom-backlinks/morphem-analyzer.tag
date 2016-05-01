<%@ tag description="Print the categories a morphem (or morphems) belongs to" pageEncoding="UTF-8"
	trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/sql" prefix="sql" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>
<%@ taglib uri="tld/utils" prefix="utils" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>

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
	ORDER BY
		morphem COLLATE utf8mb4_general_ci;
</sql:query>

<c:set var="missingPage" value="0" />
<c:set var="missingSection" value="1" />
<c:set var="normalMorphem" value="2" />
<c:set var="prefixMorphem" value="4" />
<c:set var="suffixMorphem" value="8" />
<c:set var="grammaticalEnding" value="16" />
<c:set var="unknownMorphem" value="32" />

<c:forEach var="row" items="${result.rows}" varStatus="status" >
	<strong>
		<t:linker hrefPattern="https://pl.wiktionary.org/$1#eom" target="${row.morphem}"
			testMissingPage="${row.type eq missingPage}"
			testMissingSection="${row.type eq missingSection}" sectionName="esperanto (morfem)" />
	</strong><%--
	--%><c:if test="${row.type ne missingPage and row.type ne missingSection and row.type ne unknownMorphem}">
		<c:set var="isFirst" value="true" />
		(<c:if test="${utils:bitCompare(row.type, normalMorphem)}">
			<c:set var="isFirst" value="${false}" />morfem</c:if>
		<c:if test="${utils:bitCompare(row.type, prefixMorphem)}">
			<c:if test="${not isFirst}">,${" "}</c:if>
			<c:set var="isFirst" value="${false}" />morfem przedrostkowy</c:if>
		<c:if test="${utils:bitCompare(row.type, suffixMorphem)}">
			<c:if test="${not isFirst}">,${" "}</c:if>
			<c:set var="isFirst" value="${false}" />morfem przyrostkowy</c:if>
		<c:if test="${utils:bitCompare(row.type, grammaticalEnding)}">
			<c:if test="${not isFirst}">,${" "}</c:if>
			końcówka gramatyczna</c:if>)<%--
	--%></c:if><c:if test="${not status.last}">, </c:if>
</c:forEach>
