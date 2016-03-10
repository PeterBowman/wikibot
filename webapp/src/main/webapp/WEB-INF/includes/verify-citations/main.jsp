<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/sql" prefix="sql" %>

<sql:setDataSource dataSource="jdbc/VerifyCitations" var="ds" />
<p>Test!</p>
<h2>Statystyka</h2>
<sql:query var="result" dataSource="${ds}">
	SELECT * FROM page_title LIMIT 10;
</sql:query>
<table class="wikitable" style="width: 100%;">
	<tr>
	   <th>Page ID</th>
	   <th>Title</th>
	</tr>
	<c:forEach var="row" items="${result.rows}">
		<tr>
		   <td>${row.page_id}</td>
		   <td>${row.page_title}</td>
		</tr>
	</c:forEach>
</table>
