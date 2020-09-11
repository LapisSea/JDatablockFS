package com.lapissea.cfs.io.struct.engine.impl;

import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.VariableNode;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.OptionalLong;

public class FixedGenericIOImpl<T> extends GenericIOImpl<T> implements VariableNode.FixedSize{
	
	private final int size;
	
	public FixedGenericIOImpl(String name, int size, Field valueField, IOStruct.Get.Getter<T> getFun, IOStruct.Set.Setter<T> setFun, IOStruct.Size.Sizer<T> sizerFun, IOStruct.Read.Reader<T> readFun, IOStruct.Write.Writer<T> writeFun){
		super(name, valueField, getFun, setFun, sizerFun, readFun, writeFun, OptionalLong.of(size));
		this.size=size;
	}
	
	
	@Override
	public OptionalLong getMaximumSize(){
		return OptionalLong.of(getSize());
	}
	
	@Override
	public long getSize(){
		return size;
	}
	
	/**
	 * Use {@link FixedSize#getSize()}
	 */
	@Deprecated
	@Override
	public long mapSize(IOStruct.Instance target, T value){
		return getSize();
	}
	
	@Override
	public T read(IOStruct.Instance target, ContentReader source, T oldVal) throws IOException{
		if(!ContentReader.isDirect(source)&&getSize()>5){
			try(ContentReader buff=source.bufferExactRead(getSize())){
				return super.read(target, buff, oldVal);
			}
		}else{
			return super.read(target, source, oldVal);
		}
	}
	
	@Override
	public void write(IOStruct.Instance target, ContentWriter dest, T source) throws IOException{
		if(ContentWriter.isDirect(dest)){
			super.write(target, dest, source);
		}else{
			try(ContentWriter buff=dest.bufferExactWrite(getSize())){
				super.write(target, buff, source);
			}
		}
	}
}
