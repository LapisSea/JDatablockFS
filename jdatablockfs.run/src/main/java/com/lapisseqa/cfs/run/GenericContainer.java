package com.lapisseqa.cfs.run;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOType;
import com.lapissea.cfs.type.field.annotations.IOValue;

public class GenericContainer<T> extends IOInstance<GenericContainer<T>>{
	
	@IOValue
	@IOType.Dynamic
	public T value;
	
	public GenericContainer(T value){
		this.value=value;
	}
	
	public GenericContainer(){}
}
