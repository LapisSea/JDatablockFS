package com.lapissea.fsf;

public class SourcedPointer{
	public final long         source;
	public final ChunkPointer pointer;
	
	public SourcedPointer(ChunkPointer pointer, long source){
		this.pointer=pointer;
		this.source=source;
	}
}
