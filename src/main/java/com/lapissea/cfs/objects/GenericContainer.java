package com.lapissea.cfs.objects;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOType;
import com.lapissea.cfs.type.field.annotations.IOValue;

public class GenericContainer<T extends IOInstance<T>> extends IOInstance<GenericContainer<T>>{
	
	@IOValue
	@IOType.Dynamic
	public T value;
	
	public GenericContainer(T value){
		this.value=value;
	}
	
	public GenericContainer(){}
}
