package com.lapissea.cfs.io.struct.engine.impl;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.objects.NumberSize;

import java.io.IOException;
import java.lang.invoke.VarHandle;

public class LongArrayVarSizeIOImpl extends VariableNode.ArrayVar<long[]>{
	
	private final VarHandle                   valueField;
	private final IOStruct.Get.Getter<long[]> getFun;
	private final IOStruct.Set.Setter<long[]> setFun;
	private final NumberSize                  defaultSize;
	private final VarHandle                   varSize;
	
	public LongArrayVarSizeIOImpl(VarInfo info, int fixedArraySize, VarHandle valueField, IOStruct.Get.Getter<long[]> getFun, IOStruct.Set.Setter<long[]> setFun, VarHandle varSize, NumberSize defaultSize){
		super(info, fixedArraySize);
		this.valueField=valueField;
		this.getFun=getFun;
		this.setFun=setFun;
		this.varSize=varSize;
		this.defaultSize=defaultSize==NumberSize.VOID?null:defaultSize;
	}
	
	private NumberSize getSize(IOInstance source){
		NumberSize size=(NumberSize)varSize.get(source);
		if(size==null) size=defaultSize;
		if(size==null) throw new NullPointerException(varSize+" must be non null if no default size is provided!");
		if(size==NumberSize.VOID) throw new IllegalStateException("Size can not be VOID!");
		return size;
	}
	
	@Override
	protected long[] getValue(IOInstance source){
		if(getFun!=null) return getFun.getValue(source);
		else return (long[])valueField.get(source);
	}
	
	@Override
	protected void setValue(IOInstance target, long[] newValue){
		if(setFun!=null) setFun.setValue(target, newValue);
		else valueField.set(target, newValue);
	}
	
	@Override
	protected long[] makeArray(int size){
		return new long[size];
	}
	
	@Override
	protected long getElementsSize(IOInstance target, long[] value){
		return value.length*getSize(target).bytes;
	}
	
	@Override
	protected void readElements(Cluster cluster, IOInstance target, ContentReader source, long[] array) throws IOException{
		var size=getSize(target);
		for(int i=0;i<array.length;i++){
			array[i]=size.read(source);
		}
	}
	
	@Override
	protected void writeElements(Cluster cluster, IOInstance target, ContentWriter dest, long[] array) throws IOException{
		var size=getSize(target);
		for(long l : array){
			size.write(dest, l);
		}
	}
	
	@Override
	protected int readSize(ContentReader source) throws IOException{
		var siz=FlagReader.readSingle(source, NumberSize.BYTE, NumberSize.FLAG_INFO, false);
		return Math.toIntExact(siz.read(source));
	}
	@Override
	protected void writeSize(ContentWriter dest, long[] array) throws IOException{
		var siz=NumberSize.bySize(array.length);
		FlagWriter.writeSingle(dest, NumberSize.BYTE, NumberSize.FLAG_INFO, false, siz);
		siz.write(dest, array.length);
	}
	@Override
	protected int headSize(long[] array){
		return NumberSize.bySize(array.length).bytes;
	}
}
