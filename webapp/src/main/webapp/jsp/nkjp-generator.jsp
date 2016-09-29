<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>

<c:set var="title" value="Generator szablonu {{NKJP}}" />
<c:set var="heading" value="Generator szablonu <tt>{{NKJP}}</tt>" />

<t:template title="${title}" firstHeading="${heading}" enableJS="true">
	<jsp:attribute name="head">
		<script src="scripts/nkjp-generator.js"></script>
	</jsp:attribute>
	<jsp:body>
		<p>
			Narzędzie generujące wypełniony szablon
			<code>{{NKJP}}</code>
			na podstawie adresu prowadzącego do wystąpienia w
			<a href="http://nkjp.pl/" target="_blank">Narodowym Korpusie Języka Polskiego</a>.
			Przykład z
			<a href="https://pl.wiktionary.org/wiki/Szablon:NKJP" target="_blank">dokumentacji szablonu</a>
			(kliknij na link, aby sprawdzić wynik):
		</p>
		<p>
			<c:set var="example" value="http://nkjp.uni.lodz.pl/ParagraphMetadata?pid=e8f388f43c5a921039efd59b53dddc70&amp;match_start=228&amp;match_end=235&amp;wynik=1" />
			<c:url var="exampleUrl" value="">
				<c:param name="address" value="${example}" />
				<c:param name="gui" value="on" />
			</c:url>
			<code><a href="${exampleUrl}" id="example-link">${example}</a></code>
		</p>
		<p>
			Podobne linki można uzyskać w wyszukiwarce
			<a href="http://www.nkjp.uni.lodz.pl/index.jsp" target="_blank">PELCRA NKJP 1.0</a>
			(z adresu okna otwieranego po naciśnięciu ikony z plusem obok wyniku wyszukiwania).
		</p>
		<form action="/pbbot/nkjp-generator" method="GET" id="nkjp-form">
			<fieldset>
				<input type="hidden" name="gui" value="on">
				<legend>Generator {{NKJP}}</legend>
				<span class="mw-input-with-label">
					<label for="address">Adres:</label>
					<input id="address" name="address" size="70" value="${param.address}">
				</span>
				<input type="submit" id="submit" value="Wyślij">
			</fieldset>
		</form>
		<c:if test="${not empty output or not empty error}">
			<h2>Wynik</h2>
			<c:choose>
				<c:when test="${not empty error}">
					<p>Błąd: ${error}</p>
					<c:if test="${not empty backtrace}">
						<ul>
							<c:forEach var="element" items="${backtrace}">
								<li>${element}</li>
							</c:forEach>
						</ul>
					</c:if>
				</c:when>
				<c:otherwise>
					<pre>${output}</pre>
					<c:if test="${not empty parameters}">
						<p>Parametry szablonu:</p>
						<ul>
							<c:forEach var="entry" items="${parameters}">
								<li><code>${entry.key}</code>: ${entry.value}</li>
							</c:forEach>
						</ul>
					</c:if>
				</c:otherwise>
			</c:choose>
		</c:if>
	</jsp:body>
</t:template>
