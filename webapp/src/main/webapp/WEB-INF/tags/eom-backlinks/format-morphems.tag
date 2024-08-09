<%@ tag description="Output morphem formatter" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ attribute name="morphems" required="true" %>
<%@ attribute name="types" required="true" %>

<%@ taglib uri="jakarta.tags.core" prefix="c" %>
<%@ taglib uri="jakarta.tags.functions" prefix="fn" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>

<jsp:useBean id="targetMap" class="java.util.HashMap" />
<jsp:useBean id="missingPageMap" class="java.util.HashMap" />
<jsp:useBean id="missingSectionMap" class="java.util.HashMap" />

<c:forTokens var="target" items="${param.morphem}" delims="|">
    <c:set var="trimmed" value="${fn:trim(target)}" />
    <c:if test="${fn:length(trimmed) ne 0}">
        <c:set target="${targetMap}" property="${trimmed}" value="${true}" />
    </c:if>
    <c:remove var="trimmed" />
</c:forTokens>

<c:forTokens var="type" items="${types}" delims="|" varStatus="status">
    <c:choose>
        <c:when test="${type eq 0}">
            <c:set target="${missingPageMap}" property="${status.index}" value="${true}" />
        </c:when>
        <c:when test="${type eq 1}">
            <c:set target="${missingSectionMap}" property="${status.index}" value="${true}" />
        </c:when>
    </c:choose>
</c:forTokens>

<c:forTokens var="morphem" items="${morphems}" delims="|" varStatus="status">
    <c:set var="normalized" value="${fn:replace(morphem, '_', ' ')}" />
    <c:choose>
        <c:when test="${targetMap[normalized]}">
            <span class="eom-backlinks-hl">${normalized}</span><c:if test="${not status.last}">•</c:if>
        </c:when>
        <c:otherwise>
            <%-- Cast integer index as string, don't use 'value' attribute --%>
            <c:set var="index">${status.index}</c:set>
            <t:linker hrefPattern="https://pl.wiktionary.org/$1#eom" target="${morphem}"
                testMissingPage="${missingPageMap[index]}"
                testMissingSection="${missingSectionMap[index]}" sectionName="esperanto (morfem)" /><%--
            --%><c:if test="${not status.last}">•</c:if>
            <c:remove var="index" />
        </c:otherwise>
    </c:choose>
    <c:remove var="normalized" />
</c:forTokens>
