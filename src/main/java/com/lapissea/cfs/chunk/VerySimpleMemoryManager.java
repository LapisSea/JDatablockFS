package com.lapissea.cfs.chunk;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.util.NotImplementedException;

import java.io.IOException;
import java.util.List;

public class VerySimpleMemoryManager implements MemoryManager{
	
	private final ChunkDataProvider context;
	
	public VerySimpleMemoryManager(ChunkDataProvider context){
		this.context=context;
	}
	
	@Override
	public void free(List<Chunk> tofree){
		throw NotImplementedException.infer();
	}
	@Override
	public void allocTo(Chunk firstChunk, Chunk target, long toAllocate) throws IOException{
		throw NotImplementedException.infer();
	}
	@Override
	public Chunk alloc(AllocateTicket ticket) throws IOException{
		var src=context.getSource();
		
		var siz    =src.getIOSize();
		var builder=new ChunkBuilder(context, ChunkPointer.of(siz)).withCapacity(ticket.bytes()).withNext(ticket.next());
		var chunk  =builder.create();
		if(!ticket.approve(chunk)) return null;
		
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
