package com.lapissea.cfs.run;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOValue;

public class BooleanContainer extends IOInstance.Managed<BooleanContainer>{
	
	@IOValue
	public boolean value;
	
	public BooleanContainer(){}
	public BooleanContainer(boolean value){
		this.value=value;
	}
}
