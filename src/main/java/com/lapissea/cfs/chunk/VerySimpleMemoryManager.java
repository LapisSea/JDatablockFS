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
	
	private long growFileAloc(Chunk target, long toAllocate) throws IOException{
//		LogUtil.println("growing file by:", toAllocate);
		
		if(context.isLastPhysical(target)){
			var remaining=target.getBodyNumSize().remaining(target.getCapacity());
			var toGrow   =Math.min(toAllocate, remaining);
			if(toGrow>0){
				try(var io=context.getSource().io()){
					io.setCapacity(io.getCapacity()+toGrow);
				}
				target.modifyAndSave(ch->ch.setCapacity(ch.getCapacity()+toGrow));
				return toGrow;
			}
		}
		return 0;
	}
	
	@Override
	public void allocTo(Chunk firstChunk, Chunk target, long toAllocate) throws IOException{
		
		long remaining=toAllocate;
		while(remaining>0){
			long aloc;
			
			remaining-=(aloc=growFileAloc(target, remaining));
			if(aloc>0) continue;
			
			throw new NotImplementedException();
		}
		
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
