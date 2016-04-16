<%@ tag description="Sidebar template" pageEncoding="UTF-8" trimDirectiveWhitespaces="true" %>

<div id="p-logo" class="portlet" role="banner">
	<a title="Strona główna" class="mw-wiki-logo" href="/" target="_blank"></a>
</div>

<div id="p-labs" class="portlet" role="navigation">
	<h3>Tool labs</h3>
	<div class="pBody">
		<ul><%@ include file="/WEB-INF/fragments/toolbar-labs.jspf" %></ul>
	</div>
</div>

<div id="p-navigation" class="portlet" role="navigation">
	<h3>Nawigacja</h3>
	<div class="pBody">
		<ul><%@ include file="/WEB-INF/fragments/toolbar-navigation.jspf" %></ul>
	</div>
</div>

<div id="p-tools" class="portlet" role="navigation">
	<h3>Narzędzia</h3>
	<div class="pBody">
		<ul><%@ include file="/WEB-INF/fragments/toolbar-tools.jspf" %></ul>
	</div>
</div>
