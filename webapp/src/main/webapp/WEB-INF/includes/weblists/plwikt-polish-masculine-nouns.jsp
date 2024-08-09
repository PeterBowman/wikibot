<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.sql" prefix="sql" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="title" value="Polskie rzeczowniki rodzaju męskiego" />

<c:set var="defaultLimit" value="250" />
<c:set var="columnThreshold" value="25" />

<c:set var="limit" value="${not empty param.limit ? utils:max(param.limit, 0) : defaultLimit}" />
<c:set var="offset" value="${not empty param.offset ? utils:max(param.offset, 0) : 0}" />

<sql:query var="result" dataSource="jdbc/plwiktionary-web" startRow="${offset}" maxRows="${limit}">
    SELECT
        CONVERT(page_title USING utf8) AS title
    FROM
        page
            INNER JOIN categorylinks AS c_all ON
                c_all.cl_from = page_id AND
                c_all.cl_to = "Język_polski_-_rzeczowniki_rodzaju_męskiego"
            LEFT JOIN categorylinks AS c_mrz ON
                c_mrz.cl_from = page_id AND
                c_mrz.cl_to = "Język_polski_-_rzeczowniki_rodzaju_męskorzeczowego"
            LEFT JOIN categorylinks AS c_mzw ON
                c_mzw.cl_from = page_id AND
                c_mzw.cl_to = "Język_polski_-_rzeczowniki_rodzaju_męskozwierzęcego"
            LEFT JOIN categorylinks AS c_mos ON
                c_mos.cl_from = page_id AND
                c_mos.cl_to = "Język_polski_-_rzeczowniki_rodzaju_męskoosobowego"
    WHERE
        page_namespace = 0 AND
        page_is_redirect = 0 AND
        c_mrz.cl_to IS NULL AND
        c_mzw.cl_to IS NULL AND
        c_mos.cl_to IS NULL
    ORDER BY
        CONVERT(page_title USING utf8) COLLATE utf8_polish_ci;
</sql:query>

<t:template title="${title}" firstHeading="${title}" enableJS="true">
    <jsp:attribute name="head">
        <link href="${pageContext.request.contextPath}/styles/tipsy.css" type="text/css" rel="stylesheet">
        <script src="${pageContext.request.contextPath}/scripts/tipsy.js"></script>
        <script src="${pageContext.request.contextPath}/scripts/definition-popups.js"></script>
        <script src="${pageContext.request.contextPath}/scripts/plwikt-polish-masculine-nouns.js"></script>
    </jsp:attribute>
    <jsp:attribute name="contentSub">
        <a href="${pageContext.request.contextPath}/weblists">Powrót do indeksu</a>
    </jsp:attribute>
    <jsp:body>
        <p>
            Hasła polskie opisujące rzeczowniki, zawierające ciąg znaków <code>rodzaj męski</code>
            w polu <strong>znaczenia</strong>. Należy doprecyzować rodzaj, wstawiając w miejsce drugiego
            członu wyraz <code>męskorzeczowy</code>, <code>męskozwierzęcy</code> lub <code>męskoosobowy</code>.
            Najechanie kursorem na dowolny link poskutkuje wyświetleniem okienka z wyciągiem pola znaczeń
            odpowiedniego hasła w Wikisłowniku.
            Zobacz też:
            <a href="https://pl.wiktionary.org/wiki/Kategoria:J%C4%99zyk_polski_-_rzeczowniki_rodzaju_m%C4%99skiego" target="_blank">
                Kategoria:Język polski - rzeczowniki rodzaju męskiego</a>.
        </p>
        <p>
            <strong>Uwaga:</strong> lista może nie uwzględniać najświeższych edycji z powodu opóźnienia
            w aktualizacji bazy danych.
        </p>
        <t:paginator limit="${limit}" offset="${offset}" hasNext="${result.limitedByMaxRows}" />
        <ol start="${offset + 1}" <c:if test="${result.rowCount gt columnThreshold}">class="column-list"</c:if>>
            <c:forEach var="row" items="${result.rows}">
                <li>
                    <t:linker hrefPattern="https://pl.wiktionary.org/$1#pl" target="${row.title}" sectionName="polski" />
                </li>
            </c:forEach>
        </ol>
        <t:paginator limit="${limit}" offset="${offset}" hasNext="${result.limitedByMaxRows}" />
    </jsp:body>
</t:template>
