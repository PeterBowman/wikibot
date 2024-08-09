<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.functions" prefix="fn" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="title" value="Páginas huérfanas" />
<c:set var="defaultLimit" value="500" />

<c:set var="limit" value="${not empty param.limit ? utils:max(param.limit, 0) : defaultLimit}" />
<c:set var="offset" value="${not empty param.offset ? utils:max(param.offset, 0) : 0}" />

<c:set var="columnThreshold" value="25" />
<c:set var="paginatorLimits" value="100,250,500,1000,2000,5000" />

<t:template title="${title}" firstHeading="${title}" enableJS="true">
    <jsp:attribute name="head">
        <script>
            window.lonelyPages = {
                defaultLimit: ${defaultLimit},
                limit: ${limit},
                offset: ${offset},
                columnThreshold: ${columnThreshold}
            };
        </script>
        <script src="${pageContext.request.contextPath}/scripts/eswikt-lonely-pages.js"></script>
    </jsp:attribute>
    <jsp:attribute name="contentSub">
        <a href="${pageContext.request.contextPath}/weblists">Retorno al índice</a>
    </jsp:attribute>
    <jsp:body>
        <p>
            Complemento para
            <a href="https://es.wiktionary.org/wiki/Especial:P%C3%A1ginasHu%C3%A9rfanas" target="_blank">Especial:PáginasHuérfanas</a>.
            Permite visualizar todas las páginas no enlazadas ni transcluidas en ninguna otra,
            sin el límite de 5000 resultados impuesto por el software de MediaWiki.
        </p>
        <div id="lonely-pages-content">
            <p>
                Última actualización: <span id="lonely-pages-timestamp">${timestamp}</span>.
                <c:if test="${total ne 0}">
                    El informe contiene <strong id="lonely-pages-total">${total}</strong> resultados.
                </c:if>
            </p>
            <c:choose>
                <c:when test="${not empty results}">
                    <p id="lonely-pages-summary">
                        Abajo se muestran hasta <strong id="lonely-pages-limit">${limit}</strong> resultados
                        entre el n.º <strong id="lonely-pages-start">${offset + 1}</strong>
                        y el n.º <strong id="lonely-pages-end">${utils:min(offset + limit, total)}</strong>.
                    </p>
                    <t:paginator limit="${limit}" offset="${offset}" hasNext="${total gt offset + limit}"
                        limits="${paginatorLimits}" />
                    <ol id="lonely-pages-results" start="${offset + 1}"
                        <c:if test="${fn:length(results) gt columnThreshold}">class="column-list"</c:if>>
                        <c:forEach var="item" items="${results}">
                            <li>
                                <t:linker hrefPattern="https://es.wiktionary.org/$1" target="${item}" />
                            </li>
                        </c:forEach>
                    </ol>
                    <t:paginator limit="${limit}" offset="${offset}" hasNext="${total gt offset + limit}"
                        limits="${paginatorLimits}" />
                </c:when>
                <c:otherwise>
                    <p>
                        No hay resultados que mostrar.
                    </p>
                </c:otherwise>
            </c:choose>
        </div>
    </jsp:body>
</t:template>
