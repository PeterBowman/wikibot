package com.github.wikibot.dumps;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Optional;

import com.github.wikibot.utils.PageContainer;

public class XMLRevision implements Serializable, Comparable<XMLRevision> {
	private static final long serialVersionUID = -7617943255499585377L;
	
	String title;
	int ns;
	long pageid;
	boolean isRedirect; // TODO: add target title
	long revid;
	long parentid;
	String timestamp;
	String contributor;
	boolean isAnonymousContributor;
	boolean isMinor;
	String comment;
	String text;
	int bytes;
	boolean isRevDeleted;
	boolean isCommentDeleted;
	boolean isUserDeleted;
	
	XMLRevision() {
		parentid = 0;
		contributor = ""; // otherwise might be null if revdeleted
		comment = ""; // empty comment
	}
	
	public String getTitle() {
		return title;
	}
	
	public int getNamespace() {
		return ns;
	}
	
	public long getPageid() {
		return pageid;
	}
	
	public boolean isRedirect() {
		return isRedirect;
	}
	
	public long getRevid() {
		return revid;
	}
	
	public long getParentid() {
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
	
	public int getBytes() {
		return bytes;
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
	
	public boolean isRevisionDeleted() {
		return isRevDeleted;
	}
	
	public boolean isCommentDeleted() {
		return isCommentDeleted;
	}
	
	public boolean isContributorDeleted() {
		return isUserDeleted;
	}
	
	public PageContainer toPageContainer() {
		return new PageContainer(title, text, OffsetDateTime.parse(timestamp));
	}
	
	@Override
	public int hashCode() {
		return (int)revid;
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
	public int compareTo(XMLRevision o) {
		return Optional.of(Long.compare(pageid, o.pageid))
			.filter(v -> v != 0)
			.orElse(Long.compare(revid, o.revid));
	}
	
	@Override
	public String toString() {
		return
			"[title=" + title +
			",ns=" + ns +
			",pageid=" + pageid +
			",isRedirect=" + isRedirect +
			",revid=" + revid +
			",parentid=" + parentid +
			",timestamp=" + timestamp +
			",contributor=" + contributor +
			",isAnon=" + isAnonymousContributor +
			",isMinor=" + isMinor +
			",comment=" + comment +
			",bytes=" + bytes +
			",isRevDeleted=" + isRevDeleted +
			",isCommentDeleted=" + isCommentDeleted +
			",isUserDeleted=" + isUserDeleted +
			",text=" + text + "]";
	}
}
