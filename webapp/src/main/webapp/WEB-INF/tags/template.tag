<%@ tag description="Standard template" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<%@ attribute name="title" %>
<%@ attribute name="firstHeading" %>
<%@ attribute name="enableJS" %>
<%@ attribute name="head" fragment="true" %>
<%@ attribute name="cactions" fragment="true" %>
<%@ attribute name="toolbarExtra" fragment="true" %>
<%@ attribute name="headerNotice" fragment="true" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>

<!DOCTYPE html>
<html>
<head>
	<meta charset="UTF-8">
	<c:set var="contextPath" value="${pageContext.request.contextPath}" />
	<link href="${contextPath}/styles/shared.css" type="text/css" rel="stylesheet">
	<link href="${contextPath}/styles/elements.css" type="text/css" rel="stylesheet">
	<link href="${contextPath}/styles/interface.css" type="text/css" rel="stylesheet">
	<link href="${contextPath}/styles/monobook.css" type="text/css" rel="stylesheet">
	<link href="${contextPath}/styles/pbbot-common.css" type="text/css" rel="stylesheet">
	<c:if test="${not empty headerNotice}">
		<link href="${contextPath}/styles/ambox.css" type="text/css" rel="stylesheet">
	</c:if>
	<link href="/favicon.ico" rel="shortcut icon" />
	<title>
		<c:choose>
			<c:when test="${not empty title}">${title}</c:when>
			<c:otherwise>PBbot</c:otherwise>
		</c:choose>
	</title>
	<c:if test="${enableJS}">
		<script src="//tools-static.wmflabs.org/static/jquery/1.11.0/jquery.min.js"></script>
	</c:if>
	<jsp:invoke fragment="head" />
</head>
<body>
	<div id="globalWrapper">
		<div id="column-content">
			<div id="content" class="mw-body" role="main">
				<a id="top"></a>
				<div id="sitenotice"><jsp:invoke fragment="headerNotice" /></div>
				<c:if test="${not empty firstHeading}">
					<h1 id="firstHeading" class="firstHeading">${firstHeading}</h1>
				</c:if>
				<div id="bodyContent" class="mw-body-content">
					<div id="contentSub"></div>
					<div id="mw-content-text" class="mw-content-ltr" dir="ltr">
						<p></p><jsp:doBody />
					</div>
					<div class="visualClear"></div>
				</div>
			</div>
		</div>
		<div id="column-one">
			<h2>Menu nawigacyjne</h2>
			<div id="p-cactions" class="portlet" role="navigation">
				<h3>Podstrony</h3>
				<div class="pBody">
					<ul><jsp:invoke fragment="cactions" /></ul>
				</div>
			</div>
			<t:toolbar />
			<jsp:invoke fragment="toolbarExtra" />
		</div>
		<div class="visualClear"></div>
		<div id="footer" role="contentinfo">
			<ul id="flist"><%@ include file="/WEB-INF/fragments/footer.jspf" %></ul>
		</div>
	</div>
</body>
</html>