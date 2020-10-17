package com.lapissea.cfs.io.struct.engine.impl;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.bit.EnumFlag;
import com.lapissea.cfs.io.bit.FlagReader;
import com.lapissea.cfs.io.bit.FlagWriter;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.VariableNode;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.util.ShouldNeverHappenError;

import java.io.IOException;
import java.lang.reflect.Field;

public class EnumCustomByteWiseIO<T extends Enum<T>> extends VariableNode.FixedSize.Node<T>{
	private final Field                    valueField;
	private final EnumFlag<T>              flagInfo;
	private final NumberSize               numberSize;
	private final IOStruct.Get.Getter<T>   getFun;
	private final IOStruct.Set.Setter<T>   setFun;
	private final IOStruct.Read.Reader<T>  readFun;
	private final IOStruct.Write.Writer<T> writeFun;
	
	public EnumCustomByteWiseIO(String name, int index, int bytes, Field valueField, EnumFlag<T> flagInfo, NumberSize numberSize, IOStruct.Get.Getter<T> getFun, IOStruct.Set.Setter<T> setFun, IOStruct.Read.Reader<T> readFun, IOStruct.Write.Writer<T> writeFun){
		super(name, index, bytes);
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
	public T read(IOInstance target, ContentReader source, T oldVal, Cluster cluster) throws IOException{
		if(readFun!=null){
			return readFun.read(target, cluster, source, oldVal);
		}else{
			try(var flags=FlagReader.read(source, numberSize)){
				return flagInfo.read(flags);
			}
		}
	}
	
	@Override
	public void write(IOInstance target, Cluster cluster, ContentWriter dest, T source) throws IOException{
		if(writeFun!=null){
			writeFun.write(target, cluster, dest, source);
		}else{
			try(var flags=new FlagWriter.AutoPop(numberSize, dest)){
				flags.writeEnum(flagInfo, source);
			}
		}
	}
}
