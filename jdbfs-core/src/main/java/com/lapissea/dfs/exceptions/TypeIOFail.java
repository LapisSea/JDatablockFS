package com.lapissea.dfs.exceptions;

import com.lapissea.dfs.Utils;

import java.io.IOException;
import java.util.stream.Collectors;

public class TypeIOFail extends IOException{
	
	private static String makeMsg(String note, Class<?> type, Throwable cause){
		var base = note + (type == null? "" : ": " + Utils.typeToHuman(type, false));
		if(cause == null) return base;
		var msg = cause.getMessage() == null? Utils.typeToHuman(cause.getClass(), false) : cause.getMessage().trim();
		return "\n" +
		       base + "\n" +
		       msg.lines().map(s -> "\t" + s).collect(Collectors.joining("\n"));
	}
	
	public final Class<?> type;
	
	public TypeIOFail(String note, Class<?> type){
		super(makeMsg(note, type, null));
		this.type = type;
	}
	
	public TypeIOFail(String note, Class<?> type, Throwable cause){
		super(makeMsg(note, type, cause), getCause(cause));
		this.type = type;
	}
	private static Throwable getCause(Throwable cause){
		while(cause instanceof TypeIOFail f && f.getCause() != null) cause = f.getCause();
		return cause;
	}
}
