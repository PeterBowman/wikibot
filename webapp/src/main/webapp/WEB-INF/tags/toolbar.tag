<%@ tag description="Sidebar template" pageEncoding="UTF-8" %>

<div id="p-logo" class="portlet" role="banner">
	<a title="Strona główna" class="mw-wiki-logo" href="/"></a>
</div>

<div id="p-labs" class="portlet" role="navigation">
	<h3>Tool labs</h3>
	<div class="pBody">
		<ul><%@ include file="/WEB-INF/jspf/toolbar-labs.jspf" %></ul>
	</div>
</div>

<div id="p-navigation" class="portlet" role="navigation">
	<h3>Nawigacja</h3>
	<div class="pBody">
		<ul><%@ include file="/WEB-INF/jspf/toolbar-navigation.jspf" %></ul>
	</div>
</div>

<div id="p-tools" class="portlet" role="navigation">
	<h3>Narzędzia</h3>
	<div class="pBody">
		<ul><%@ include file="/WEB-INF/jspf/toolbar-tools.jspf" %></ul>
	</div>
</div>
