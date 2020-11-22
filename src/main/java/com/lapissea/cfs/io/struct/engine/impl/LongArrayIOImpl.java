package com.lapissea.cfs.io.struct.engine.impl;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.objects.NumberSize;

import java.io.IOException;
import java.lang.invoke.VarHandle;

public class LongArrayIOImpl extends VariableNode.ArrayVar<long[]>{
	
	private final VarHandle                   valueField;
	private final IOStruct.Get.Getter<long[]> getFun;
	private final IOStruct.Set.Setter<long[]> setFun;
	private final NumberSize                  elementSize;
	
	public LongArrayIOImpl(VarInfo info, VarHandle valueField, NumberSize elementSize, IOStruct.Get.Getter<long[]> getFun, IOStruct.Set.Setter<long[]> setFun){
		super(info, -1);
		this.elementSize=elementSize;
		this.valueField=valueField;
		this.getFun=getFun;
		this.setFun=setFun;
	}
	
	
	@Override
	protected long[] getValue(IOInstance source){
		if(getFun!=null) return getFun.getValue(source);
		else return (long[])valueField.get(source);
	}
	
	@Override
	protected void setValue(IOInstance target, long[] newValue){
		if(getFun!=null) setFun.setValue(target, newValue);
		else valueField.set(target, newValue);
	}
	
	@Override
	protected long[] makeArray(int size){
		return new long[size];
	}
	
	@Override
	protected long getElementsSize(IOInstance target, long[] value){
		return elementSize.bytes*value.length;
	}
	
	@Override
	protected void readElements(Cluster cluster, IOInstance target, ContentReader source, long[] array) throws IOException{
		for(int i=0;i<array.length;i++){
			array[i]=elementSize.read(source);
		}
	}
	
	@Override
	protected void writeElements(Cluster cluster, IOInstance target, ContentWriter dest, long[] array) throws IOException{
		for(long l : array){
			elementSize.write(dest, l);
		}
	}
	
	
}
