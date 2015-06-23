package com.github.wikibot.utils;
import java.io.IOException;
import java.net.MalformedURLException;

import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.gargoylesoftware.htmlunit.FailingHttpStatusCodeException;
import com.gargoylesoftware.htmlunit.IncorrectnessListener;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

public class RAE extends OnlineDict<RAE> {
	protected boolean exists = false;
	protected boolean isRedirect = false;
	protected boolean isStandAloneVerbForm = false;
	private final WebClient webClient = new WebClient(BrowserVersion.FIREFOX_24);
		
    public RAE(String entry) {
    	super(entry, "http://lema.rae.es/drae/srv/search?val=", "UTF-8");
    	
    	webClient.setIncorrectnessListener(new IncorrectnessListener() {
            public void notify(String arg0, Object arg1) {}
        });
    }
    
    public boolean exists() {
		String text1 = "no está registrada en el Diccionario";
		String text2 = "no está en el Diccionario";
		
		return isSerial ? exists : !(content.contains(text1) || content.contains(text2));
	}
    
    public boolean isRedirect() {
    	return isSerial ? isRedirect : !content.contains("<p class=\"p\">");
    }
    
    public boolean isStandAloneVerbForm() {
    	//return isSerial ? isStandAloneVerbForm : content.contains("<b>" + entry +"</b>");
    	return isSerial ? isStandAloneVerbForm : content.matches(".*?<span class=\"f\"><b>\\s*" + entry + "\\s*</b></span>.*");
    }
    
    protected String escape(String text) {
		text.replace("�", "%E1").replace("�", "%E9").replace("�", "%ED").replace("�", "%F3").replace("�", "%FA");
		text.replace("�", "%C1").replace("�", "%C9").replace("�", "%CD").replace("�", "%D3").replace("�", "%DA");
		text.replace("�", "%F1").replace("�", "%D1").replace("�", "%FC").replace(" ", "%A0");
		
		return text;
	}
    
    @Override
	protected String stripContent(String text) {
		return text;
	}
    
    @Override
    public RAE call() throws IOException {
		fetchEntry();
		return this;
	}
        
    @Override
    protected String getHTML(String page) throws IOException {
    	HtmlPage htmlpage = webClient.getPage(URL + escape(page));
		String text = htmlpage.asXml();
		text = text.replace("\n", "").replace("\r", "").replaceAll("[ ]{2}", "");
		int a = text.indexOf("<body>") + 6;
		int b = text.indexOf("</body>", a);
		
		content = text.substring(a, b);
		
		return stripContent(content);
	}
    	
	public static void main(String args[]) throws FailingHttpStatusCodeException, MalformedURLException, IOException {
		String entry = "como";
		RAE rae;
		
		try {
			rae = new RAE(entry);
			rae.fetchEntry();
			System.out.println(rae.getContent());
			System.out.println("¿Esta página existe? " + rae.exists());
			System.out.println("¿Es una página de redirección? " + rae.isRedirect());
			System.out.println("¿Es una forma flexiva con acepciones adicionales? " + rae.isStandAloneVerbForm());
		}
		catch (IOException e1) {
			e1.printStackTrace();
		}
		
		/*final WebClient webClient = new WebClient();
	    final HtmlPage page = webClient.getPage("http://htmlunit.sourceforge.net");
	    final String pageAsXml = page.asXml();
	    final String pageAsText = page.asText();
	    
	    webClient.closeAllWindows();*/
	}
}
