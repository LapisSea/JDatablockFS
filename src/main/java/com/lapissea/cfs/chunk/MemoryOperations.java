package com.lapissea.cfs.chunk;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.BitDepthOutOfSpaceException;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
import org.roaringbitmap.RoaringBitmap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.PrimitiveIterator;
import java.util.stream.IntStream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public class MemoryOperations{
	
	public static void purgePossibleChunkHeaders(ChunkDataProvider provider, long from, long size) throws IOException{
		var maxHeaderSize=(int)Chunk.PIPE.getSizeDescriptor().requireMax(WordSpace.BYTE);
		
		RoaringBitmap possibleHeaders=new RoaringBitmap();
		try(var io=provider.getSource().read(from)){
			for(int i=0;i<size;i++){
				if(Chunk.earlyCheckChunkAt(io)){
					possibleHeaders.add(i);
				}
			}
		}
		
		boolean noTrim=false;
		
		while(!possibleHeaders.isEmpty()){
			
			int lastUnknown=-1;
			int removeCount=0;
			
			//test unknowns
			PrimitiveIterator.OfInt iter=(noTrim?IntStream.of(possibleHeaders.last()):possibleHeaders.stream()).iterator();
			while(iter.hasNext()){
				var headIndex=iter.nextInt();
				
				var pos=headIndex+from;
				
				try{
					Chunk.readChunk(provider, ChunkPointer.of(pos));
				}catch(Throwable e){
					//invalid only if last
					lastUnknown=Math.max(lastUnknown, headIndex);
					continue;
				}
				
				//known invalid - destroyed
				
				try(var io=provider.getSource().write(pos, false)){
					io.writeInt1(0);
				}
				possibleHeaders.remove(headIndex);
				removeCount++;
			}
			if(lastUnknown!=-1){
				possibleHeaders.remove(lastUnknown);
			}
			if(possibleHeaders.isEmpty()) break;
			
			if(removeCount>1) noTrim=false;
			if(noTrim) continue;
			
			noTrim=true;
			//pop alone headers, no change will make them valid
			iter=possibleHeaders.stream().iterator();
			int lastIndex=-maxHeaderSize*2;
			var index    =iter.nextInt();
			while(iter.hasNext()){
				var nextIndex=iter.nextInt();
				
				if(Math.min(Math.abs(lastIndex-index), Math.abs(nextIndex-index))>maxHeaderSize){
					possibleHeaders.remove(index);
					noTrim=false;
				}else{
					lastIndex=index;
				}
				index=nextIndex;
			}
		}
	}
	
	public static List<Chunk> mergeChunks(List<Chunk> data, boolean purgeAccidental) throws IOException{
		List<Chunk> chunks   =new ArrayList<>(data);
		List<Chunk> toDestroy=new ArrayList<>();
		iter:
		while(chunks.size()>1){
			for(var iter=chunks.iterator();iter.hasNext();){
				var chunk=iter.next();
				
				var optPrev=chunks.stream().filter(c->c.isNextPhysical(chunk)).findAny();
				if(optPrev.isPresent()){
					var prev=optPrev.get();
					
					var wholeSize=chunk.getHeaderSize()+chunk.getCapacity();
					prev.sizeSetZero();
					prev.setCapacityAndModifyNumSize(prev.getCapacity()+wholeSize);
					
					toDestroy.add(chunk);
					iter.remove();
					continue iter;
				}
				
				chunk.sizeSetZero();
			}
			
			break;
		}
		
		for(Chunk chunk : chunks){
			chunk.clearNextPtr();
			chunk.syncStruct();
			
			if(purgeAccidental){
				MemoryOperations.purgePossibleChunkHeaders(chunk.getChunkProvider(), chunk.dataStart(), chunk.getCapacity());
			}
		}
		
		if(!purgeAccidental){
			for(Chunk chunk : toDestroy){
				chunk.destroy(false);
			}
		}
		return chunks;
	}
	
	
	public static long growFileAlloc(Chunk target, long toAllocate) throws IOException{
//		LogUtil.println("growing file by:", toAllocate);
		
		ChunkDataProvider context=target.getChunkProvider();
		
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
	
	public static long allocateBySimpleNextAssign(MemoryManager manager, Chunk target, long toAllocate) throws IOException{
		var toPin=AllocateTicket.bytes(Math.max(toAllocate, 8)).withApproval(c->target.getNextSize().canFit(c.getPtr())).submit(manager);
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
	
	
	public static Chunk allocateReuseFreeChunk(ChunkDataProvider context, AllocateTicket ticket, Iterable<Chunk> freeChunks) throws IOException{
		for(Iterator<Chunk> iterator=freeChunks.iterator();iterator.hasNext();){
			Chunk c=iterator.next();
			if(c.getCapacity()<ticket.bytes()) continue;
			
			var freeSpace=c.getCapacity()-ticket.bytes();
			
			if(freeSpace>c.getHeaderSize()*2L){
				Chunk reallocate=chipEndProbe(context, ticket, c);
				if(reallocate!=null) return reallocate;
			}
			
			if(!ticket.disableResizing()&&freeSpace<8){
				if(ticket.approve(c)){
					iterator.remove();
					return c;
				}
			}
		}
		return null;
	}
	
	private static Chunk chipEndProbe(ChunkDataProvider context, AllocateTicket ticket, Chunk ch) throws IOException{
		var builder=new ChunkBuilder(context, ch.getPtr())
			.withCapacity(ticket.bytes())
			.withNext(ticket.next())
			.withExplicitNextSize(ticket.disableResizing()?NumberSize.VOID:NumberSize.bySize(ticket.next()).max(NumberSize.SHORT));
		
		var reallocate=builder.create();
		
		var siz=reallocate.totalSize();
		var end=ch.dataEnd();
		builder.withPtr(ChunkPointer.of(end-siz));
		reallocate=builder.create();
		
		assert reallocate.dataEnd()==ch.dataEnd();
		
		if(ticket.approve(reallocate)){
			reallocate.writeHeader();
			
			ch.setCapacityAndModifyNumSize(ch.getCapacity()-reallocate.totalSize());
			ch.writeHeader();
			return reallocate;
		}
		return null;
	}
	
	public static Chunk allocateAppendToFile(ChunkDataProvider context, AllocateTicket ticket) throws IOException{
		
		var src=context.getSource();
		
		var builder=new ChunkBuilder(context, ChunkPointer.of(src.getIOSize()))
			.withCapacity(ticket.bytes())
			.withNext(ticket.next())
			.withExplicitNextSize(ticket.disableResizing()?NumberSize.VOID:NumberSize.bySize(ticket.next()).max(NumberSize.SHORT));
		
		var chunk=builder.create();
		if(!ticket.approve(chunk)) return null;
		
		return chunk;
	}
	
	public static void checkValidityOfChainAlloc(ChunkDataProvider context, Chunk firstChunk, Chunk target) throws IOException{
		if(DEBUG_VALIDATION){
			assert firstChunk.getChunkProvider()==context;
			assert target.getChunkProvider()==context;
			
			var ptr=firstChunk.getPtr();
			
			var prev=new PhysicalChunkWalker(context.getFirstChunk())
				.stream()
				.filter(Chunk::hasNextPtr)
				.map(Chunk::getNextPtr)
				.filter(p->p.equals(ptr))
				.findAny();
			
			if(prev.isPresent()){
				var ch=context.getChunk(prev.get());
				throw new IllegalArgumentException(firstChunk+" is not the first chunk! "+ch+" declares it as next.");
			}
			
			if(firstChunk.streamNext().noneMatch(c->c==target)){
				throw new IllegalArgumentException(TextUtil.toString(target, "is in the chain of", firstChunk, "descendents:", firstChunk.collectNext()));
			}
		}
	}
}
