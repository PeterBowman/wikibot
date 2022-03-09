<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>

<c:set var="title" value="Listy automatyczne" />

<t:template title="${title}" firstHeading="${title}">
	<p>
		Generowane przez automat, okresowo lub na bieżąco odświeżane listy stron.
	</p>
	<ul>
		<li>
			<a href="${pageContext.request.contextPath}/weblists/plwikt-polish-masculine-nouns">
				(plwiktionary) polskie rzeczowniki rodzaju męskiego
			</a>
		</li>
		<li>
            <a href="${pageContext.request.contextPath}/weblists/plwikt-missing-plwiki-backlinks">
                (plwiktionary) brak linku zwrotnego do Wikisłownika w artykułach polskojęzycznej Wikipedii
            </a>
        </li>
        <li>
            <a href="${pageContext.request.contextPath}/weblists/plwikt-missing-polish-examples">
                (plwiktionary) brak przykładu w polskim haśle, lecz istnieją linkujące
            </a>
        </li>
		<li>
			<a href="${pageContext.request.contextPath}/weblists/plwiki-sandbox-redirects">
				(plwiki) strony przeniesione do brudnopisu
			</a>
		</li>
		<li>
			<a href="${pageContext.request.contextPath}/weblists/plwikinews-missing-plwiki-backlinks">
				(plwikinews) artykuły bez linku zwrotnego z Wikipedii
			</a>
		</li>
		<li>
			<a href="${pageContext.request.contextPath}/weblists/eswikt-lonely-pages">
				(eswiktionary) complemento para Especial:PáginasHuérfanas
			</a>
		</li>
	</ul>
</t:template>
