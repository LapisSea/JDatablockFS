package com.lapissea.cfs.tools.logging;

import com.lapissea.util.LogUtil;

import java.io.PrintWriter;
import java.io.Serializable;
import java.io.Writer;

public record MemFrame(byte[] data, long[] ids, String e) implements Serializable{
	
	public MemFrame(byte[] data, long[] ids, Throwable e){
		this(data, ids, toStr(e));
	}
	private static String toStr(Throwable e){
		StringBuffer b=new StringBuffer();
		
		e.printStackTrace(new PrintWriter(new Writer(){
			@Override
			public void write(char[] cbuf, int off, int len){b.append(cbuf, off, len);}
			@Override
			public void flush(){}
			@Override
			public void close(){}
		}));
		
		
		return b.toString();
	}
	
	public void printStackTrace(){
		LogUtil.printlnEr(e);
	}
	
	public boolean exceptionContains(String match){
		return e.contains(match);
//		return Arrays.stream(e.getStackTrace()).map(Object::toString).anyMatch(l->l.contains(match));
	}
}
