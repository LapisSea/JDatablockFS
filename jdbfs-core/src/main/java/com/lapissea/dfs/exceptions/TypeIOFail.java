package com.lapissea.dfs.exceptions;

import com.lapissea.dfs.Utils;

import java.io.IOException;
import java.util.stream.Collectors;

public class TypeIOFail extends IOException{
	
	private static String makeMsg(String note, Class<?> type, Throwable cause){
		var base = note + (type == null? "" : ": " + Utils.typeToHuman(type));
		if(cause == null) return base;
		var msg = cause.getMessage() == null? Utils.typeToHuman(cause.getClass()) : cause.getMessage().trim();
		return "\n" +
		       base + "\n" +
		       msg.lines().map(s -> "\t" + s).collect(Collectors.joining("\n"));
	}
	
	private record Msg(String note, Class<?> type, Throwable cause){ }
	
	public final Class<?> type;
	private      String   msgCache;
	private      Msg      msg;
	
	public TypeIOFail(String note, Class<?> type, Throwable cause){
		super(getCause(cause));
		this.type = type;
		msg = new Msg(note, type, cause);
	}
	
	private static Throwable getCause(Throwable cause){
		while(cause instanceof TypeIOFail f && f.getCause() != null) cause = f.getCause();
		return cause;
	}
	
	@Override
	public String getMessage(){
		if(msgCache != null) return msgCache;
		msgCache = makeMsg(msg.note, msg.type, msg.cause);
		msg = null;
		return msgCache;
	}
}
