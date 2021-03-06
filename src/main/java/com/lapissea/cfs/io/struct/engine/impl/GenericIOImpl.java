package com.lapissea.cfs.io.struct.engine.impl;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.util.ShouldNeverHappenError;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.OptionalLong;

public class GenericIOImpl<T> extends VariableNode<T>{
	private final Field                    valueField;
	private final IOStruct.Get.Getter<T>   getFun;
	private final IOStruct.Set.Setter<T>   setFun;
	private final IOStruct.Size.Sizer<T>   sizerFun;
	private final IOStruct.Read.Reader<T>  readFun;
	private final IOStruct.Write.Writer<T> writeFun;
	private final OptionalLong             maxSize;
	
	public GenericIOImpl(VarInfo info,
	                     Field valueField,
	                     IOStruct.Get.Getter<T> getFun,
	                     IOStruct.Set.Setter<T> setFun,
	                     IOStruct.Size.Sizer<T> sizerFun,
	                     IOStruct.Read.Reader<T> readFun,
	                     IOStruct.Write.Writer<T> writeFun,
	                     OptionalLong maxSize){
		super(info);
		this.getFun=getFun;
		this.valueField=valueField;
		this.setFun=setFun;
		this.sizerFun=sizerFun;
		this.readFun=readFun;
		this.writeFun=writeFun;
		this.maxSize=maxSize;
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public T getValue(IOInstance source){
		if(getFun!=null){
			return getFun.getValue(source);
		}else{
			try{
				return (T)valueField.get(source);
			}catch(ReflectiveOperationException e){
				throw new ShouldNeverHappenError(e);
			}
		}
	}
	
	@Override
	public void setValue(IOInstance target, T value){
		if(setFun!=null){
			setFun.setValue(target, value);
		}else{
			try{
				valueField.set(target, value);
			}catch(ReflectiveOperationException e){
				throw new ShouldNeverHappenError(e);
			}
		}
	}
	@Override
	public long mapSize(IOInstance target, T value){
		return sizerFun.mapSize(target, value);
	}
	
	@Override
	public T read(IOInstance target, ContentReader source, T oldVal, Cluster cluster) throws IOException{
		return readFun.read(target, cluster, source, oldVal);
	}
	
	@Override
	public void write(IOInstance target, Cluster cluster, ContentWriter dest, T source) throws IOException{
		writeFun.write(target, cluster, dest, source);
	}
	@Override
	public OptionalLong getMaximumSize(){
		return maxSize;
	}
}
