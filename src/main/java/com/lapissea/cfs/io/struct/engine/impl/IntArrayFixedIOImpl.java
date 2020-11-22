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

public class IntArrayFixedIOImpl extends VariableNode.ArrayVar.Fixed<int[]>{
	
	private final VarHandle                   valueField;
	private final IOStruct.Get.Getter<int[]> getFun;
	private final IOStruct.Set.Setter<int[]> setFun;
	private final NumberSize                  elementSize;
	
	public IntArrayFixedIOImpl(VarInfo info, int fixedElementSize, VarHandle valueField, NumberSize elementSize, IOStruct.Get.Getter<int[]> getFun, IOStruct.Set.Setter<int[]> setFun){
		super(info, fixedElementSize);
		this.elementSize=elementSize;
		this.valueField=valueField;
		this.getFun=getFun;
		this.setFun=setFun;
	}
	
	
	@Override
	protected int[] getValue(IOInstance source){
		if(getFun!=null) return getFun.getValue(source);
		else return (int[])valueField.get(source);
	}
	
	@Override
	protected void setValue(IOInstance target, int[] newValue){
		if(getFun!=null) setFun.setValue(target, newValue);
		else valueField.set(target, newValue);
	}
	
	@Override
	protected int[] makeArray(int size){
		return new int[size];
	}
	
	
	@Override
	protected void readElements(Cluster cluster, IOInstance target, ContentReader source, int[] array) throws IOException{
		for(int i=0;i<array.length;i++){
			array[i]=(int)elementSize.read(source);
		}
	}
	
	@Override
	protected void writeElements(Cluster cluster, IOInstance target, ContentWriter dest, int[] array) throws IOException{
		for(long l : array){
			elementSize.write(dest, l);
		}
	}
	
	@Override
	protected long getFixedElementsSize(){
		return elementSize.bytes*fixedArraySize;
	}
}
