package com.lapisseqa.cfs.run;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOValue;

public class BooleanContainer extends IOInstance<BooleanContainer>{
	
	@IOValue
	public boolean value;
	
	public BooleanContainer(){}
	public BooleanContainer(boolean value){
		this.value=value;
	}
}
