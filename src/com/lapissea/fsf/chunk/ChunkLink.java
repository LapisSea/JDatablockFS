package com.lapissea.fsf.chunk;

public class ChunkLink{
	public final long         source;
	public final ChunkPointer pointer;
	
	public ChunkLink(ChunkPointer pointer, long source){
		this.pointer=pointer;
		this.source=source;
	}
}
