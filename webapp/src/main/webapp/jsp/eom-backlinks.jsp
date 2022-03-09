<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/sql" prefix="sql" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="heading" value="Linkujące morfemów esperanto" />

<sql:query var="lastUpdate" dataSource="jdbc/plwikt-common">
    SELECT
        timestamp
    FROM
        execution_log
    WHERE
        type = 'tasks.plwikt.MorfeoDatabase';
</sql:query>

<t:template title="${heading}" firstHeading="${heading}" enableJS="true">
    <jsp:attribute name="head">
        <link href="styles/suggestions.css" type="text/css" rel="stylesheet">
        <link href="styles/tipsy.css" type="text/css" rel="stylesheet">
        <link href="styles/eom-backlinks.css" type="text/css" rel="stylesheet">
        <script src="scripts/suggestions.js"></script>
        <script src="scripts/tipsy.js"></script>
        <script src="scripts/definition-popups.js"></script>
        <script src="scripts/eom-backlinks.js"></script>
    </jsp:attribute>
    <jsp:body>
        <p>
            Spis haseł esperanto, w których występuje wskazany morfem w szablonie <code>{{morfeo}}</code>.
            Można też wyszukać wspólne wystąpienia dwóch lub więcej morfemów, oddzielając je znakiem
            <code>|</code> w polu wyszukiwania (na przykład <a href="?morphem=o%7Cpatr">„o|patr”</a>).
            Zostaw to pole niewypełnione, aby wyświetlić wszystkie morfemy użyte w hasłach esperanto.
            Najechanie kursorem na dowolny link poskutkuje wyświetleniem okienka z wyciągiem pola znaczeń
            odpowiedniego hasła w Wikisłowniku.
        </p>
        <p>
            <%-- It's important to set this values BEFORE the call to fmt:formatDate. --%>
            <fmt:setLocale value="pl_PL" />
            <fmt:setTimeZone value="Europe/Madrid" />
            <fmt:formatDate var="date" value="${lastUpdate.rows[0].timestamp}" pattern="HH:mm, d MMM yyyy (z)" />
            Ostatnia aktualizacja bazy danych: ${date}.
        </p>
        <form action="${pageContext.request.contextPath}${pageContext.request.servletPath}" method="GET">
            <fieldset>
                <legend>Wyszukiwarka morfemów</legend>
                <span class="mw-input-with-label">
                    <label for="morphem">Morfem(y):</label>
                    <input id="morphem-input" name="morphem" size="20" value="${param.morphem}">
                </span>
                <input type="submit" value="Pokaż" >
            </fieldset>
        </form>
        <c:if test="${param.morphem ne null}">
            <jsp:include page="/WEB-INF/includes/eom-backlinks-query.jsp" />
        </c:if>
    </jsp:body>
</t:template>
