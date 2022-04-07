package com.lapissea.cfs.run;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOValue;

public class IntContainer extends IOInstance<IntContainer>{
	
	@IOValue
	public int value;
	
	public IntContainer(){}
	public IntContainer(int value){
		this.value=value;
	}
}
