package com.lapissea.dfs.run;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;

public class IntContainer extends IOInstance.Managed<IntContainer>{
	
	@IOValue
	public int value;
	
	public IntContainer(){ }
	public IntContainer(int value){
		this.value = value;
	}
}
