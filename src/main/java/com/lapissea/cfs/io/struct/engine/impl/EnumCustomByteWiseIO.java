package com.lapissea.cfs.io.struct.engine.impl;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.bit.EnumUniverse;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct.Get.Getter;
import com.lapissea.cfs.io.struct.IOStruct.Read.Reader;
import com.lapissea.cfs.io.struct.IOStruct.Set.Setter;
import com.lapissea.cfs.io.struct.IOStruct.Write.Writer;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.objects.NumberSize;

import java.io.IOException;
import java.lang.invoke.VarHandle;
import java.util.Objects;

public class EnumCustomByteWiseIO<T extends Enum<T>> extends VariableNode.FixedSize.Node<T>{
	private final VarHandle       valueField;
	private final EnumUniverse<T> flagInfo;
	private final NumberSize      numberSize;
	private final Getter<T>       getFun;
	private final Setter<T>       setFun;
	private final Reader<T>       readFun;
	private final Writer<T>       writeFun;
	private final boolean         nullable;
	
	public EnumCustomByteWiseIO(VarInfo info, boolean nullable, int bytes, VarHandle valueField, EnumUniverse<T> flagInfo, NumberSize numberSize, Getter<T> getFun, Setter<T> setFun, Reader<T> readFun, Writer<T> writeFun){
		super(info, bytes);
		this.nullable=nullable;
		this.valueField=valueField;
		this.flagInfo=flagInfo;
		this.numberSize=numberSize;
		this.getFun=getFun;
		this.setFun=setFun;
		this.readFun=readFun;
		this.writeFun=writeFun;
	}
	
	@Override
	@SuppressWarnings("unchecked")
	public T getValue(IOInstance source){
		if(getFun!=null) return getFun.getValue(source);
		else return (T)valueField.get(source);
		
	}
	
	@Override
	public void setValue(IOInstance target, T value){
		if(!nullable) Objects.requireNonNull(value);
		
		if(setFun!=null) setFun.setValue(target, value);
		else valueField.set(target, value);
	}
	
	@Override
	public T read(IOInstance target, ContentReader source, T oldVal, Cluster cluster) throws IOException{
		if(readFun!=null){
			return readFun.read(target, cluster, source, oldVal);
		}else{
			return FlagReader.readSingle(source, numberSize, flagInfo, nullable);
		}
	}
	
	@Override
	public void write(IOInstance target, Cluster cluster, ContentWriter dest, T source) throws IOException{
		if(!nullable) Objects.requireNonNull(source);
		
		if(writeFun!=null){
			writeFun.write(target, cluster, dest, source);
		}else{
			FlagWriter.writeSingle(dest, numberSize, flagInfo, nullable, source);
		}
	}
}
