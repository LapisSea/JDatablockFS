package com.lapissea.cfs.objects;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.field.annotations.IOValue;

public class ObjectID extends IOInstance<ObjectID>{
	
	@IOValue
	private String id;
	
	public ObjectID(){}
	public ObjectID(String id){
		this.id=id;
	}
}
