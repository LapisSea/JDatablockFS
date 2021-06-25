package com.lapissea.cfs.objects;

import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.util.NotNull;

import java.io.IOException;

public final class ChunkPointer implements INumber{
	
	public static final ChunkPointer NULL=new ChunkPointer(0);
	
	public static ChunkPointer read(NumberSize size, ContentReader src) throws IOException{
		return ChunkPointer.of(size.read(src));
	}
	
	@NotNull
	public static ChunkPointer of(long value){
		return value==0?NULL:new ChunkPointer(value);
	}
	
	@Deprecated
	public static ChunkPointer of(ChunkPointer value){
		return value;
	}
	
	public static ChunkPointer of(INumber value){
		return of(value.getValue());
	}
	
	public static long getValueNullable(ChunkPointer ptr){
		return ptr==null?0:ptr.getValue();
	}
	
	private final long value;
	
	private ChunkPointer(long value){
		if(value<0) throw new IllegalArgumentException();
		this.value=value;
	}
	
	@Override
	public long getValue(){
		return value;
	}
	
	public Chunk dereference(ChunkDataProvider provider) throws IOException{
		requireNonNull();
		return provider.getChunk(this);
	}
	
	@Override
	public String toString(){
		return isNull()?"NULL":"*"+getValue();
	}
	
	public ChunkPointer addPtr(INumber value){
		requireNonNull();
		return addPtr(value.getValue());
	}
	
	public ChunkPointer addPtr(long value){
		requireNonNull();
		return new ChunkPointer(getValue()+value);
	}
	
	public long add(INumber value){
		requireNonNull();
		return add(value.getValue());
	}
	
	public long add(long value){
		requireNonNull();
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
	
	public Reference makeReference(){
		return makeReference(0);
	}
	public Reference makeReference(long offset){
		requireNonNull();
		return new Reference(this, offset);
	}
	
	public void requireNonNull(){
		if(isNull()) throw new NullPointerException();
	}
	public boolean isNull(){
		return getValue()==0;
	}
}
