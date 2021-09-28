package com.lapissea.cfs.chunk;

import java.io.IOException;
import java.util.List;

public interface MemoryManager{
	
	default void free(Chunk toFree) throws IOException{
		free(List.of(toFree));
	}
	void free(List<Chunk> toFree) throws IOException;
	
	void allocTo(Chunk firstChunk, Chunk target, long toAllocate) throws IOException;
	
	Chunk alloc(AllocateTicket ticket) throws IOException;
}
