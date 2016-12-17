<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="title" value="Páginas huérfanas" />
<c:set var="defaultLimit" value="500" />

<c:set var="limit" value="${not empty param.limit ? utils:max(param.limit, 0) : defaultLimit}" />
<c:set var="offset" value="${not empty param.offset ? utils:max(param.offset, 0) : 0}" />

<c:set var="columnThreshold" value="25" />

<t:template title="${title}" firstHeading="${title}" enableJS="false">
	<jsp:attribute name="headerNotice">
		<t:ambox type="warning">
			Esta herramienta se encuentra en fase de pruebas.
		</t:ambox>
	</jsp:attribute>
	<jsp:body>
		<p>
			Complemento para
			<a href="https://es.wiktionary.org/wiki/Especial:P%C3%A1ginasHu%C3%A9rfanas" target="_blank">Especial:PáginasHuérfanas</a>.
			Permite visualizar todas las páginas no enlazadas ni transcluidas en ninguna otra,
			sin el límite de 5000 resultados impuesto por el software de MediaWiki.
		</p>
		<fmt:formatDate var="updated" value="${timestamp}" pattern="HH:mm, d MMM yyyy (z)" />
		<p>
			Última actualización: ${updated}.
			<c:if test="${total ne 0}">
				El informe contiene <strong>${total}</strong> resultados.
			</c:if>
		</p>
		<c:choose>
			<c:when test="${not empty results}">
				<p>
					Abajo se muestran hasta <strong>${limit}</strong> resultados entre el n.º
					<strong>${offset + 1}</strong> y el n.º <strong>${utils:min(offset + limit, total)}</strong>.
				</p>
				<t:paginator limit="${limit}" offset="${offset}" hasNext="${total gt offset + limit}" />
				<ol start="${offset + 1}" <c:if test="${fn:length(results) gt columnThreshold}">class="column-list"</c:if>>
					<c:forEach var="item" items="${results}">
						<li>
							<t:linker hrefPattern="https://es.wiktionary.org/$1" target="${item}" />
						</li>
					</c:forEach>
				</ol>
				<t:paginator limit="${limit}" offset="${offset}" hasNext="${total gt offset + limit}" />
			</c:when>
			<c:otherwise>
				<p>
					No hay resultados para este informe.
				</p>
			</c:otherwise>
		</c:choose>
	</jsp:body>
</t:template>
