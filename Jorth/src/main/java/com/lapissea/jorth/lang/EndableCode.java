package com.lapissea.jorth.lang;

import com.lapissea.jorth.exceptions.MalformedJorth;

public interface EndableCode{
	void end(TokenSource code) throws MalformedJorth;
}
