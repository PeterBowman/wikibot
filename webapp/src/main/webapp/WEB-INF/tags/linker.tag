<%@ tag description="Create links to wiki pages" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ attribute name="hrefPattern" required="true" %>
<%@ attribute name="target" required="true" %>
<%@ attribute name="testMissingPage" %>
<%@ attribute name="testMissingSection" %>
<%@ attribute name="testRedirection" %>
<%@ attribute name="testDisambiguation" %>
<%@ attribute name="sectionName" %>
<%@ attribute name="display" %>
<%@ attribute name="customClasses" %>

<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.functions" prefix="fn" %>
<%@ taglib uri="tld/utils" prefix="utils" %>

<c:set var="normalized" value="${fn:replace(target, '_', ' ')}" />
<c:set var="escaped" value="${fn:escapeXml(normalized)}" />
<c:set var="encodedParam" value="${utils:encodeParam(normalized)}" />
<c:set var="encodedTitle" value="${fn:replace(encodedParam, '+', '_')}" />

<c:if test="${empty display}">
    <c:set var="display" value="${normalized}" />
</c:if>

<c:choose>
    <c:when test="${testMissingPage eq true}">
        <c:set var="href" value="w/index.php?action=edit&redlink=1&title=${encodedParam}" />
        <c:set var="title" value="(strona nie istnieje)" />
        <c:set var="classVar" value="new" />
    </c:when>
    <c:when test="${testMissingSection eq true}">
        <c:set var="href" value="wiki/${encodedTitle}" />
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
    <c:when test="${testRedirection eq true}">
        <c:set var="href" value="w/index.php?redirect=no&title=${encodedParam}" />
        <c:set var="title" value="(przekierowanie)" />
        <c:set var="classVar" value="redirect" />
    </c:when>
    <c:when test="${testDisambiguation eq true}">
       <c:set var="href" value="wiki/${encodedTitle}" />
       <c:set var="title" value="(strona ujednoznaczniająca)" />
       <c:set var="classVar" value="disambig" />
    </c:when>
    <c:otherwise>
        <c:set var="href" value="wiki/${encodedTitle}" />
    </c:otherwise>
</c:choose>

<c:set var="classVar" value="wikilink ${classVar} ${customClasses}" />

<a href="${fn:replace(hrefPattern, '$1', href)}"
        class="${fn:trim(classVar)}"
        data-target="${escaped}"
        data-href="${fn:substring(hrefPattern, 0, fn:indexOf(hrefPattern, '$1'))}"
        <c:if test="${not empty sectionName}">data-section="${sectionName}"</c:if>
        title='${escaped}<c:if test="${not empty title}">${" "}${title}</c:if>'
        target="_blank"
    >${display}</a><%-- No trailing newlines! --%>
