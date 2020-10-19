package com.lapissea.cfs.objects.boxed;

import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;

public class IOInt extends IOInstance{
	private static final IOStruct TYP=IOStruct.thisClass();
	
	@IOStruct.PrimitiveValue(index=1)
	private int value;
	
	public IOInt(){ }
	public IOInt(int value){
		super(TYP);
		this.value=value;
	}
	
	public int getValue(){
		return value;
	}
	@Override
	public String toString(){
		return ""+value;
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		return o instanceof IOInt ioInt&&
		       getValue()==ioInt.getValue();
	}
	
	@Override
	public int hashCode(){
		return Integer.hashCode(getValue());
	}
}
