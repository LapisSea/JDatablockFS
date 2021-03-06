package com.lapissea.cfs.io.struct.engine.impl;

import com.lapissea.cfs.cluster.Cluster;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.cfs.io.content.ContentWriter;
import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.io.struct.IOStruct;
import com.lapissea.cfs.io.struct.VariableNode;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.OptionalLong;

public class FixedGenericIOImpl<T> extends GenericIOImpl<T> implements VariableNode.FixedSize{
	
	private final int          size;
	private final OptionalLong sizeOpt;
	
	public FixedGenericIOImpl(VarInfo info, int size, Field valueField, IOStruct.Get.Getter<T> getFun, IOStruct.Set.Setter<T> setFun, IOStruct.Size.Sizer<T> sizerFun, IOStruct.Read.Reader<T> readFun, IOStruct.Write.Writer<T> writeFun){
		super(info, valueField, getFun, setFun, sizerFun, readFun, writeFun, OptionalLong.of(size));
		this.size=size;
		sizeOpt=OptionalLong.of(size);
	}
	
	
	@Override
	public final OptionalLong getMaximumSize(){
		return sizeOpt;
	}
	
	@Override
	public final long getSize(){
		return size;
	}
	
	/**
	 * Use {@link FixedSize#getSize()}
	 */
	@Deprecated
	@Override
	public final long mapSize(IOInstance target, T value){
		return getSize();
	}
	
	@Override
	public T read(IOInstance target, ContentReader source, T oldVal, Cluster cluster) throws IOException{
		if(!ContentReader.isDirect(source)&&getSize()>5){
			try(ContentReader buff=source.bufferExactRead(getSize())){
				return super.read(target, buff, oldVal, cluster);
			}
		}else{
			return super.read(target, source, oldVal, cluster);
		}
	}
	
	@Override
	public void write(IOInstance target, Cluster cluster, ContentWriter dest, T source) throws IOException{
		if(ContentWriter.isDirect(dest)){
			super.write(target, cluster, dest, source);
		}else{
			try(ContentWriter buff=dest.bufferExactWrite(getSize())){
				super.write(target, cluster, buff, source);
			}
		}
	}
}
