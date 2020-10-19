package com.lapissea.cfs.objects.boxed;

import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;

public class IOFloat extends IOInstance{
	private static final IOStruct TYP=IOStruct.thisClass();
	
	@IOStruct.PrimitiveValue(index=1)
	private float value;
	
	public IOFloat(){ }
	public IOFloat(int value){
		super(TYP);
		this.value=value;
	}
	
	public float getValue(){
		return value;
	}
	
	@Override
	public String toString(){
		return ""+value;
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		return o instanceof IOFloat ioInt&&
		       getValue()==ioInt.getValue();
	}
	
	@Override
	public int hashCode(){
		return Float.hashCode(getValue());
	}
}
