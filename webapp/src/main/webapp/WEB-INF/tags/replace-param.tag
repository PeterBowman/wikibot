<%-- Based on http://stackoverflow.com/a/33246991 --%>
<%@ tag pageEncoding="UTF-8" dynamic-attributes="attrs" trimDirectiveWhitespaces="true" %>
<%@ attribute name="context" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>

<c:url value="${context}">

	<%--
		Replaces or adds a set of params to a URL.
		If $attr.key in query then replace its value with $attr.value.
		Copies existing values.
	--%>
	
	<c:forEach var="p" items="${paramValues}">
		<c:choose>
			<c:when test="${not empty attrs[p.key]}">
				<c:param name="${p.key}" value="${attrs[p.key]}"/>
			</c:when>
			<c:otherwise>
				<c:forEach var="val" items="${p.value}">
					<c:param name="${p.key}" value="${val}"/>
				</c:forEach>
			</c:otherwise>
		</c:choose>
	</c:forEach>
	
	<%-- If $name not in query, then add. --%>
	<c:forEach var="attr" items="${attrs}">
		<c:if test="${empty param[attr.key]}">
			<c:param name="${attr.key}" value="${attr.value}"/>
		</c:if>
	</c:forEach>

</c:url>
