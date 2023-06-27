package com.lapissea.jorth.lang.type;

import java.util.List;

public sealed interface JType permits GenericType, JType.Wildcard{
	record Wildcard(List<JType> lower, List<JType> upper) implements JType{
	
	}
}
