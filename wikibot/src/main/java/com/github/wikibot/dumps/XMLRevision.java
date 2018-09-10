package com.github.wikibot.dumps;

import java.io.Serializable;
import java.time.OffsetDateTime;

import com.github.wikibot.utils.PageContainer;

public class XMLRevision implements Serializable, Comparable<XMLRevision> {
	private static final long serialVersionUID = -7617943255499585377L;
	
	String title;
	int ns;
	int pageid;
	boolean isRedirect; // TODO: add target title
	int revid;
	int parentid = 0;
	String timestamp;
	String contributor;
	boolean isAnonymousContributor;
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
	
	public String getContributor() {
		return contributor;
	}
	
	public boolean isAnonymousContributor() {
		return isAnonymousContributor;
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
	
	public boolean isFirstRevision() {
		return parentid == 0;
	}
	
	public PageContainer toPageContainer() {
		return new PageContainer(title, text, OffsetDateTime.parse(timestamp));
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
		rev.isAnonymousContributor = isAnonymousContributor;
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
		return
			"[title=" + title +
			",ns=" + ns +
			",pageid=" + pageid +
			",isRedirect=" + isRedirect +
			",revid=" + revid +
			",parentid=" + parentid +
			",timestamp=" + timestamp +
			",contributor=" + contributor +
			",isAnonymousContributor=" + isAnonymousContributor +
			",isMinor=" + isMinor +
			",comment=" + comment +
			",text=" + text + "]";
	}
}
