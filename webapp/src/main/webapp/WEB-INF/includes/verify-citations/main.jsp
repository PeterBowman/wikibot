<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/sql" prefix="sql" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>

<sql:query var="resultCommon" dataSource="jdbc/plwikt-common">
	SELECT
		CASE WHEN type LIKE '%.update' THEN timestamp END AS updated,
	    CASE WHEN type LIKE '%.edit' THEN timestamp END AS edited
	FROM
		execution_log
	WHERE
		type LIKE 'tasks.plwikt.CitationTypography.%';
</sql:query>

<sql:query var="result1" dataSource="${verifyCitationsDS}">
	SELECT
		COUNT(*) AS total_entries,
	    SUM(IF(is_pending, 1, 0)) AS total_pending,
	    SUM(IF(review_status, 1, 0)) AS total_verified,
	    SUM(IF(NOT review_status, 1, 0)) AS total_rejected
	FROM
		all_entries;
</sql:query>

<sql:query var="result2" dataSource="${verifyCitationsDS}">
	SELECT
		COUNT(*) AS total_changes,
	    SUM(IF(editor NOT LIKE '@%', 1, 0)) AS manual_changes,
	    COUNT(DISTINCT editor) - 1 AS total_editors,
    	COUNT(DISTINCT reviewer) AS total_reviewers
	FROM
		all_changes;
</sql:query>

<sql:query var="result3" dataSource="${verifyCitationsDS}">
	SELECT
		COUNT(*) AS total_edits,
	    COUNT(DISTINCT change_log_id) AS distinct_edits
	FROM
		edit_log;
</sql:query>

<sql:query var="result4" dataSource="${verifyCitationsDS}">
	SELECT
		COUNT(DISTINCT user) AS total_users
	FROM (
		SELECT user
		FROM change_log
		WHERE user NOT LIKE '@%'
		UNION ALL
		SELECT USER
		FROM review_log
	) AS derived;
</sql:query>

<c:set var="rowCommon" value="${resultCommon.rows[0]}" />
<c:set var="row1" value="${result1.rows[0]}" />
<c:set var="row2" value="${result2.rows[0]}" />
<c:set var="row3" value="${result3.rows[0]}" />
<c:set var="row4" value="${result4.rows[0]}" />

<p>
	Niniejszy projekt stanowi wdrożenie ustaleń z Baru polskojęzycznego Wikisłownika oraz
	póżniejszego głosowania przeprowadzonego na stronie
	<t:linker hrefPattern="https://pl.wiktionary.org/$1"
		target="Wikisłownik:Głosowania/Pozycja odsyłacza przypisu względem kropki" />.
	Celem narzędzia jest ujednolicenie zapisu odsyłacza przypisu względem kropki zamykającej zdanie.
	Ujednolicenie obejmuje (ekstrakt ze strony głosowania):
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
	<li>kropka kończąca skrót (np. <code>itd.</code>),</li>
	<li>kropka po liczebniku porządkowym zapisanym cyframi (np. <code>10.</code>).</li>
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

<h2>Statystyki</h2>

<table class="wikitable mw-statistics-table">
	<tr>
		<th colspan="2">Wystąpienia</th>
	</tr>
	<tr>
		<td><a href="verify-citations/entries?hideprocessed=on">Oczekujące na przetworzenie</a></td>
		<td class="mw-statistics-numbers">${row1.total_pending}</td>
	</tr>
	<tr>
		<td><a href="verify-citations/entries">Wszystkie</a></td>
		<td class="mw-statistics-numbers">${row1.total_entries}</td>
	</tr>
	<tr>
		<th colspan="2">Weryfikacja</th>
	</tr>
	<tr>
		<td>Zatwierdzone wystąpienia</td>
		<td class="mw-statistics-numbers">${row1.total_verified}</td>
	</tr>
	<tr>
		<td>Odrzuczone wystąpienia</td>
		<td class="mw-statistics-numbers">${row1.total_rejected}</td>
	</tr>
	<tr>
		<th colspan="2">Zmiany</th>
	</tr>
	<tr>
		<td><a href="verify-citations/change-log">Ręcznie wprowadzone</a></td>
		<td class="mw-statistics-numbers">${row2.manual_changes}</td>
	</tr>
	<tr>
		<td><a href="verify-citations/change-log?showgenerated=on">Wszystkie</a></td>
		<td class="mw-statistics-numbers">${row2.total_changes}</td>
	</tr>
	<tr>
		<th colspan="2">Edycje</th>
	</tr>
	<tr>
		<td>Jednakowe wystąpienia</td>
		<td class="mw-statistics-numbers">${row3.distinct_edits}</td>
	</tr>
	<tr>
		<td><a href="verify-citations/edit-log">Wszystkie</a></td>
		<td class="mw-statistics-numbers">${row3.total_edits}</td>
	</tr>
	<tr>
		<th colspan="2">Użytkownicy</th>
	</tr>
	<tr>
		<td>Weryfikatorzy</td>
		<td class="mw-statistics-numbers">${row2.total_reviewers}</td>
	</tr>
	<tr>
		<td>Edytorzy</td>
		<td class="mw-statistics-numbers">${row2.total_editors}</td>
	</tr>
	<tr>
		<td><a href="verify-citations/ranking">Wszyscy</a></td>
		<td class="mw-statistics-numbers">${row4.total_users}</td>
	</tr>
</table>
