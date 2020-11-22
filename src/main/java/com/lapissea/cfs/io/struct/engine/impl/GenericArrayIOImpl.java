package com.lapissea.cfs.io.struct.engine.impl;

import com.lapissea.cfs.Utils;
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
import java.util.Arrays;
import java.util.function.IntFunction;

public class GenericArrayIOImpl<T> extends VariableNode.ArrayVar<T[]>{
	
	private final VarHandle                valueField;
	private final IOStruct.Get.Getter<T[]> getFun;
	private final IOStruct.Set.Setter<T[]> setFun;
	private final IOStruct.Size.Sizer<T>   sizerFun;
	private final IOStruct.Read.Reader<T>  readFun;
	private final IOStruct.Write.Writer<T> writeFun;
	private final IntFunction<T[]>         newArray;
	
	public GenericArrayIOImpl(VarInfo info, VarHandle valueField, IOStruct.Get.Getter<T[]> getFun, IOStruct.Set.Setter<T[]> setFun, IOStruct.Size.Sizer<T> sizerFun, IOStruct.Read.Reader<T> readFun, IOStruct.Write.Writer<T> writeFun){
		super(info, -1);
		this.valueField=valueField;
		this.getFun=getFun;
		this.setFun=setFun;
		this.sizerFun=sizerFun;
		this.readFun=readFun;
		this.writeFun=writeFun;
		
		newArray=Utils.newArray((Class<T[]>)valueField.varType());
	}
	
	
	@Override
	protected T[] getValue(IOInstance source){
		if(getFun!=null) return getFun.getValue(source);
		else return (T[])valueField.get(source);
	}
	
	@Override
	protected void setValue(IOInstance target, T[] newValue){
		if(getFun!=null) setFun.setValue(target, newValue);
		else valueField.set(target, newValue);
	}
	
	@Override
	protected T[] makeArray(int size){
		return newArray.apply(size);
	}
	
	@Override
	protected long getElementsSize(IOInstance target, T[] value){
		return Arrays.stream(value).mapToLong(e->sizerFun.mapSize(target, e)).sum();
	}
	
	@Override
	protected void readElements(Cluster cluster, IOInstance target, ContentReader source, T[] array) throws IOException{
		for(int i=0;i<array.length;i++){
			array[i]=readFun.read(target, cluster, source, array[i]);
		}
	}
	
	@Override
	protected void writeElements(Cluster cluster, IOInstance target, ContentWriter dest, T[] array) throws IOException{
		for(T element : array){
			writeFun.write(target, cluster, dest, element);
		}
	}
	
	@Override
	protected int readSize(ContentReader source) throws IOException{
		var siz=FlagReader.readSingle(source, NumberSize.SMALEST_REAL, NumberSize.FLAG_INFO, false);
		return Math.toIntExact(siz.read(source));
	}
	
	@Override
	protected void writeSize(ContentWriter dest, T[] array) throws IOException{
		var siz=getNumSize(array);
		FlagWriter.writeSingle(dest, NumberSize.SMALEST_REAL, NumberSize.FLAG_INFO, false, siz);
		siz.write(dest, array.length);
	}
	@Override
	protected int headSize(T[] array){
		return NumberSize.SMALEST_REAL.bytes()+getNumSize(array).bytes();
	}
	private NumberSize getNumSize(T[] array){
		return NumberSize.bySize(array.length);
	}
	
	
}
