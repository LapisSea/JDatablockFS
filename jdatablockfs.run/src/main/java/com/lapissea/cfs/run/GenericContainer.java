package com.lapissea.cfs.run;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IODynamic;
import com.lapissea.cfs.type.field.annotations.IOValue;

public class GenericContainer<T> extends IOInstance.Managed<GenericContainer<T>>{
	
	@IOValue
	@IODynamic
	public T value;
	
	public GenericContainer(T value){
		this.value=value;
	}
	
	public GenericContainer(){}
}
