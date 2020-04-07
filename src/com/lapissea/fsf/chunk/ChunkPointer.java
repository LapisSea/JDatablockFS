package com.lapissea.fsf.chunk;

import com.lapissea.fsf.Header;
import com.lapissea.fsf.INumber;
import com.lapissea.fsf.NumberSize;
import com.lapissea.fsf.collections.IOList;
import com.lapissea.fsf.io.ContentReader;
import com.lapissea.fsf.io.ContentWriter;
import com.lapissea.fsf.io.serialization.FileObject;
import com.lapissea.util.NotNull;
import com.lapissea.util.Nullable;

import java.io.IOException;

public class ChunkPointer extends FileObject implements Comparable<ChunkPointer>, INumber{
	
	public static final IOList.PointerConverter<ChunkPointer> CONVERTER=IOList.PointerConverter.make(p->p, (old, ptr)->ptr);
	
	public static ChunkPointer newOrNull(long ptr){
		if(ptr==0) return null;
		return new ChunkPointer(ptr);
	}
	
	@Nullable
	public static ChunkPointer readOrNull(@NotNull NumberSize size, @NotNull ContentReader src) throws IOException{
		return newOrNull(size.read(src));
	}
	
	public static void writeNullable(@NotNull NumberSize size, @NotNull ContentWriter dest, @Nullable ChunkPointer ptr) throws IOException{
		size.write(dest, ptr==null?0:ptr.getValue());
	}
	
	protected long value;
	
	public ChunkPointer(){
		this(-1);
	}
	
	@Override
	public void read(ContentReader dest) throws IOException{
		value=dest.readInt8();
	}
	
	public ChunkPointer(Chunk chunk){
		this(chunk.getOffset());
	}
	
	public ChunkPointer(INumber value){
		this(value.getValue());
	}
	
	public ChunkPointer(long value){
		this.value=value;
	}
	
	@Override
	public void write(ContentWriter dest) throws IOException{
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
		return equals((ChunkPointer)o);
	}
	
	public boolean equals(ChunkPointer ptr){
		if(ptr==null) return false;
		return equals(ptr.getValue());
	}
	
	@Override
	public int hashCode(){
		return Long.hashCode(value);
	}
	
	@Override
	public String toString(){
		return "@"+value;
	}
	
	public Chunk dereference(Header<?> header) throws IOException{
		return header.getChunk(this);
	}
	
	@Override
	public long getValue(){
		return value;
	}
}
