package com.lapissea.dfs.run;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;

import java.lang.invoke.MethodHandles;

public class IntContainer extends IOInstance.Managed<IntContainer>{
	static{ allowFullAccess(MethodHandles.lookup()); }
	
	@IOValue
	public int value;
	
	public IntContainer(){ }
	public IntContainer(int value){
		this.value = value;
	}
}
