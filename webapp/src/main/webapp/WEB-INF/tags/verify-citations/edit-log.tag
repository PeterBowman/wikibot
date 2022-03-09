<%@ tag description="Generate a log item for /edit-log subpage" pageEncoding="UTF-8"
	trimDirectiveWhitespaces="true" %>

<%@ attribute name="row" required="true" type="java.util.SortedMap" %>

<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib prefix="fmt" uri="http://java.sun.com/jsp/jstl/fmt" %>

<fmt:formatDate value="${row.edit_timestamp}" pattern="HH:mm, d MMM yyyy" />
.&nbsp;.
zedytowano wersję #${row.change_log_id} wystąpienia
<a href="?entry=%23${row.entry_id}">#${row.entry_id}</a>
(<a href="https://pl.wiktionary.org/w/index.php?curid=${row.page_id}" target="_blank">${row.page_title}</a> •
${row.language} • ${row.field_localized})
(<a href="https://pl.wiktionary.org/w/index.php?diff=${row.rev_id}" target="_blank">diff</a>
•
<a href="diff?entry=${row.entry_id}&changeid=${row.change_log_id}">szczegóły</a>)
