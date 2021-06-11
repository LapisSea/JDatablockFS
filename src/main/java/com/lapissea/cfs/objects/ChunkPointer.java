package com.lapissea.cfs.objects;

import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.util.NotNull;

import java.io.IOException;

public final class ChunkPointer implements INumber{
	
	public static ChunkPointer read(NumberSize size, ContentReader src) throws IOException{
		return ChunkPointer.of(size.read(src));
	}
	
	@NotNull
	public static ChunkPointer of(long value){
		return value==0?null:new ChunkPointer(value);
	}
	
	@Deprecated
	public static ChunkPointer of(ChunkPointer value){
		return value;
	}
	
	public static ChunkPointer of(INumber value){
		return new ChunkPointer(value.getValue());
	}
	
	public static long getValueNullable(ChunkPointer ptr){
		return ptr==null?0:ptr.getValue();
	}
	
	private final long value;
	
	private ChunkPointer(long value){
		if(value==0) throw new NullPointerException();
		if(value<0) throw new IllegalArgumentException();
		this.value=value;
	}
	
	public ChunkPointer(INumber value){
		this(value.getValue());
	}
	
	@Override
	public long getValue(){
		return value;
	}
	
	public Chunk dereference(Cluster cluster) throws IOException{
		return cluster.getChunk(this);
	}
	
	@Override
	public String toString(){
		return "*"+getValue();
	}
	
	public ChunkPointer addPtr(INumber value){
		return addPtr(value.getValue());
	}
	
	public ChunkPointer addPtr(long value){
		return new ChunkPointer(getValue()+value);
	}
	
	public long add(INumber value){
		return add(value.getValue());
	}
	
	public long add(long value){
		return getValue()+value;
	}
	
	@Override
	public boolean equals(Object o){
		return o==this||
		       o instanceof INumber num&&
		       equals(num.getValue());
	}
	
	@Override
	public int hashCode(){
		return Long.hashCode(getValue());
	}
	
}
