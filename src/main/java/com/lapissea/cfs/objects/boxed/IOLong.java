package com.lapissea.cfs.objects.boxed;

import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;

public class IOLong extends IOInstance{
	private static final IOStruct TYP=IOStruct.thisClass();
	
	@IOStruct.PrimitiveValue(index=1)
	private long value;
	
	public IOLong(){ }
	public IOLong(int value){
		super(TYP);
		this.value=value;
	}
	
	public long getValue(){
		return value;
	}
	
	@Override
	public String toString(){
		return ""+value;
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		return o instanceof IOLong ioInt&&
		       getValue()==ioInt.getValue();
	}
	
	@Override
	public int hashCode(){
		return Long.hashCode(getValue());
	}
}
