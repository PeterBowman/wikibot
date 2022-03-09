<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>

<c:set var="heading" value="Przenoszenie refów na koniec" />

<t:template title="${heading}" firstHeading="${heading}" enableJS="true">
	<jsp:attribute name="head">
		<link href="styles/suggestions.css" type="text/css" rel="stylesheet">
		<script src="scripts/suggestions.js"></script>
		<script src="scripts/pretty-ref.js"></script>
	</jsp:attribute>
	<jsp:body>
		<p>
			Adaptacja narzędzia
			<a href="https://github.com/MatmaRex/prettyref" target="_blank">https://github.com/MatmaRex/prettyref</a>
			autorstwa
			<a href="https://pl.wikipedia.org/wiki/Wikipedysta:Matma_Rex" target="_blank">w:pl:User:Matma Rex</a>
			na licencji
			<a href="https://creativecommons.org/licenses/by-sa/3.0/deed.pl" target="_blank">CC BY-SA 3.0</a>.
		</p>
		<form action="${pageContext.request.contextPath}/pretty-ref" method="POST"
			onsubmit="if (this.text.value === '') this.method = 'get'; else this.method = 'post';">
			<fieldset>
				<legend>Konwerter przypisów</legend>
				<span class="mw-input-with-label">
					<label for="title">Tytuł:</label>
					<input id="title" name="title" size="70" value="${param.title}">
				</span>
				lub
				<br>
				<span class="mw-input-with-label">
					<label for="text">Tekst:</label>
					<textarea id="text" name="text" rows="10" cols="50"></textarea>
				</span>
				<br>
				<span class="mw-input-with-label">
					<label for="format">Format:</label>
					<select id="format" name="format">
						<option <c:if test="${param.format eq 'plain'}">selected</c:if>>plain</option>
						<option <c:if test="${param.format eq 'json'}">selected</c:if>>json</option>
						<option disabled>jsonp</option>
					</select>
				</span>
				<span class="mw-input-with-label">
					<input type="checkbox" id="gui" name="gui" checked>
					<label for="gui">wyświetl interfejs</label>
				</span>
				<input type="submit" value="Pokaż" >
			</fieldset>
		</form>
		<c:if test="${not empty output}">
			<h2>Wynik</h2>
			<p>
				<textarea id="output" name="output" rows="20" cols="50" readonly>${output}</textarea>
			</p>
		</c:if>
	</jsp:body>
</t:template>
