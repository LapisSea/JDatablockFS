package com.lapissea.cfs.io.struct.engine.impl;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.bit.BitInputStream;
import com.lapissea.cfs.io.bit.BitOutputStream;
import com.lapissea.cfs.io.bit.BitUtils;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.VariableNode;

import java.io.IOException;
import java.lang.invoke.VarHandle;

public class ArrayBoolFixedIOImpl extends VariableNode.ArrayVar.Fixed<boolean[]>{
	
	private final VarHandle                      valueField;
	private final IOStruct.Get.Getter<boolean[]> getFun;
	private final IOStruct.Set.Setter<boolean[]> setFun;
	
	public ArrayBoolFixedIOImpl(VarInfo info, int fixedElements, VarHandle valueField, IOStruct.Get.Getter<boolean[]> getFun, IOStruct.Set.Setter<boolean[]> setFun){
		super(info, fixedElements);
		this.valueField=valueField;
		this.getFun=getFun;
		this.setFun=setFun;
	}
	
	
	@Override
	protected boolean[] getValue(IOInstance source){
		if(getFun!=null) return getFun.getValue(source);
		else return (boolean[])valueField.get(source);
	}
	
	@Override
	protected void setValue(IOInstance target, boolean[] newValue){
		if(getFun!=null) setFun.setValue(target, newValue);
		else valueField.set(target, newValue);
	}
	
	@Override
	protected boolean[] makeArray(int size){
		return new boolean[size];
	}
	
	@Override
	protected void readElements(Cluster cluster, IOInstance target, ContentReader source, boolean[] array) throws IOException{
		try(var stream=new BitInputStream(source)){
			stream.readBits(array);
		}
	}
	
	@Override
	protected void writeElements(Cluster cluster, IOInstance target, ContentWriter dest, boolean[] array) throws IOException{
		try(var stream=new BitOutputStream(dest)){
			stream.writeBits(array);
		}
	}
	
	@Override
	protected long getFixedElementsSize(){
		return BitUtils.bitsToBytes(fixedArraySize);
	}
}
