<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/sql" prefix="sql" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<sql:setDataSource dataSource="jdbc/plwiktionary" var="ds" />

<c:set var="defaultLimit" value="100" />
<c:set var="limit" value="${not empty param.limit ? utils:max(param.limit, 0) : defaultLimit}" />
<c:set var="offset" value="${not empty param.offset ? utils:max(param.offset, 0) : 0}" />

<%-- https://meta.wikimedia.org/wiki/Help:Interwiki_linking --%>
<%-- http://tools.wmflabs.org/tools-info/?listmetap --%>
<c:choose>
	<c:when test="${param.project eq 'plwikisource'}">
		<c:set var="prefix" value="s" />
	</c:when>
	<c:when test="${param.project eq 'plwikiquote'}">
		<c:set var="prefix" value="q" />
	</c:when>
	<c:when test="${param.project eq 'plwikibooks'}">
		<c:set var="prefix" value="b" />
	</c:when>
	<c:when test="${param.project eq 'plwikinews'}">
		<c:set var="prefix" value="n" />
	</c:when>
	<c:when test="${param.project eq 'plwikivoyage'}">
		<c:set var="prefix" value="voy" />
	</c:when>
	<c:when test="${param.project eq 'specieswiki'}">
		<c:set var="prefix" value="species" />
	</c:when>
	<c:when test="${param.project eq 'plwiki'}">
		<c:set var="prefix" value="w" />
	</c:when>
	<c:otherwise>
		<c:set var="errMsg" value="nierozpoznana wartość parametru 'project'" scope="request" />
		<jsp:forward page="/jsp/error.jsp" />
	</c:otherwise>
</c:choose>

<jsp:useBean id="beginQuery" class="java.util.Date"/>

<sql:query var="result" dataSource="${ds}" startRow="${offset}" maxRows="${limit}">
	SELECT
		CONVERT(page_title USING utf8) AS page_title,
		CONVERT(iwl_title USING utf8) AS iwl_title
	FROM
		iwlinks INNER JOIN page ON iwl_from = page_id
	WHERE
		page_namespace = 0 AND
		iwl_prefix = ? AND
		page_title != "" AND
		<c:if test="${not empty param.ignorelc}">
			<%-- TODO: this doesn't seem to work for non-ASCII characters --%>
			STRCMP(
				LEFT(CONVERT(iwl_title USING latin7), 1) COLLATE latin7_general_cs,
				UPPER(LEFT(CONVERT(iwl_title USING latin7), 1)) COLLATE latin7_general_cs
			) < 1 AND
		</c:if>
		NOT EXISTS (
			SELECT NULL
			FROM meta_p.wiki AS meta_wiki
			<%-- Appending COLLATE utf8_general_ci led to empty results --%>
			WHERE iwl_title LIKE CONCAT(meta_wiki.lang, ":%")
		) AND
		NOT EXISTS (
			SELECT NULL
			FROM ${param.project}_p.page AS foreign_page
			WHERE foreign_page.page_title = iwl_title
		)
	ORDER BY
		CONVERT(page_title USING utf8) COLLATE utf8_polish_ci, iwl_title
	<sql:param value="${prefix}" />
</sql:query>

<jsp:useBean id="endQuery" class="java.util.Date"/>
<fmt:formatNumber var="elapsedTime" value="${(endQuery.time - beginQuery.time)/1000}" minFractionDigits="3" />

<c:choose>
	<c:when test="${result.rowCount eq 0}">
		<p>Nie znaleziono pozycji odpowiadających zapytaniu.</p> 
	</c:when>
	<c:otherwise>
		<p>Czas wykonania zapytania: <c:out value="${elapsedTime}" /> sekund.</p>
		<t:paginator limit="${limit}" offset="${offset}" hasNext="${result.limitedByMaxRows}" />
		<ol start="${offset + 1}">
			<c:forEach var="row" items="${result.rows}">
				<li>
					<a href="https://pl.wiktionary.org/wiki/${row.page_title}">
						${fn:replace(row.page_title, '_', ' ')}
					</a>
					→
					<c:choose>
						<c:when test="${fn:length(row.iwl_title) eq 0}">
							<i>pusty link</i>
						</c:when>
						<c:otherwise>
							<a href="https://pl.wiktionary.org/wiki/${prefix}:${row.iwl_title}">
								${fn:replace(row.iwl_title, '_', ' ')}
							</a>
						</c:otherwise>
					</c:choose>
				</li>
			</c:forEach>
		</ol>
		<t:paginator limit="${limit}" offset="${offset}" hasNext="${result.limitedByMaxRows}" />
	</c:otherwise>
</c:choose>
