package com.lapissea.jorth.lang;

import com.lapissea.jorth.MalformedJorthException;

public class Token{
	
	public final int    line;
	public final String source;
	
	public Token(int line, String source){
		this.line=line;
		this.source=source;
	}
	
	public boolean isFloating(){
		try{
			Double.parseDouble(source);
			return true;
		}catch(NumberFormatException e){
			return false;
		}
	}
	
	public boolean isInteger(){
		try{
			Long.parseLong(source);
			return true;
		}catch(NumberFormatException e){
			return false;
		}
	}
	
	public boolean isStringLiteral(){
		return source.startsWith("'")&&source.endsWith("'");
	}
	public String getStringLiteralValue(){
		return source.substring(1, source.length()-1);
	}
	
	public String lower(){
		return source.toLowerCase();
	}
	
	
	public Visibility asVisibility() throws MalformedJorthException{
		return Visibility.fromName(source);
	}
	
	@Override
	public String toString(){
		return source;
	}
}
