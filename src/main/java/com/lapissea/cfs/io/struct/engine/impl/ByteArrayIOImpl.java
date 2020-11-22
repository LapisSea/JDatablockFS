package com.lapissea.cfs.io.struct.engine.impl;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.VariableNode;

import java.io.IOException;
import java.lang.invoke.VarHandle;

public class ByteArrayIOImpl extends VariableNode.ArrayVar<byte[]>{
	
	private final VarHandle                   valueField;
	private final IOStruct.Get.Getter<byte[]> getFun;
	private final IOStruct.Set.Setter<byte[]> setFun;
	
	public ByteArrayIOImpl(VarInfo info, VarHandle valueField, IOStruct.Get.Getter<byte[]> getFun, IOStruct.Set.Setter<byte[]> setFun){
		super(info, -1);
		this.valueField=valueField;
		this.getFun=getFun;
		this.setFun=setFun;
	}
	
	
	@Override
	protected byte[] getValue(IOInstance source){
		if(getFun!=null) return getFun.getValue(source);
		else return (byte[])valueField.get(source);
	}
	
	@Override
	protected void setValue(IOInstance target, byte[] newValue){
		if(getFun!=null) setFun.setValue(target, newValue);
		else valueField.set(target, newValue);
	}
	
	@Override
	protected byte[] makeArray(int size){
		return new byte[size];
	}
	
	@Override
	protected long getElementsSize(IOInstance target, byte[] value){
		return value.length;
	}
	
	@Override
	protected void readElements(Cluster cluster, IOInstance target, ContentReader source, byte[] array) throws IOException{
		source.read(array);
	}
	
	@Override
	protected void writeElements(Cluster cluster, IOInstance target, ContentWriter dest, byte[] array) throws IOException{
		dest.write(array);
	}
	
	
}
