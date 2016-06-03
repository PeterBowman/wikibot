<%@ tag description="Generate a log item for /edit-log subpage" pageEncoding="UTF-8"
	trimDirectiveWhitespaces="true" %>

<%@ attribute name="row" required="true" type="java.util.SortedMap" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/functions" prefix="fn" %>
<%@ taglib tagdir="/WEB-INF/tags" prefix="t" %>

<fmt:formatDate value="${row.edit_timestamp}" pattern="HH:mm, d MMM yyyy" />
.&nbsp;.
wersję #${row.change_log_id} wystąpienia #${row.entry_id}
(<a href="https://pl.wiktionary.org/w/index.php?curid=${row.page_id}">${row.page_title}</a> •
${row.language} • ${row.localized})
(szczegóły)
