package com.lapisseqa.cfs.run;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOValue;

public class StringContainer extends IOInstance<StringContainer>{
	
	@IOValue
	public String value;
	
	public StringContainer(){}
	public StringContainer(String value){
		this.value=value;
	}
}
