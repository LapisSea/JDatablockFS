package com.lapissea.cfs.chunk;

import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.exceptions.BitDepthOutOfSpaceException;
import com.lapissea.cfs.io.IOInterface;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.NumberSize;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.objects.collections.IOList.IOIterator;
import com.lapissea.cfs.type.WordSpace;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.LongStream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

public class MemoryOperations{
	
	public static void purgePossibleChunkHeaders(DataProvider provider, long from, long size) throws IOException{
		var maxHeaderSize=(int)Chunk.PIPE.getSizeDescriptor().requireMax(WordSpace.BYTE);
		
		ChunkSet possibleHeaders=new ChunkSet();
		try(var io=provider.getSource().read(from)){
			for(int i=0;i<size;i++){
				if(Chunk.earlyCheckChunkAt(io)){
					possibleHeaders.add(i);
				}
			}
		}
		
		boolean noTrim=false;
		
		List<RandomIO.WriteChunk> writes=new ArrayList<>();
		byte[]                    b1    ={0};
		
		while(!possibleHeaders.isEmpty()){
			
			long lastUnknown=-1;
			int  removeCount=0;
			
			
			//test unknowns
			var iter=(noTrim?LongStream.of(possibleHeaders.last().getValue()):possibleHeaders.longStream()).iterator();
			while(iter.hasNext()){
				var headIndex=iter.nextLong();
				
				var pos=headIndex+from;
				
				try{
					Chunk.readChunk(provider, ChunkPointer.of(pos));
				}catch(Throwable e){
					//invalid only if last
					lastUnknown=Math.max(lastUnknown, headIndex);
					continue;
				}
				
				//known invalid - destroyed
				writes.add(new RandomIO.WriteChunk(pos, b1));
				possibleHeaders.remove(headIndex);
				removeCount++;
			}
			if(!writes.isEmpty()){
				try(var io=provider.getSource().io()){
					io.writeAtOffsets(writes);
				}
				writes.clear();
			}
			
			if(lastUnknown!=-1){
				possibleHeaders.remove(lastUnknown);
			}
			if(possibleHeaders.isEmpty()) break;
			
			if(removeCount>1) noTrim=false;
			if(noTrim) continue;
			
			noTrim=true;
			//pop alone headers, no change will make them valid
			iter=possibleHeaders.longStream().iterator();
			long lastIndex=-maxHeaderSize*2L;
			var  index    =iter.nextLong();
			while(iter.hasNext()){
				var nextIndex=iter.nextLong();
				
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
	
	public static void mergeFreeChunksSorted(DataProvider provider, IOList<ChunkPointer> data, List<Chunk> newData) throws IOException{
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
	
	private static void checkOptimal(DataProvider provider, IOList<ChunkPointer> data) throws IOException{
		if(!DEBUG_VALIDATION) return;
		
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
		newCh.setSize(0);
		newCh.clearNextPtr();
		newCh.syncStruct();
	}
	
	private static void mergeFreeChunks(Chunk prev, Chunk next) throws IOException{
		prepareFreeChunkMerge(prev, next);
		prev.syncStruct();
		next.destroy(true);
	}
	
	private static void prepareFreeChunkMerge(Chunk prev, Chunk next){
		var wholeSize=next.getHeaderSize()+next.getCapacity();
		prev.setCapacityAndModifyNumSize(prev.getCapacity()+wholeSize);
		assert prev.dataEnd()==next.dataEnd();
	}
	
	
	private static final boolean PURGE_ACCIDENTAL=UtilL.sysPropertyByClass(MemoryOperations.class, "PURGE_ACCIDENTAL", GlobalConfig.DEBUG_VALIDATION, Boolean::valueOf);
	
	public static List<Chunk> mergeChunks(Collection<Chunk> data) throws IOException{
		List<Chunk> chunks=new ArrayList<>(data);
		chunks.sort(Chunk::compareTo);
		List<Chunk> toDestroy=new ArrayList<>();
		
		while(chunks.size()>1){
			boolean noChange=true;
			
			var chunk=chunks.get(chunks.size()-1);
			for(int i=chunks.size()-2;i>=0;i--){
				var prev=chunks.get(i);
				if(prev.isNextPhysical(chunk)){
					prepareFreeChunkMerge(prev, chunk);
					toDestroy.add(chunk);
					chunks.remove(i+1);
					noChange=false;
				}
				chunk=prev;
			}
			if(noChange){
				break;
			}
		}
		
		ExecutorService service=null;
		
		for(Chunk chunk : chunks){
			clearFree(chunk);
			
			if(PURGE_ACCIDENTAL){
				if(chunk.getCapacity()>3000){
					if(service==null) service=Executors.newWorkStealingPool();
					service.execute(()->{
						try{
							purgePossibleChunkHeaders(chunk.getDataProvider(), chunk.dataStart(), chunk.getCapacity());
						}catch(IOException e){
							throw new RuntimeException(e);
						}
					});
				}else{
					purgePossibleChunkHeaders(chunk.getDataProvider(), chunk.dataStart(), chunk.getCapacity());
				}
			}
		}
		if(service!=null){
			service.shutdown();
			try{
				while(!service.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS)) ;
			}catch(InterruptedException e){
				throw new RuntimeException(e);
			}
		}
		
		if(!PURGE_ACCIDENTAL){
			if(!toDestroy.isEmpty()){
				toDestroy.sort(Comparator.naturalOrder());
				for(Chunk chunk : toDestroy){
					chunk.destroy(false);
				}
			}
		}
		return chunks;
	}
	
	
	public static long growFileAlloc(Chunk target, long toAllocate) throws IOException{
//		LogUtil.println("growing file by:", toAllocate);
		
		DataProvider context=target.getDataProvider();
		
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
		if(target.getNextSize()==NumberSize.VOID){
			return 0;
		}
		var toPin=AllocateTicket.bytes(toAllocate)
		                        .withApproval(Chunk.sizeFitsPointer(target.getNextSize()))
		                        .withPositionMagnet(target)
		                        .submit(manager);
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
	
	public static long allocateByGrowingHeaderNextAssign(MemoryManager manager, Chunk target, long toAllocate) throws IOException{
		if(target.hasNextPtr()) throw new IllegalArgumentException();
		
		var siz=target.getNextSize();
		
		Chunk toPin;
		int   growth;
		do{
			if(siz==NumberSize.LARGEST){
				throw new OutOfMemoryError();
			}
			
			siz=siz.next();
			growth=siz.bytes-target.getNextSize().bytes;
			
			if(target.getCapacity()<growth){
				return 0;
			}
			
			toPin=AllocateTicket.bytes(toAllocate+growth)
			                    .withApproval(Chunk.sizeFitsPointer(siz))
			                    .withPositionMagnet(target)
			                    .submit(manager);
		}while(toPin==null);
		
		IOInterface source=target.getDataProvider().getSource();
		
		int shiftSize=Math.toIntExact(Math.min(target.getCapacity()-growth, target.getSize()));
		if(shiftSize<0){
			shiftSize=0;
		}
		byte[] toShift;
		try(var io=target.io()){
			toShift=io.readInts1(shiftSize);
			try(var pio=toPin.io()){
				io.transferTo(pio);
			}
		}
		
		var oldCapacity=target.getCapacity();
		
		target.requireReal();
		try{
			target.setNextSize(siz);
			target.setNextPtr(toPin.getPtr());
			target.setSize(shiftSize);
			target.setCapacity(oldCapacity-growth);
		}catch(BitDepthOutOfSpaceException e){
			throw new ShouldNeverHappenError(e);
		}
		try(var ignored=source.openIOTransaction()){
			target.syncStruct();
			source.write(target.dataStart(), false, toShift);
		}
		
		return (target.getCapacity()+toPin.getCapacity())-oldCapacity;
	}
	
	
	private static IOIterator<ChunkPointer> magnetisedFreeChunkIterator(DataProvider context, OptionalLong magnet) throws IOException{
		var freeChunks=context.getMemoryManager().getFreeChunks();
		if(magnet.isEmpty()||freeChunks.size()<=1){
			return freeChunks.iterator();
		}
		
		var pos=magnet.getAsLong();
		
		long index=IOList.findSortedClosest(freeChunks, ch->Math.abs(ch.getValue()-pos));
		
		var after =freeChunks.listIterator(index);
		var before=freeChunks.listIterator(index);
		
		return new IOIterator<>(){
			private boolean toggle;
			private IOList.IOListIterator<ChunkPointer> lastRet;
			@Override
			public boolean hasNext(){
				return after.hasNext()||before.hasPrevious();
			}
			@Override
			public ChunkPointer ioNext() throws IOException{
				toggle=!toggle;
				
				if(toggle){
					if(after.hasNext()){
						lastRet=after;
						return after.ioNext();
					}
					lastRet=before;
					return before.ioPrevious();
				}else{
					if(before.hasPrevious()){
						lastRet=before;
						return before.ioPrevious();
					}
					lastRet=after;
					return after.ioNext();
				}
			}
			@Override
			public void ioRemove() throws IOException{
				lastRet.ioRemove();
			}
		};
	}
	
	public static Chunk allocateReuseFreeChunk(DataProvider context, AllocateTicket ticket) throws IOException{
		for(var iterator=magnetisedFreeChunkIterator(context, ticket.positionMagnet());iterator.hasNext();){
			Chunk c=iterator.ioNext().dereference(context);
			if(c.getCapacity()<ticket.bytes()) continue;
			
			var freeSpace=c.getCapacity()-ticket.bytes();
			
			if(freeSpace>c.getHeaderSize()*2L){
				Chunk reallocate=chipEndProbe(context, ticket, c);
				if(reallocate!=null) return reallocate;
			}
			
			if(freeSpace<8){
				if(ticket.approve(c)){
					iterator.ioRemove();
					return c;
				}
			}
		}
		return null;
	}
	
	private static Chunk chipEndProbe(DataProvider context, AllocateTicket ticket, Chunk ch) throws IOException{
		var builder=new ChunkBuilder(context, ch.getPtr())
			            .withCapacity(ticket.bytes())
			            .withNext(ticket.next());
		
		var reallocate=builder.create();
		
		var siz=reallocate.totalSize();
		var end=ch.dataEnd();
		builder.withPtr(ChunkPointer.of(end-siz));
		reallocate=builder.create();
		
		assert reallocate.dataEnd()==ch.dataEnd();
		
		if(!ticket.approve(reallocate)){
			return null;
		}
		
		reallocate.writeHeader();
		
		ch.setCapacityAndModifyNumSize(ch.getCapacity()-reallocate.totalSize());
		ch.writeHeader();
		return reallocate;
	}
	
	public static Chunk allocateAppendToFile(DataProvider context, AllocateTicket ticket) throws IOException{
		
		var src=context.getSource();
		
		var builder=new ChunkBuilder(context, ChunkPointer.of(src.getIOSize()))
			            .withCapacity(ticket.bytes())
			            .withNext(ticket.next());
		
		var chunk=builder.create();
		if(!ticket.approve(chunk)) return null;
		
		try(var io=src.ioAt(chunk.getPtr().getValue())){
			chunk.writeHeader(io);
			Utils.zeroFill(io::write, chunk.getCapacity());
		}
		
		return chunk;
	}
	
	public static void checkValidityOfChainAlloc(DataProvider context, Chunk firstChunk, Chunk target) throws IOException{
		if(!DEBUG_VALIDATION) return;
		
		assert firstChunk.getDataProvider()==context;
		assert target.getDataProvider()==context;
		
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
	
	public static long growFreeAlloc(MemoryManager manager, Chunk target, long toAllocate) throws IOException{
		var end=target.dataEnd();
		for(var iter=manager.getFreeChunks().listIterator();iter.hasNext();){
			ChunkPointer freePtr=iter.ioNext();
			if(!freePtr.equals(end)) continue;
			
			var provider =manager.getDataProvider();
			var freeChunk=freePtr.dereference(provider);
			var size     =freeChunk.totalSize();
			
			var remaining=size-toAllocate;
			if(remaining<16){
				var newCapacity=target.getCapacity()+size;
				
				if(!target.getBodyNumSize().canFit(newCapacity)){
					return 0;
				}
				
				iter.ioRemove();
				try{
					target.setCapacity(newCapacity);
				}catch(BitDepthOutOfSpaceException e){
					throw new ShouldNeverHappenError(e);
				}
				target.syncStruct();
				
				freeChunk.destroy(false);
				return size;
			}
			
			
			var ch=new ChunkBuilder(provider, freePtr.addPtr(toAllocate)).create();
			ch.setCapacityAndModifyNumSize(size-ch.getHeaderSize()-toAllocate);
			ch.writeHeader();
			
			ch=ch.getPtr().dereference(provider);
			
			iter.ioSet(ch.getPtr());
			
			
			var newCapacity=target.getCapacity()+toAllocate;
			try{
				target.setCapacity(newCapacity);
			}catch(BitDepthOutOfSpaceException e){
				throw new ShouldNeverHappenError(e);
			}
			target.syncStruct();
			
			freeChunk.destroy(false);
			return toAllocate;
		}
		return 0;
	}
}
