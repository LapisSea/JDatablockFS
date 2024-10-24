package com.lapissea.dfs.run;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;

public class BooleanContainer extends IOInstance.Managed<BooleanContainer>{
	
	@IOValue
	public boolean value;
	
	public BooleanContainer(){ }
	public BooleanContainer(boolean value){
		this.value = value;
	}
}
