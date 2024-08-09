<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.functions" prefix="fn" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="title" value="Brak Wikinews w Wikipedii" />
<c:set var="defaultLimit" value="500" />
<c:set var="limit" value="${not empty param.limit ? utils:max(param.limit, 0) : defaultLimit}" />
<c:set var="offset" value="${not empty param.offset ? utils:max(param.offset, 0) : 0}" />
<c:set var="columnThreshold" value="25" />
<c:set var="paginatorLimits" value="100,250,500,1000,2000,5000" />

<t:template title="${title}" firstHeading="${title}" enableJS="true">
    <jsp:attribute name="head">
        <script>
            window.plwikinewsMissingPlwikiBacklinks = {
                defaultLimit: ${defaultLimit},
                limit: ${limit},
                offset: ${offset},
                columnThreshold: ${columnThreshold}
            };
        </script>
        <script src="${pageContext.request.contextPath}/scripts/plwikinews-missing-plwiki-backlinks.js"></script>
    </jsp:attribute>
    <jsp:attribute name="contentSub">
        <a href="${pageContext.request.contextPath}/weblists">Powrót do indeksu</a>
    </jsp:attribute>
    <jsp:body>
        <p>
            Spis artykułów w polskojęzycznej wersji Wikinews, które nie są linkowane z przestrzeni głównej Wikipedii
            ani bezpośrednio, ani za pośrednictwem kategorii Wikinews, w której są zawarte.
        </p>
        <div id="plwikinews-missing-plwiki-backlinks-content">
            <c:choose>
                <c:when test="${not empty results}">
                    <p id="plwikinews-missing-plwiki-backlinks-summary">
                        Lista zawiera <strong id="plwikinews-missing-plwiki-backlinks-total">${total}</strong>
                        ${utils:makePluralPL(total, 'wynik', 'wyniki', 'wyników')}.
                        Poniżej wyświetlono co najwyżej <strong id="plwikinews-missing-plwiki-backlinks-limit">${limit}</strong>
                        ${utils:makePluralPL(limit, 'wynik', 'wyniki', 'wyników')}
                        w zakresie od <strong id="plwikinews-missing-plwiki-backlinks-start">${offset + 1}</strong>
                        do <strong id="plwikinews-missing-plwiki-backlinks-end">${utils:min(offset + limit, total)}</strong>.
                    </p>
                    <t:paginator limit="${limit}" offset="${offset}" hasNext="${total gt offset + limit}"
                        limits="${paginatorLimits}" />
                    <ol id="plwikinews-missing-plwiki-backlinks-results" start="${offset + 1}"
                        <c:if test="${fn:length(results) gt columnThreshold}">class="column-list"</c:if>>
                        <c:forEach var="item" items="${results}">
                            <li>
                                <t:linker hrefPattern="https://pl.wikinews.org/$1" target="${item}" />
                            </li>
                        </c:forEach>
                    </ol>
                    <t:paginator limit="${limit}" offset="${offset}" hasNext="${total gt offset + limit}"
                        limits="${paginatorLimits}" />
                </c:when>
                <c:otherwise>
                    <p>Brak wyników.</p>
                </c:otherwise>
            </c:choose>
        </div>
    </jsp:body>
</t:template>
