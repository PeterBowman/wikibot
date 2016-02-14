package com.github.wikibot.main;

import java.io.StringWriter;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import com.oracle.avatar.js.Loader;
import com.oracle.avatar.js.Server;
import com.oracle.avatar.js.log.Logging;

public final class Test {
	public static void main(String[] args) throws Throwable {
		String out = runJs();
		System.out.println(out);
	}
	
	static String runJs() throws Throwable {
		StringWriter scriptWriter = new StringWriter();
		ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
		ScriptContext scriptContext = engine.getContext();
		scriptContext.setWriter(scriptWriter);
		Server server = new Server(engine, new Loader.Core(), new Logging(false), System.getProperty("user.dir"));
		server.run("src/main/js/hello-world.js");
		
		return scriptWriter.toString();
	}
}
