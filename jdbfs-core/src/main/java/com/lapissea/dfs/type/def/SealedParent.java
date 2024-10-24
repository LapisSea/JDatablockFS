package com.lapissea.dfs.type.def;

import com.lapissea.dfs.type.IOInstance;

@IOInstance.StrFormat(name = false, fNames = false)
@IOInstance.Order({"name", "type"})
public interface SealedParent extends IOInstance.Def<SealedParent>{
	
	enum Type{
		EXTEND,
		JUST_INTERFACE
	}
	
	static SealedParent of(String name, Type type){
		return Def.of(SealedParent.class, name, type);
	}
	
	String name();
	Type type();
}
