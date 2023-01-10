<%@ tag description="Select input element" pageEncoding="UTF-8" dynamic-attributes="options"
    trimDirectiveWhitespaces="true" %>

<%@ attribute name="parameter" required="true" %>
<%@ attribute name="label" %>
<%@ attribute name="defaultOption" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>

<c:if test="${not empty label}">
    <label for="${parameter}">${label}:</label>
</c:if>

<select name="${parameter}" id="${parameter}">

<c:if test="${not empty defaultOption}">
    <option <c:if test="${empty param[parameter]}">selected</c:if> disabled>
        ${defaultOption}
    </option>
</c:if>

<c:forEach var="option" items="${options}">
    <option value="${option.key}" <c:if test="${param[parameter] eq option.key}">selected</c:if>>
        ${option.value}
    </option>
</c:forEach>

</select>
