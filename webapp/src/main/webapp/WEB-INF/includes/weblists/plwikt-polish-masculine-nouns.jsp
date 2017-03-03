<%@ page language="java" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>

<c:set var="title" value="Polskie rzeczowniki rodzaju męskiego" />

<t:template title="${title}" firstHeading="${title}">
	<jsp:attribute name="contentSub">
		<a href="${pageContext.request.contextPath}/weblists">Powrót do indeksu</a>
	</jsp:attribute>
	<jsp:body>
		<p>
			Hasła polskie opisujące rzeczowniki, zawierające ciąg znaków <code>rodzaj męski</code>
			w polu <strong>znaczenia</strong>.
		</p>
		<p>
			Zobacz też:
			<a href="https://pl.wiktionary.org/wiki/Kategoria:J%C4%99zyk_polski_-_rzeczowniki_rodzaju_m%C4%99skiego" target="_blank">
				Kategoria:Język polski - rzeczowniki rodzaju męskiego</a>.
		</p>
	</jsp:body>
</t:template>
