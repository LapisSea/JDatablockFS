package com.lapissea.dfs.objects;

import com.lapissea.dfs.chunk.Chunk;
import com.lapissea.dfs.chunk.DataProvider;
import com.lapissea.dfs.io.content.ContentReader;
import com.lapissea.util.NotNull;

import java.io.IOException;

public final class ChunkPointer implements Comparable<ChunkPointer>{
	
	public static final ChunkPointer NULL = new ChunkPointer(0);
	
	@NotNull
	public static ChunkPointer read(NumberSize size, ContentReader src) throws IOException{
		return ChunkPointer.of(size.read(src));
	}
	
	@NotNull
	public static ChunkPointer of(long value){
		return value == 0? NULL : new ChunkPointer(value);
	}
	
	private final long value;
	
	private ChunkPointer(long value){
		if(value<0) throw new IllegalArgumentException();
		this.value = value;
	}
	
	public Chunk dereference(DataProvider provider) throws IOException{
		requireNonNull();
		return provider.getChunk(this);
	}
	
	@Override
	public String toString(){
		if(isNull()) return "NULL";
		return "*" + getValue();
	}
	
	public ChunkPointer addPtr(ChunkPointer value){
		requireNonNull();
		return addPtr(value.getValue());
	}
	
	public ChunkPointer addPtr(long value){
		requireNonNull();
		return new ChunkPointer(getValue() + value);
	}
	
	public long add(ChunkPointer value){
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
		       o instanceof ChunkPointer num &&
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
	
	public long getValue(){
		return value;
	}
	
	public int getValueInt(){
		return Math.toIntExact(getValue());
	}
	
	public boolean equals(long value){
		return getValue() == value;
	}
	
	public int compareTo(long o){
		return Long.compare(getValue(), o);
	}
}
