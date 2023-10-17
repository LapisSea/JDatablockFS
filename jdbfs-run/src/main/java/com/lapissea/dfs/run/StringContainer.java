package com.lapissea.dfs.run;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;

public class StringContainer extends IOInstance.Managed<StringContainer>{
	
	@IOValue
	public String value;
	
	public StringContainer(){ }
	public StringContainer(String value){
		this.value = value;
	}
}
