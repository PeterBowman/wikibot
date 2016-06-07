<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>

<c:set var="heading" value="Zerwane linki do siostrzanych" />

<t:template title="${heading}" firstHeading="${heading}">
	<p>
		Wystąpienia linków w przestrzeni głównej polskiego Wikisłownika, które kierują do
		nieistniejących stron w projektach siostrzanych.
	</p>
	<p>
		Wyszukiwarka nie rozpoznaje haseł w projekcie docelowym, jeżeli link został zapisany
		początkową, małą literą (na przykład <code>[[s:link]]</code> zamiast <code>[[s:Link]]</code>).
		Nie potrafi też interpretować przekierowań między projektami za pośrednictwem prefiksów
		interwiki (na przykład <code>[[q:de:Link]]</code> → https://de.wikiquote.org/wiki/<i>Link</i>).
		Zestawienie nie obejmuje linków zewnętrznych (https://pl.<i>projekt</i>.org/wiki/<i>Link</i>).
	</p>
	<p>
		<strong>Uwaga:</strong> niektóre zapytania mogą zająć kilka minut w zależności od rozmiaru bazy
		danych (szczególnie w przypadku Wikipedii). 
	</p>
	<form action="${pageContext.request.contextPath}${pageContext.request.servletPath}" method="GET">
		<%-- TODO: don't use this if the 'project' parameter has been changed --%>
		<c:if test="${not empty param.limit}">
			<input type="hidden" name="limit" value="${param.limit}">
		</c:if>
		<c:if test="${not empty param.offset}">
			<input type="hidden" name="offset" value="${param.offset}">
		</c:if>
		<fieldset>
			<legend>Zerwane linki</legend>
			<t:select parameter="project" label="Projekt" defaultOption="Wybierz opcję">
				<jsp:attribute name="plwikisource">Wikizródła</jsp:attribute>
				<jsp:attribute name="plwikiquote">Wikicytaty</jsp:attribute>
				<jsp:attribute name="plwiki">Wikipedia</jsp:attribute>
				<jsp:attribute name="plwikibooks">Wikibooks</jsp:attribute>
				<jsp:attribute name="plwikinews">Wikinews</jsp:attribute>
				<jsp:attribute name="plwikivoyage">Wikipodróże</jsp:attribute>
				<jsp:attribute name="specieswiki">Wikispecies</jsp:attribute>
			</t:select>
			<span class="mw-input-with-label">
				<input type="checkbox" id="ignorelc" name="ignorelc"
					<c:if test="${not empty param.ignorelc}">checked</c:if>>
				<label for="ignorelc">ukryj strony docelowe zaczynające się od małej litery</label>
			</span>
			<span class="mw-input-with-label">
				<input type="checkbox" id="hideprefixes" name="hideprefixes"
					<c:if test="${not empty param.hideprefixes}">checked</c:if>>
				<label for="hideprefixes">ukryj prefiksy interwiki</label>
			</span>
			<input type="submit" value="Pokaż" >
		</fieldset>
	</form>
	<c:if test="${not empty param.project}">
		<jsp:include page="/WEB-INF/includes/broken-iw-links-query.jsp" />
	</c:if>
</t:template>
