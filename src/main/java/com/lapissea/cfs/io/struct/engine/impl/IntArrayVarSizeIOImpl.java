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

public class IntArrayVarSizeIOImpl extends VariableNode.ArrayVar<int[]>{
	
	private final VarHandle                  valueField;
	private final IOStruct.Get.Getter<int[]> getFun;
	private final IOStruct.Set.Setter<int[]> setFun;
	private final NumberSize                 defaultSize;
	private final VarHandle                  varSize;
	
	public IntArrayVarSizeIOImpl(VarInfo info, int fixedArraySize, VarHandle valueField, IOStruct.Get.Getter<int[]> getFun, IOStruct.Set.Setter<int[]> setFun, VarHandle varSize, NumberSize defaultSize){
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
//		if(size==NumberSize.VOID) throw new IllegalStateException("Size can not be VOID!");
		return size;
	}
	
	@Override
	protected int[] getValue(IOInstance source){
		if(getFun!=null) return getFun.getValue(source);
		else return (int[])valueField.get(source);
	}
	
	@Override
	protected void setValue(IOInstance target, int[] newValue){
		if(setFun!=null) setFun.setValue(target, newValue);
		else valueField.set(target, newValue);
	}
	
	@Override
	protected int[] makeArray(int size){
		return new int[size];
	}
	
	@Override
	protected long getElementsSize(IOInstance target, int[] value){
		return value.length*getSize(target).bytes;
	}
	
	@Override
	protected void readElements(Cluster cluster, IOInstance target, ContentReader source, int[] array) throws IOException{
		var size=getSize(target);
		for(int i=0;i<array.length;i++){
			array[i]=(int)size.read(source);
		}
	}
	
	@Override
	protected void writeElements(Cluster cluster, IOInstance target, ContentWriter dest, int[] array) throws IOException{
		var size=getSize(target);
		for(long l : array){
			size.write(dest, l);
		}
	}
	
	@Override
	protected int readSize(ContentReader source) throws IOException{
		var siz=FlagReader.readSingle(source, NumberSize.SMALEST_REAL, NumberSize.FLAG_INFO, false);
		return Math.toIntExact(siz.read(source));
	}
	@Override
	protected void writeSize(ContentWriter dest, int[] array) throws IOException{
		var siz=getNumSize(array);
		FlagWriter.writeSingle(dest, NumberSize.SMALEST_REAL, NumberSize.FLAG_INFO, false, siz);
		siz.write(dest, array.length);
	}
	@Override
	protected int headSize(int[] array){
		return NumberSize.SMALEST_REAL.bytes()+getNumSize(array).bytes();
	}
	private NumberSize getNumSize(int[] array){
		return NumberSize.bySize(array.length);
	}
}
