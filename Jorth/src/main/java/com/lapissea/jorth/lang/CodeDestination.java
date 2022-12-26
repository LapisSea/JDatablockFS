package com.lapissea.jorth.lang;

import com.lapissea.jorth.MalformedJorth;

public abstract class CodeDestination{
	
	protected abstract TokenSource transform(TokenSource src);
	
	protected abstract void parse(TokenSource source) throws MalformedJorth;
	
	public final void addImports(Class<?>... classes){
		for(Class<?> c : classes) addImport(c);
	}
	public final void addImport(Class<?> clazz){
		addImport(clazz.getName());
	}
	public abstract void addImport(String clasName);
	public abstract void addImportAs(String clasName, String name);
	
}