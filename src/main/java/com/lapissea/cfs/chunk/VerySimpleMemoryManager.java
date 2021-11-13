package com.lapissea.cfs.chunk;

import com.lapissea.cfs.Utils;
import com.lapissea.util.NotImplementedException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class VerySimpleMemoryManager implements MemoryManager{
	
	private static final boolean PURGE_ACCIDENTAL=true;
	
	private final List<Chunk> freeChunks=new ArrayList<>();
	
	private final ChunkDataProvider context;
	
	public VerySimpleMemoryManager(ChunkDataProvider context){
		this.context=context;
	}
	
	@Override
	public void free(List<Chunk> toFree) throws IOException{
		List<Chunk> toAdd=toFree.stream().sorted(Comparator.comparingLong(c->c.getPtr().getValue())).collect(Collectors.toList());
		toAdd=MemoryOperations.mergeChunks(toAdd, PURGE_ACCIDENTAL);
		freeChunks.addAll(toAdd);
		
		var mergedFree=MemoryOperations.mergeChunks(freeChunks, false);
		freeChunks.clear();
		freeChunks.addAll(mergedFree);
	}
	
	
	@Override
	public void allocTo(Chunk firstChunk, Chunk target, long toAllocate) throws IOException{
		
		MemoryOperations.checkValidityOfChainAlloc(context, firstChunk, target);
		
		long remaining=toAllocate;
		while(remaining>0){
			long aloc;
			
			remaining-=(aloc=MemoryOperations.growFileAlloc(target, remaining));
			if(aloc>0) continue;
			remaining-=(aloc=MemoryOperations.allocateBySimpleNextAssign(this, target, remaining));
			if(aloc>0) continue;
			
			throw new NotImplementedException("Tried to allocate "+toAllocate+" bytes to "+target.getPtr()+" but there is no known way to do that");
		}
		
	}
	
	@Override
	public Chunk alloc(AllocateTicket ticket) throws IOException{
		
		Chunk chunk=null;
		
		if(chunk==null) chunk=MemoryOperations.allocateReuseFreeChunk(context, ticket, freeChunks);
		if(chunk==null) chunk=MemoryOperations.allocateAppendToFile(context, ticket);
		
		if(chunk==null) throw new NotImplementedException("Tried to allocate with "+ticket+" but there is no known way to do that");
		
		var src=context.getSource();
		
		try(var io=src.ioAt(chunk.getPtr().getValue())){
			chunk.writeHeader(io);
			Utils.zeroFill(io::write, chunk.getCapacity());
		}
		
		context.getChunkCache().add(chunk);
		
		var initial=ticket.dataPopulator();
		if(initial!=null){
			initial.accept(chunk);
		}
		
		return chunk;
	}
}
