package com.lapissea.cfs.chunk;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.BitDepthOutOfSpaceException;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
import org.roaringbitmap.RoaringBitmap;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
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
	
	private static long binaryFindWedge(IOList<ChunkPointer> a, ChunkPointer key) throws IOException{
		long low =0;
		long high=a.size()-1;
		
		while(low<=high){
			long mid   =(low+high) >>> 1;
			var  midVal=a.get(mid);
			
			int cmp;
			int cmp1=midVal.compareTo(key);
			if(cmp1<0){
				var nextMid=mid+1;
				if(nextMid>=a.size()){
					return nextMid;
				}
				var nextMidVal=a.get(nextMid);
				int cmp2      =nextMidVal.compareTo(key);
				if(cmp2>=0){
					return nextMid;
				}
				cmp=cmp1;
			}else if(mid==0){
				return mid;
			}else{
				cmp=cmp1;
			}
			
			if(cmp<0) low=mid+1;
			else if(cmp>0) high=mid-1;
			else return mid; // key found
		}
		return -1;  // key not found.
	}
	
	public static void mergeFreeChunksSorted(ChunkDataProvider provider, IOList<ChunkPointer> data, List<Chunk> newData) throws IOException{
		for(Chunk newCh : newData){
			checkOptimal(provider, data);
			
			var newPtr=newCh.getPtr();
			if(data.isEmpty()){
				data.add(newPtr);
				continue;
			}
			var insertIndex=binaryFindWedge(data, newPtr);
			if(insertIndex==0){
				var existing=data.get(0).dereference(provider);
				var next    =newCh;
				if(existing.compareTo(next)<0){
					var tmp=next;
					next=existing;
					existing=tmp;
				}
				if(next.isNextPhysical(existing)){
					freeListReplace(data, 0, newCh);
					mergeFreeChunks(next, existing);
					//check if next element in free list is now next physical and merge+remove from list
					if(data.size()>1){
						var ptr=data.get(1);
						if(existing.isNextPhysical(ptr)){
							var ch=ptr.dereference(provider);
							data.remove(1);
							mergeFreeChunks(existing, ch);
						}
					}
					continue;
				}
				
				freeListAdd(data, insertIndex, newCh);
				continue;
			}
			
			var prev=data.get(insertIndex-1).dereference(provider);
			if(prev.isNextPhysical(newCh)){
				mergeFreeChunks(prev, newCh);
				//check if next element in free list is now next physical and merge+remove from list
				if(data.size()>insertIndex){
					var ch=data.get(insertIndex).dereference(provider);
					if(prev.isNextPhysical(ch)){
						data.remove(insertIndex);
						mergeFreeChunks(prev, ch);
					}
				}
			}else{
				if(data.size()>insertIndex){
					var next=data.get(insertIndex).dereference(provider);
					
					if(newCh.isNextPhysical(next)){
						freeListReplace(data, insertIndex, newCh);
						mergeFreeChunks(newCh, next);
						continue;
					}
				}
				freeListAdd(data, insertIndex, newCh);
			}
		}
		checkOptimal(provider, data);
	}
	
	private static void freeListReplace(IOList<ChunkPointer> data, long replaceIndex, Chunk newCh) throws IOException{
		clearFree(newCh);
		data.set(replaceIndex, newCh.getPtr());
	}
	private static void freeListAdd(IOList<ChunkPointer> data, long insertIndex, Chunk newCh) throws IOException{
		clearFree(newCh);
		data.add(insertIndex, newCh.getPtr());
	}
	
	private static void checkOptimal(ChunkDataProvider provider, IOList<ChunkPointer> data) throws IOException{
		ChunkPointer last=null;
		for(ChunkPointer val : data){
			if(last!=null){
				assert last.compareTo(val)<0:last+" "+val+" "+data;
				var prev=last.dereference(provider);
				var c   =val.dereference(provider);
				assert !prev.isNextPhysical(c):prev+" "+c+" "+data;
			}
			last=val;
		}
	}
	private static void clearFree(Chunk newCh) throws IOException{
		newCh.sizeSetZero();
		newCh.clearNextPtr();
		newCh.syncStruct();
	}
	
	private static void mergeFreeChunks(Chunk prev, Chunk next) throws IOException{
		prepareFreeChunkMerge(prev, next);
		prev.syncStruct();
		next.destroy(true);
		next.getChunkProvider().getChunkCache().notifyDestroyed(next);
	}
	
	private static void prepareFreeChunkMerge(Chunk prev, Chunk next){
		var wholeSize=next.getHeaderSize()+next.getCapacity();
		prev.setCapacityAndModifyNumSize(prev.getCapacity()+wholeSize);
		assert prev.dataEnd()==next.dataEnd();
	}
	
	public static List<Chunk> mergeChunks(Collection<Chunk> data, boolean purgeAccidental) throws IOException{
		List<Chunk> chunks=new ArrayList<>(data);
		chunks.sort(Chunk::compareTo);
		List<Chunk> toDestroy=new ArrayList<>();
		iter:
		while(chunks.size()>1){
			for(var iter=chunks.iterator();iter.hasNext();){
				var chunk=iter.next();
				
				var optPrev=chunks.stream().filter(c->c.isNextPhysical(chunk)).findAny();
				if(optPrev.isPresent()){
					var prev=optPrev.get();
					prepareFreeChunkMerge(prev, chunk);
					toDestroy.add(chunk);
					iter.remove();
					continue iter;
				}
				
				chunk.sizeSetZero();
			}
			
			break;
		}
		
		for(Chunk chunk : chunks){
			clearFree(chunk);
			
			if(purgeAccidental){
				purgePossibleChunkHeaders(chunk.getChunkProvider(), chunk.dataStart(), chunk.getCapacity());
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
	
	
	public static Chunk allocateReuseFreeChunk(ChunkDataProvider context, AllocateTicket ticket) throws IOException{
		for(var iterator=context.getMemoryManager().getFreeChunks().iterator();iterator.hasNext();){
			Chunk c=iterator.next().dereference(context);
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
		
		try(var io=src.ioAt(chunk.getPtr().getValue())){
			chunk.writeHeader(io);
			Utils.zeroFill(io::write, chunk.getCapacity());
		}
		
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
