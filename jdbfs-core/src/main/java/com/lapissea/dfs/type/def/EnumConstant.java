package com.lapissea.dfs.type.def;

import com.lapissea.dfs.type.IOInstance;

@IOInstance.StrFormat(name = false, fNames = false, curly = false)
public interface EnumConstant extends IOInstance.Def<EnumConstant>{
	
	static EnumConstant of(Enum<?> e){
		return Def.of(EnumConstant.class, e.name());
	}
	
	String getName();
}
