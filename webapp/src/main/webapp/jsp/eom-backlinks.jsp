<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/sql" prefix="sql" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="heading" value="Linkujące morfemów esperanto" />

<sql:query var="lastUpdate" dataSource="jdbc/tools-db">
	SELECT
		CONVERT(timestamp USING utf8) AS timestamp
	FROM
		s52584__plwikt_common.execution_log
	WHERE
		type = 'tasks.plwikt.MorfeoDatabase';
</sql:query>

<t:template title="${heading}" firstHeading="${heading}" enableJS="true">
	<jsp:attribute name="head">
		<link href="${pageContext.request.contextPath}/styles/eom-backlinks.css" type="text/css"
			rel="stylesheet">
	</jsp:attribute>
	<jsp:body>
		<p>
			Spis haseł esperanto, w których występuje wskazany morfem w szablonie <code>{{morfeo}}</code>.
			Można też wyszukać wspólne wystąpienia dwóch lub więcej morfemów, oddzielając je znakiem
			<code>|</code> w polu wyszukiwania (na przykład <a href="?morphem=o|patr">„o|patr”</a>).
			Zostaw to pole niewypełnione, aby wyświetlić wszystkie morfemy użyte w hasłach esperanto.
		</p>
		<p>
			Ostatnia aktualizacja bazy danych: ${utils:formatTimestamp(lastUpdate.rows[0].timestamp, 'ECT', 'pl')}.
		</p>
		<form action="${pageContext.request.contextPath}${pageContext.request.servletPath}" method="GET">
			<fieldset>
				<legend>Wyszukiwarka morfemów</legend>
				<label for="morphem">Morfem(y):</label>
				<input id="morphem-input" name="morphem" size="20" value="${param.morphem}" multiple>
				<input type="submit" value="Pokaż" >
			</fieldset>
		</form>
		<c:if test="${param.morphem ne null}">
			<jsp:include page="/WEB-INF/includes/eom-backlinks-query.jsp" />
		</c:if>
	</jsp:body>
</t:template>
