package com.lapissea.cfs.type;

import com.lapissea.cfs.type.field.annotations.IOValue;

public class StructLayout extends IOInstance.Managed<StructLayout>{
	
	@IOValue
	private String typeName;
	
	public StructLayout(){}
	
	public StructLayout(String typeName){
		this.typeName=typeName;
	}
	
	
	public String getTypeName(){
		return typeName;
	}
}
