package com.lapissea.cfs.objects;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOValue;

public class ObjectID extends IOInstance.Managed<ObjectID>{
	
	@IOValue
	private String id;
	
	public ObjectID(){}
	public ObjectID(String id){
		this.id=id;
	}
	
	@Override
	public String toString(){
		return id;
	}
	@Override
	public String toShortString(){
		return id;
	}
}
