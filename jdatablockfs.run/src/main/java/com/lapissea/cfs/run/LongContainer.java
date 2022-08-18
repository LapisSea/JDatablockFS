package com.lapissea.cfs.run;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOValue;

public class LongContainer extends IOInstance.Managed<LongContainer>{
	
	@IOValue
	public long value;
	
	public LongContainer(){}
	public LongContainer(long value){
		this.value=value;
	}
}
