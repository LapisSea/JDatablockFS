package com.lapissea.fsf;

import java.io.IOException;

public class ChunkPointer extends FileObject implements Comparable<ChunkPointer>{
	
	public static ChunkPointer readNew(ContentInputStream src) throws IOException{
		ChunkPointer ptr=new ChunkPointer();
		ptr.read(src);
		return ptr;
	}
	
	public long value;
	
	public ChunkPointer(){
		this(-1);
	}
	
	@Override
	public void read(ContentInputStream dest) throws IOException{
		value=dest.readInt8();
	}
	
	public ChunkPointer(Chunk chunk){
		this(chunk.getOffset());
	}
	
	public ChunkPointer(long value){
		this.value=value;
	}
	
	@Override
	public void write(ContentOutputStream dest) throws IOException{
		dest.writeInt8(value);
	}
	
	@Override
	public long length(){
		return Long.BYTES;
	}
	
	@Override
	public int compareTo(ChunkPointer o){
		return Long.compare(value, o.value);
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		if(!(o instanceof ChunkPointer)) return false;
		var other=(ChunkPointer)o;
		return equals(other.value);
	}
	
	public boolean equals(long ptr){
		return value==ptr;
	}
	
	@Override
	public int hashCode(){
		return Long.hashCode(value);
	}
	
	@Override
	public String toString(){
		return "@"+value;
	}
	
	public Chunk dereference(Header header) throws IOException{
		return header.getByOffset(value);
	}
	
	public void set(Chunk chunk){
		value=chunk.getOffset();
	}
}
