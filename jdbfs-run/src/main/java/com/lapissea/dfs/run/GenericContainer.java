package com.lapissea.dfs.run;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.annotations.IOValue;

public class GenericContainer<T> extends IOInstance.Managed<GenericContainer<T>>{
	
	@IOValue
	@IOValue.Generic
	public T value;
	
	public GenericContainer(T value){
		this.value = value;
	}
	
	public GenericContainer(){ }
}
