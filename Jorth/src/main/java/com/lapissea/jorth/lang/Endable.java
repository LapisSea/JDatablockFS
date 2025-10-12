package com.lapissea.jorth.lang;

import com.lapissea.jorth.exceptions.MalformedJorth;

public interface Endable extends EndableCode{
	void end() throws MalformedJorth;
	@Override
	default void end(TokenSource code) throws MalformedJorth{ end(); }
}
