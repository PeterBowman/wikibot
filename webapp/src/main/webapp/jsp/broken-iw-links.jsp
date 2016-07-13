<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>

<c:set var="heading" value="Zerwane linki do siostrzanych" />

<t:template title="${heading}" firstHeading="${heading}" enableJS="true">
	<jsp:attribute name="head">
		<script src="scripts/broken-iw-links.js"></script>
	</jsp:attribute>
	<jsp:body>
		<p>
			Wystąpienia linków interwiki w polskim Wikisłowniku, które kierują do nieistniejących stron,
			przekierowań lub ujednoznacznień w projektach siostrzanych.
		</p>
		<p>
			Narzędzie wyszukuje linki w formacie <code>[[<i>prefiks</i>:<i>tytuł_strony</i>]]</code>,
			np. <code>[[s:Test]]</code>. Potrafi rozwinąć prefiksy zagnieżdżone
			(np. <code>[[s:q:w:de:Test]]</code>). Nie obsługuje linków o pustym tytule strony
			(kierujących do strony głównej projektu docelowego) lub zawierającym niedozwolone znaki.
			Wykrywa zarówno te linki, które występują w wikikodzie, jak i transkludowane przez szablony. 
		</p>
		<p>
			<strong>Uwaga:</strong> niektóre zapytania mogą zająć kilkadziesiąt sekund w zależności od
			rozmiaru bazy danych (szczególnie w przypadku Wikipedii). 
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
				<span class="mw-input-with-label">
					<label for="targetdb">Projekt:</label>
					<input id="targetdb" name="targetdb" size="20" value="${param.targetdb}">
				</span>
				<p>
					Opcje:
					<span class="mw-input-with-label">
						<input type="checkbox" id="onlymainnamespace" name="onlymainnamespace"
							<c:if test="${not empty param.onlymainnamespace}">checked</c:if>>
						<label for="onlymainnamespace">tylko przestrzeń główna</label>
					</span>
					<span class="mw-input-with-label">
						<input type="checkbox" id="showredirects" name="showredirects"
							<c:if test="${not empty param.showredirects}">checked</c:if>>
						<label for="showredirects">uwzględnij
							<span style="color: green;">przekierowania</span>
						</label>
					</span>
					<span class="mw-input-with-label">
						<input type="checkbox" id="showdisambigs" name="showdisambigs"
							<c:if test="${not empty param.showdisambigs}">checked</c:if>>
						<label for="showdisambigs">uwzględnij
							<span style="color: maroon;">strony ujednoznaczniające</span>
						</label>
					</span>
					<span class="mw-input-with-label">
						<input type="checkbox" id="includecreated" name="includecreated"
							<c:if test="${not empty param.includecreated}">checked</c:if>>
						<label for="includecreated">pokaż wszystkie</label>
					</span>
				</p>
				<input type="submit" value="Szukaj" >
			</fieldset>
		</form>
		<c:if test="${not empty param.targetdb}">
			<jsp:include page="/broken-iw-links/query" />
		</c:if>
	</jsp:body>
</t:template>
