package com.lapissea.jorth.v2.lang;

import com.lapissea.jorth.MalformedJorthException;

public abstract class CodeDestination{
	
	protected abstract TokenSource transform(TokenSource src);
	
	protected abstract void parse(TokenSource source) throws MalformedJorthException;
	
}
