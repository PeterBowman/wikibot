<%--
    This HTML structure is based on https://pl.wikipedia.org/wiki/Szablon:Ambox and is
    licensed under CC BY-SA 3.0 (see LICENSE-3RD-PARTIES.txt).
--%>

<%@ tag description="Ambox header template" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>
<%@ attribute name="type" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>

<c:choose>
    <c:when test="${type eq 'warning'}">
        <c:set var="icon" value="icon-warning.png" />
    </c:when>
    <c:when test="${type eq 'serious'}">
        <c:set var="icon" value="icon-serious.png" />
    </c:when>
    <c:otherwise>
        <c:set var="icon" value="icon-notice.png" />
    </c:otherwise>
</c:choose>

<table class="ambox ambox-${not empty type ? type : 'notice'}">
    <tr>
        <td class="ambox-image">
            <div style="width: 52px;">
                <img width="35" height="35" src="${pageContext.request.contextPath}/images/${icon}">
            </div>
        </td>
        <td class="ambox-text">
            <jsp:doBody />
        </td>
    </tr>
</table>
