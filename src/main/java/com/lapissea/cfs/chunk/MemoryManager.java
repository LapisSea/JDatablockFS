package com.lapissea.cfs.chunk;

import java.io.IOException;
import java.util.List;

public interface MemoryManager{
	
	default void free(Chunk tofree){
		free(List.of(tofree));
	}
	void free(List<Chunk> tofree);
	
	void allocTo(Chunk firstChunk, Chunk target, long toAllocate) throws IOException;
	
	Chunk alloc(AllocateTicket ticket) throws IOException;
}
