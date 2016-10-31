<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>

<jsp:include page="main-statistics.jsp" />

<p>
	Niniejszy projekt stanowi wdrożenie ustaleń z Baru polskojęzycznego Wikisłownika oraz
	póżniejszego głosowania przeprowadzonego na stronie
	<t:linker hrefPattern="https://pl.wiktionary.org/$1"
		target="Wikisłownik:Głosowania/Pozycja odsyłacza przypisu względem kropki" />.
	Celem narzędzia jest ujednolicenie zapisu odsyłacza przypisu względem kropki zamykającej zdanie.
	Ujednolicenie obejmuje (wyciąg ze strony głosowania):
</p>

<ul>
	<li>wyłącznie pozycję odsyłacza przypisu i kropki (innych znaków interpunkcyjnych – nie)</li>
	<li>
		wyłącznie przypisy stojące po zdaniach polskich, tzn.:
		<ul>
			<li>hasła polskie w całości</li>
			<li>
				w hasłach obcych pola
				<ol>
					<li>przykłady, o ile przypis stoi po tłumaczeniu (czyli po strzałce)</li>
					<li>etymologia</li>
					<li>uwagi.</li>
				</ol>
			</li>
		</ul>
	</li>
</ul>

<p>
	<c:set var="context" value="${pageContext.request.contextPath}${pageContext.request.servletPath}" />
	Narzędzie oferuje użytkownikom Wikisłownika możliwość zatwierdzania lub odrzucania
	automatycznie wygenerowanych zmian, jak również ich swobodnej edycji w chwili
	oznaczania. Wystąpienia są wykrywane na podstawie analizy ostatnich zmian (codziennie) oraz
	opublikowanych zrzutów z bazy danych Wikisłownika (okresowo). Zarówno wystąpienia wraz z ich
	wygenerowanymi modyfikacjami, jak i oznaczone wersje tych modyfikacji oraz ręcznie wprowadzone
	zmiany są magazynowane w wewnętrznej bazie danych. Codzienne uruchomienie programu skutkuje
	przetworzeniem wystąpień na <a href="${context}/entries?hideprocessed=on">liście roboczej</a>,
	wyselekcjonowaniem <a href="${context}/review-log">zatwierdzonych</a> z uwzględnieniem
	<a href="${context}/change-log">modyfikacji użytkowników</a> oraz naniesieniem
	<a href="${context}/edit-log">zmian w hasłach</a> za pośrednictwem bota
	<t:linker hrefPattern="https://pl.wiktionary.org/$1" target="User:PBbot" display="PBbot" />.
</p>

<p>
	Należy zwracać uwagę na wyjątki nieobjęte przez powyższe ustalenia, których narzędzie nie jest
	w stanie poprawnie zidentyfikować: 
</p>

<ul>
	<li>kropkę kończącą skrót (np. <code>itd.</code>),</li>
	<li>kropkę po liczebniku porządkowym zapisanym cyframi (np. <code>10.</code>).</li>
</ul>

<fmt:formatDate var="updated" value="${rowCommon.updated}" pattern="HH:mm, d MMM yyyy" />
<fmt:formatDate var="edited" value="${rowCommon.edited}" pattern="HH:mm, d MMM yyyy" />

<p>
	Wszystkie sygnatury czasowe odnoszą się do strefy Europy Środkowej (CEST).
</p>

<ul>
	<li>
		Ostatnia aktualizacja bazy danych:
		<c:choose>
			<c:when test="${not empty updated}">
				${updated}.
			</c:when>
			<c:otherwise>
				brak danych.
			</c:otherwise>
		</c:choose>
	</li>
	<li>
		Ostatnia edycja przetworzonych wystąpień:
		<c:choose>
			<c:when test="${not empty edited}">
				${edited}.
			</c:when>
			<c:otherwise>
				brak danych.
			</c:otherwise>
		</c:choose>
	</li>
</ul>
