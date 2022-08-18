package com.lapissea.cfs.run;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOValue;

public class StringContainer extends IOInstance.Managed<StringContainer>{
	
	@IOValue
	public String value;
	
	public StringContainer(){}
	public StringContainer(String value){
		this.value=value;
	}
}
