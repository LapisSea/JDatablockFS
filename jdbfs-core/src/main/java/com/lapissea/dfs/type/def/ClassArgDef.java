package com.lapissea.dfs.type.def;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.field.annotations.IOValue;

@IOValue
@IOInstance.Order({"name", "bound"})
public interface ClassArgDef extends IOInstance.Def<ClassArgDef>{
	String name();
	IOType bound();
	
	static ClassArgDef of(String name, IOType bound){
		return Def.of(ClassArgDef.class, name, bound);
	}
}
