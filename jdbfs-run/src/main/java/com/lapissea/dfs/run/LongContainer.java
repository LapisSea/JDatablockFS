package com.lapissea.dfs.run;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;

public class LongContainer extends IOInstance.Managed<LongContainer>{
	
	@IOValue
	public long value;
	
	public LongContainer(){ }
	public LongContainer(long value){
		this.value = value;
	}
}
