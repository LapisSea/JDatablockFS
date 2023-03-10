package com.lapissea.cfs.objects;

import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.content.ContentReader;
import com.lapissea.util.NotNull;

import java.io.IOException;

public final class ChunkPointer implements INumber, Comparable<ChunkPointer>{
	
	public static final ChunkPointer NULL = new ChunkPointer(0);
	
	@NotNull
	public static ChunkPointer read(NumberSize size, ContentReader src) throws IOException{
		return ChunkPointer.of(size.read(src));
	}
	
	@NotNull
	public static ChunkPointer of(long value){
		return value == 0? NULL : new ChunkPointer(value);
	}
	
	@Deprecated
	public static ChunkPointer of(ChunkPointer value){
		return value;
	}
	
	public static ChunkPointer of(INumber value){
		return of(value.getValue());
	}
	
	private final long value;
	
	private ChunkPointer(long value){
		if(value<0) throw new IllegalArgumentException();
		this.value = value;
	}
	
	@Override
	public long getValue(){
		return value;
	}
	
	public Chunk dereference(DataProvider provider) throws IOException{
		requireNonNull();
		return provider.getChunk(this);
	}
	
	@Override
	public String toString(){
		if(isNull()) return "NULL";
		
		if(getValue()>9999){
			var h = Long.toHexString(getValue()).toUpperCase();
			return "x" + h;
		}
		return "*" + getValue();
	}
	
	public ChunkPointer addPtr(INumber value){
		requireNonNull();
		return addPtr(value.getValue());
	}
	
	public ChunkPointer addPtr(long value){
		requireNonNull();
		return new ChunkPointer(getValue() + value);
	}
	
	public long add(INumber value){
		requireNonNull();
		return add(value.getValue());
	}
	
	public long add(long value){
		requireNonNull();
		return getValue() + value;
	}
	
	@Override
	public boolean equals(Object o){
		return o == this ||
		       o instanceof INumber num &&
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
		if(isNull()) throw new NullPointerException("Pointer is null");
	}
	public boolean isNull(){
		return getValue() == 0;
	}
	
	@Override
	public int compareTo(ChunkPointer o){
		return compareTo(o.getValue());
	}
}
