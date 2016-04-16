<%@ tag description="Create links to wiki pages" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ attribute name="hrefPattern" required="true" %>
<%@ attribute name="target" required="true" %>
<%@ attribute name="testMissingPage" %>
<%@ attribute name="testMissingSection" %>
<%@ attribute name="sectionName" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>

<c:set var="normalized" value="${fn:replace(target, '_', ' ')}" />
<c:set var="escaped" value="${fn:escapeXml(target)}" />

<c:choose>
	<c:when test="${testMissingPage eq true}">
		<c:set var="href" value="w/index.php?action=edit&redlink=1&title=${escaped}" />
		<c:set var="title" value="(strona nie istnieje)" />
		<c:set var="classVar" value="new" />
	</c:when>
	<c:when test="${testMissingSection eq true}">
		<c:set var="href" value="wiki/${escaped}" />
		<c:choose>
			<c:when test="${not empty sectionName}">
				<c:set var="title" value='(brak sekcji ${sectionName})' />
			</c:when>
			<c:otherwise>
				<c:set var="title" value='(brak sekcji językowej)' />
			</c:otherwise>
		</c:choose>
		<c:set var="classVar" value="false-blue" />
	</c:when>
	<c:otherwise>
		<c:set var="href" value="wiki/${escaped}" />
	</c:otherwise>
</c:choose>

<a href="${fn:replace(hrefPattern, '$1', href)}" 
		title='${normalized}<c:if test="${not empty title}">${" "}${title}</c:if>'
		<c:if test="${not empty classVar}">class="${classVar}"</c:if>
	>${normalized}</a><%-- No trailing newlines! --%>