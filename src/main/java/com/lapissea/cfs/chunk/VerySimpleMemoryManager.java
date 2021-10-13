package com.lapissea.cfs.chunk;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.BitDepthOutOfSpaceException;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;

import java.io.IOException;
import java.util.List;

import static com.lapissea.cfs.GlobalConfig.*;

public class VerySimpleMemoryManager implements MemoryManager{
	
	private final ChunkDataProvider context;
	
	public VerySimpleMemoryManager(ChunkDataProvider context){
		this.context=context;
	}
	
	@Override
	public void free(List<Chunk> tofree) throws IOException{
		throw new IOException("not supported");
	}
	
	private long growFileAloc(Chunk target, long toAllocate) throws IOException{
//		LogUtil.println("growing file by:", toAllocate);
		
		if(context.isLastPhysical(target)){
			var remaining=target.getBodyNumSize().remaining(target.getCapacity());
			var toGrow   =Math.min(toAllocate, remaining);
			if(toGrow>0){
				try(var io=context.getSource().io()){
					var old=io.getCapacity();
					io.setCapacity(old+toGrow);
					io.setPos(old);
					Utils.zeroFill(io::write, toGrow);
				}
				target.modifyAndSave(ch->{
					try{
						ch.setCapacity(ch.getCapacity()+toGrow);
					}catch(BitDepthOutOfSpaceException e){
						/*
						 * toGrow is clamped to the remaining bitspace of body num size. If this happens
						 * something has gone horribly wrong and life choices should be reconsidered
						 * */
						throw new ShouldNeverHappenError(e);
					}
				});
				return toGrow;
			}
		}
		return 0;
	}
	private long simplePin(Chunk target, long toAllocate) throws IOException{
		var toPin=AllocateTicket.bytes(Math.max(toAllocate, 8)).withApproval(c->target.getNextSize().canFit(c.getPtr())).submit(this);
		if(toPin==null) return 0;
		target.modifyAndSave(c->{
			try{
				c.setNextPtr(toPin.getPtr());
			}catch(BitDepthOutOfSpaceException e){
				throw new ShouldNeverHappenError(e);
			}
		});
		return toPin.getCapacity();
	}
	
	@Override
	public void allocTo(Chunk firstChunk, Chunk target, long toAllocate) throws IOException{
		
		if(DEBUG_VALIDATION){
			var ptr =firstChunk.getPtr();
			var prev=new PhysicalChunkWalker(context.getFirstChunk()).stream().filter(Chunk::hasNextPtr).map(Chunk::getNextPtr).filter(p->p.equals(ptr)).findAny();
			if(prev.isPresent()){
				var ch=context.getChunk(prev.get());
				throw new IllegalArgumentException(firstChunk+" is not the first chunk! "+ch+" declares it as next.");
			}
			
			if(firstChunk.streamNext().noneMatch(c->c==target)){
				throw new IllegalArgumentException(TextUtil.toString(target, "is in the chain of", firstChunk, "descendents:", firstChunk.collectNext()));
			}
		}
		
		long remaining=toAllocate;
		while(remaining>0){
			long aloc;
			
			remaining-=(aloc=growFileAloc(target, remaining));
			if(aloc>0) continue;
			remaining-=(aloc=simplePin(target, remaining));
			if(aloc>0) continue;
			
			throw new NotImplementedException("Tried to allocate "+toAllocate+" bytes to "+target.getPtr()+" but there is no known way to do that");
		}
		
	}
	@Override
	public Chunk alloc(AllocateTicket ticket) throws IOException{
		var src=context.getSource();
		var siz=src.getIOSize();
		var builder=new ChunkBuilder(context, ChunkPointer.of(siz))
			.withCapacity(ticket.bytes())
			.withNext(ticket.next())
			.withExplicitNextSize(ticket.disableResizing()?NumberSize.VOID:NumberSize.bySize(ticket.next()).max(NumberSize.SHORT));
		var chunk=builder.create();
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
