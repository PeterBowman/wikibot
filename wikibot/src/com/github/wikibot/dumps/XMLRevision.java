package com.github.wikibot.dumps;

import java.io.Serializable;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.TimeZone;

import com.github.wikibot.utils.PageContainer;

public class XMLRevision implements Serializable, Comparable<XMLRevision>, Cloneable {
	private static final long serialVersionUID = -7617943255499585377L;
	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
	
	String title;
	int ns;
	int pageid;
	boolean isRedirect; // TODO: add target title
	int revid;
	int parentid = 0;
	String timestamp;
	String contributor;
	boolean isMinor;
	String comment;
	String text;
	
	XMLRevision() {}
	
	public String getTitle() {
		return title;
	}
	
	public int getNamespace() {
		return ns;
	}
	
	public int getPageid() {
		return pageid;
	}
	
	public boolean isRedirect() {
		return isRedirect;
	}
	
	public int getRevid() {
		return revid;
	}
	
	public int getParentid() {
		return parentid;
	}
	
	public String getTimestamp() {
		return timestamp;
	}
	
	public Calendar getCalendarTimestamp() {
		return timestampToCalendar(timestamp);
	}
	
	public String getContributor() {
		return contributor;
	}
	
	public boolean isMinor() {
		return isMinor;
	}
	
	public String getComment() {
		return comment;
	}
	
	public String getText() {
		return text;
	}
	
	public boolean isMainNamespace() {
		return ns == 0;
	}
	
	public boolean nonRedirect() {
		return !isRedirect;
	}
	
	public PageContainer toPageContainer() {
		return new PageContainer(title, text, timestampToCalendar(timestamp));
	}
	
	private static Calendar timestampToCalendar(String timestamp) {
		Calendar cal = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
		
		try {
			cal.setTime(DATE_FORMAT.parse(timestamp));
		} catch (ParseException e) {
			// set time to Unix epoch to allow edit conflict detection
			cal.setTimeInMillis(0);
		}
		 
		return cal;
	}
	
	@Override
	public int hashCode() {
		return revid;
	};
	
	@Override
	public boolean equals(Object obj) {
		if (!(obj instanceof XMLRevision) || obj != this) {
			return false;
		}
		
		XMLRevision rev = (XMLRevision) obj;
		return rev.revid == revid;
	};
	
	@Override
	public XMLRevision clone() {
		XMLRevision rev = new XMLRevision();
		rev.title = title;
		rev.ns = ns;
		rev.pageid = pageid;
		rev.isRedirect = isRedirect;
		rev.revid = revid;
		rev.parentid = parentid;
		rev.timestamp = timestamp;
		rev.contributor = contributor;
		rev.isMinor = isMinor;
		rev.comment = comment;
		rev.text = text;
		return rev;
	}
	
	@Override
	public int compareTo(XMLRevision o) {
		return Integer.compare(revid, o.revid);
	}
	
	@Override
	public String toString() {
		// contributor/comment/text may be null (revdeleted)
		StringBuilder sb = new StringBuilder((text != null ? text.length() : 0) + 100);
		sb.append("[title=").append(title);
		sb.append(",ns=").append(ns);
		sb.append(",pageid=").append(pageid);
		sb.append(",isRedirect=").append(isRedirect);
		sb.append(",revid=").append(revid);
		sb.append(",parentid=").append(parentid);
		sb.append(",timestamp=").append(timestamp);
		sb.append(",contributor=").append(contributor);
		sb.append(",isMinor=").append(isMinor);
		sb.append(",comment=").append(comment);
		sb.append(",text=").append(text).append("]");
		return sb.toString();
	}
}
