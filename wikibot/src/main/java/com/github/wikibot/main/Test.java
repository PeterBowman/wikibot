package com.github.wikibot.main;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import com.oracle.avatar.js.Loader;
import com.oracle.avatar.js.Server;
import com.oracle.avatar.js.eventloop.ThreadPool;
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
		//List<String> list = new ArrayList<>();
		//scriptContext.getBindings(ScriptContext.ENGINE_SCOPE).put("list", list);
		
		//Server server = new Server(engine, new Loader.Core(), new Logging(false), System.getProperty("user.dir"));
		Server server = new Server(engine, new Loader.Core(), new Logging(false), System.getProperty("user.dir"),
			scriptContext, 0, ThreadPool.newInstance(), null, null, false);
		
		server.run("parsoid.js");
		//System.out.println(list);
		//System.out.println(engine.eval("text"));
		//System.out.println((JSObject)engine.get("text"));
		return scriptWriter.toString();
	}
}
