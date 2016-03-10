<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" isErrorPage="true" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c"%>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>

<t:template firstHeading="Błąd 404">
	<jsp:body>
		<p>
			Podana strona nie istnieje.
			Przejdż do <a href="${pageContext.request.contextPath}">strony głównej</a>.
		</p>
	</jsp:body>
</t:template>