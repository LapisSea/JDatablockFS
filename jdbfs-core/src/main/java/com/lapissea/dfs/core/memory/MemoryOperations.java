package com.lapissea.dfs.core.memory;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.config.FreedMemoryPurgeType;
import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.MemoryManager;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.ChunkBuilder;
import com.lapissea.dfs.core.chunk.ChunkSet;
import com.lapissea.dfs.core.chunk.PhysicalChunkWalker;
import com.lapissea.dfs.exceptions.OutOfBitDepth;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.objects.collections.IOIterator;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.MemoryWalker;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.utils.IOUtils;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.TextUtil;
import com.lapissea.util.ZeroArrays;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.LongStream;

import static com.lapissea.dfs.config.GlobalConfig.BATCH_BYTES;
import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.dfs.logging.Log.smallTrace;

public final class MemoryOperations{
	
	public static void purgePossibleChunkHeaders(DataProvider provider, long from, long size) throws IOException{
		var maxHeaderSize = (int)Chunk.PIPE.getSizeDescriptor().requireMax(WordSpace.BYTE);
		
		ChunkSet possibleHeaders = new ChunkSet();
		try(var io = provider.getSource().read(from)){
			for(int i = 0; i<size; i++){
				if(Chunk.earlyCheckChunkAt(io)){
					possibleHeaders.add(i);
				}
			}
		}
		
		boolean noTrim = false;
		
		List<RandomIO.WriteChunk> writes = new ArrayList<>();
		byte[]                    b1     = {0};
		
		while(!possibleHeaders.isEmpty()){
			
			long lastUnknown = -1;
			int  removeCount = 0;
			
			
			//test unknowns
			var iter = (noTrim? LongStream.of(possibleHeaders.last().getValue()) : possibleHeaders.longStream()).iterator();
			while(iter.hasNext()){
				var headIndex = iter.nextLong();
				
				var pos = headIndex + from;
				
				try{
					Chunk.readChunk(provider, ChunkPointer.of(pos));
				}catch(Throwable e){
					//invalid only if last
					lastUnknown = Math.max(lastUnknown, headIndex);
					continue;
				}
				
				//known invalid - destroyed
				writes.add(new RandomIO.WriteChunk(pos, b1));
				possibleHeaders.remove(headIndex);
				removeCount++;
			}
			if(!writes.isEmpty()){
				try(var io = provider.getSource().io()){
					io.writeAtOffsets(writes);
				}
				writes.clear();
			}
			
			if(lastUnknown != -1){
				possibleHeaders.remove(lastUnknown);
			}
			if(possibleHeaders.isEmpty()) break;
			
			if(removeCount>1) noTrim = false;
			if(noTrim) continue;
			
			noTrim = true;
			//pop alone headers, no change will make them valid
			iter = possibleHeaders.longStream().iterator();
			long lastIndex = -maxHeaderSize*2L;
			var  index     = iter.nextLong();
			while(iter.hasNext()){
				var nextIndex = iter.nextLong();
				
				if(Math.min(Math.abs(lastIndex - index), Math.abs(nextIndex - index))>maxHeaderSize){
					possibleHeaders.remove(index);
					noTrim = false;
				}else{
					lastIndex = index;
				}
				index = nextIndex;
			}
		}
	}
	
	private static long binaryFindWedge(IOList<ChunkPointer> a, ChunkPointer key) throws IOException{
		long low  = 0;
		long high = a.size() - 1;
		
		while(low<=high){
			long mid    = (low + high) >>> 1;
			var  midVal = a.get(mid);
			
			int cmp;
			int cmp1 = midVal.compareTo(key);
			if(cmp1<0){
				var nextMid = mid + 1;
				if(nextMid>=a.size()){
					return nextMid;
				}
				var nextMidVal = a.get(nextMid);
				int cmp2       = nextMidVal.compareTo(key);
				if(cmp2>=0){
					return nextMid;
				}
				cmp = cmp1;
			}else if(mid == 0){
				return mid;
			}else{
				cmp = cmp1;
			}
			
			if(cmp<0) low = mid + 1;
			else if(cmp>0) high = mid - 1;
			else return mid; // key found
		}
		return -1;  // key isn't found.
	}
	
	public static void mergeFreeChunksSorted(DataProvider provider, IOList<ChunkPointer> data, List<Chunk> newData) throws IOException{
		for(Chunk newCh : newData){
			if(DEBUG_VALIDATION) checkOptimal(provider, data);
			
			var newPtr = newCh.getPtr();
			if(data.isEmpty()){
				data.add(newPtr);
				continue;
			}
			var insertIndex = binaryFindWedge(data, newPtr);
			if(insertIndex == 0){
				var existing = data.get(0).dereference(provider);
				var next     = newCh;
				if(existing.compareTo(next)<0){
					var tmp = next;
					next = existing;
					existing = tmp;
				}
				if(next.isNextPhysical(existing)){
					freeListReplace(data, 0, newCh);
					mergeFreeChunks(next, existing);
					//check if the next element in free list is now next physical and merge+remove from the list
					if(data.size()>1){
						var ptr = data.get(1);
						if(existing.isNextPhysical(ptr)){
							var ch = ptr.dereference(provider);
							data.remove(1);
							mergeFreeChunks(existing, ch);
						}
					}
					continue;
				}
				
				freeListAdd(data, insertIndex, newCh);
				continue;
			}
			
			var prev = data.get(insertIndex - 1).dereference(provider);
			if(prev.isNextPhysical(newCh)){
				mergeFreeChunks(prev, newCh);
				//check if next element in free list is now next physical and merge+remove from list
				if(data.size()>insertIndex){
					var ch = data.get(insertIndex).dereference(provider);
					if(prev.isNextPhysical(ch)){
						data.remove(insertIndex);
						mergeFreeChunks(prev, ch);
					}
				}
			}else{
				if(data.size()>insertIndex){
					var next = data.get(insertIndex).dereference(provider);
					
					if(newCh.isNextPhysical(next)){
						freeListReplace(data, insertIndex, newCh);
						mergeFreeChunks(newCh, next);
						continue;
					}
				}
				freeListAdd(data, insertIndex, newCh);
			}
		}
		if(DEBUG_VALIDATION) checkOptimal(provider, data);
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
		ChunkPointer last = null;
		for(ChunkPointer val : data){
			if(last != null){
				if(last.compareTo(val)>=0){
					throw new IllegalStateException(last + " >= " + val + " in " + data);
				}
				var prev = last.dereference(provider);
				var c    = val.dereference(provider);
				if(prev.isNextPhysical(c)){
					throw new IllegalStateException(prev + " connected to " + c + " in " + data);
				}
			}
			last = val;
		}
	}
	private static void clearFree(Chunk newCh) throws IOException{
		newCh.setSize(0);
		newCh.clearAndCompressHeader();
		newCh.syncStruct();
	}
	
	private static void mergeFreeChunks(Chunk prev, Chunk next) throws IOException{
		prepareFreeChunkMerge(prev, next);
		prev.syncStruct();
		next.destroy(true);
	}
	
	private static void prepareFreeChunkMerge(Chunk prev, Chunk next){
		var wholeSize = next.getHeaderSize() + next.getCapacity();
		prev.setCapacityAndModifyNumSize(prev.getCapacity() + wholeSize);
		if(prev.dataEnd() != next.dataEnd()) throw new IllegalStateException(prev + " and " + next + " are not connected");
	}
	
	private static final FreedMemoryPurgeType PURGE_ACCIDENTAL = ConfigDefs.PURGE_ACCIDENTAL_CHUNK_HEADERS.resolve();
	
	public static List<Chunk> mergeChunks(Collection<Chunk> data) throws IOException{
		if(data.isEmpty()) return new ArrayList<>();
		var provider = data.iterator().next().getDataProvider();
		
		List<Chunk> toDestroy = new ArrayList<>();
		List<Chunk> oks       = new ArrayList<>();
		
		{
			List<Chunk> chunks = new ArrayList<>(data);
			chunks.sort(Chunk::compareTo);
			while(chunks.size()>1){
				var prev  = chunks.get(chunks.size() - 2);
				var chunk = chunks.removeLast();
				assert prev.getPtr().getValue()<chunk.getPtr().getValue() : prev.getPtr() + " " + chunk.getPtr();
				if(prev.isNextPhysical(chunk)){
					prepareFreeChunkMerge(prev, chunk);
					
					toDestroy.add(chunk);
				}else{
					oks.add(chunk);
				}
			}
			var chunk = chunks.removeLast();
			oks.add(chunk);
		}
		
		var writeChunks = new ArrayList<RandomIO.WriteChunk>(oks.size());
		
		oks.sort(Comparator.naturalOrder());
		for(Chunk chunk : oks){
			chunk.setSize(0);
			chunk.clearAndCompressHeader();
			writeChunks.add(chunk.writeHeaderToBuf());
		}
		
		var purgeTransaction = PURGE_ACCIDENTAL != FreedMemoryPurgeType.NO_OP? provider.getSource().openIOTransaction() : null;
		
		if(!toDestroy.isEmpty()){
			byte[] empty         = new byte[(int)Chunk.PIPE.getSizeDescriptor().requireMax(WordSpace.BYTE)];
			var    destroyChunks = toDestroy.stream().map(c -> new RandomIO.WriteChunk(c.getPtr().getValue(), empty, c.getHeaderSize())).toList();
			
			if(purgeTransaction != null){
				try(var io = provider.getSource().io()){
					io.writeAtOffsets(writeChunks);
					io.writeAtOffsets(destroyChunks);
				}
			}else{
				writeChunks.addAll(destroyChunks);
				writeChunks.sort(Comparator.naturalOrder());
				try(var io = provider.getSource().io()){
					io.writeAtOffsets(writeChunks);
				}
			}
			
			for(Chunk chunk : toDestroy){ provider.getChunkCache().notifyDestroyed(chunk); }
		}else{
			try(var io = provider.getSource().io()){
				io.writeAtOffsets(writeChunks);
			}
		}
		ExecutorService service = null;
		if(PURGE_ACCIDENTAL != FreedMemoryPurgeType.NO_OP) try{
			for(Chunk chunk : oks){
				switch(PURGE_ACCIDENTAL){
					case ONLY_HEADER_BYTES -> {
						if(chunk.getCapacity()>3000){
							if(service == null) service = Executors.newVirtualThreadPerTaskExecutor();
							service.execute(() -> {
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
					case ZERO_OUT -> {
						try(var io = chunk.getDataProvider().getSource().io()){
							var start = chunk.dataStart();
							if(io.getSize()>=start){
								io.setPos(start);
								if(io.getPos() != start){
									throw new IOException("Failed to zero out " + chunk + " : " + io.getPos() + " -> " + start + " size: " + io.getSize());
								}
								IOUtils.zeroFill(io, chunk.getCapacity());
							}
						}
					}
				}
				if(service == null && purgeTransaction != null &&
				   (purgeTransaction.getTotalBytes() + purgeTransaction.getChunkCount()*20L)>BATCH_BYTES){
					purgeTransaction.close();
					purgeTransaction = provider.getSource().openIOTransaction();
				}
			}
		}finally{
			if(service != null){
				service.close();
			}
			if(purgeTransaction != null) purgeTransaction.close();
		}
		
		return oks;
	}
	
	
	public static long growFileAlloc(Chunk target, long toAllocate) throws IOException{
		DataProvider context = target.getDataProvider();
		
		if(context.isLastPhysical(target)){
			smallTrace("growing {} by {} by growing file", target, toAllocate);
			
			var remaining = target.getBodyNumSize().remaining(target.getCapacity());
			var toGrow    = Math.min(toAllocate, remaining);
			if(toGrow>0){
				try(var io = context.getSource().io()){
					var old = io.getCapacity();
					io.setCapacity(old + toGrow);
					io.setPos(old);
					IOUtils.zeroFill(io, toGrow);
				}
				target.modifyAndSave(ch -> {
					try{
						ch.setCapacity(ch.getCapacity() + toGrow);
					}catch(OutOfBitDepth e){
						/*
						 * toGrow is clamped to the remaining bitspace of body num size. If this happens,
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
	
	public static long allocateBySimpleNextAssign(MemoryManager manager, Chunk first, Chunk target, long toAllocate) throws IOException{
		if(target.getNextSize() == NumberSize.VOID){
			return 0;
		}
		
		boolean isChain = first != target;
		
		var toPin = AllocateTicket.bytes(toAllocate)
		                          .withApproval(Chunk.sizeFitsPointer(target.getNextSize()))
		                          .withPositionMagnet(target)
		                          .withExplicitNextSize(calcExplicitNextSize(manager, isChain))
		                          .submit(manager);
		if(toPin == null) return 0;
		target.modifyAndSave(c -> {
			try{
				c.setNextPtr(toPin.getPtr());
			}catch(OutOfBitDepth e){
				throw new ShouldNeverHappenError(e);
			}
		});
		return toPin.getCapacity();
	}
	
	
	public static long allocateByChainWalkUpDefragment(MemoryManager manager, Chunk first, Chunk target, long toAllocate) throws IOException{
		if(target.hasNextPtr()) throw new IllegalArgumentException();
		if(first == target){
			return 0;
		}
		
		var chain = first.collectNext();
		var iter  = chain.listIterator(chain.size());
		
		long toCopy = 0;
		
		while(iter.hasPrevious()){
			var ch = iter.previous();
			
			Chunk allocated;
			if(ch.getNextSize() == NumberSize.VOID) allocated = null;
			else allocated = AllocateTicket.bytes(toAllocate + toCopy)
			                               .withPositionMagnet(ch)
			                               .withApproval(Chunk.sizeFitsPointer(ch.getNextSize()))
			                               .withExplicitNextSize(calcExplicitNextSize(manager, true))
			                               .submit(manager);
			if(allocated == null){
				toCopy += ch.getCapacity();
				continue;
			}
			
			var strangler  = ch.requireNext();
			var stranglers = strangler.collectNext();
			var toMove     = 0;
			for(var c : stranglers){
				toMove += c.getCapacity();
			}
			if(toMove>0){
				try(var src = strangler.io();
				    var dst = allocated.io()){
					src.transferTo(dst);
				}
			}
			
			ch.modifyAndSave(c -> {
				try{
					c.setNextPtr(allocated.getPtr());
				}catch(OutOfBitDepth e){
					throw new ShouldNeverHappenError();//Allocated ticket has approval of can fit
				}
			});
			
			manager.free(stranglers);
			var cap = allocated.getCapacity();
			return cap - toMove;
		}
		
		return 0;
	}
	
	public static long allocateByGrowingHeaderNextAssign(MemoryManager manager, Chunk first, Chunk target, long toAllocate) throws IOException{
		if(target.hasNextPtr()) throw new IllegalArgumentException();
		
		boolean isChain = first != target;
		
		NumberSize nextSiz;
		NumberSize bodyNum;
		int        growth, bodyShrink;
		Chunk      toPin = null;
		
		var baseTicket = AllocateTicket.explicitNextSize(calcExplicitNextSize(manager, isChain));
		
		while(true){
			nextSiz = target.getNextSize();
			bodyNum = target.getBodyNumSize();
			bodyShrink = 0;
			baseTicket = baseTicket.withPositionMagnet(target);
			do{
				if(nextSiz == NumberSize.LARGEST){
					throw new OutOfMemoryError();
				}
				
				nextSiz = nextSiz.next();
				growth = (nextSiz.bytes - target.getNextSize().bytes) - bodyShrink;
				
				overflow:
				if(target.getCapacity()<growth){
					var prevBodyNum = bodyNum.prev();
					bodyShrink = (bodyNum.bytes - prevBodyNum.bytes)*2;
					growth = (nextSiz.bytes - target.getNextSize().bytes) - bodyShrink;
					while(true){
						var newCap = target.getCapacity() - growth;
						if(newCap<0) break;
						
						if(prevBodyNum.canFit(newCap)){
							bodyNum = prevBodyNum;
							break overflow;
						}
						
						nextSiz = nextSiz.next();
						growth = (nextSiz.bytes - target.getNextSize().bytes) - bodyShrink;
						
						if(nextSiz == NumberSize.LARGEST){
							break;
						}
					}
					
					break;
				}
				
				toPin = baseTicket.withBytes(toAllocate + growth)
				                  .withApproval(Chunk.sizeFitsPointer(nextSiz))
				                  .submit(manager);
			}while(toPin == null);
			if(toPin != null) break;
			
			toAllocate += target.getSize();
			target = target.findPrev(first);
			if(target == null) return 0;
		}
		
		IOInterface source = target.getDataProvider().getSource();
		
		int shiftSize = Math.toIntExact(Math.min(target.getCapacity() - growth, target.getSize()));
		if(shiftSize<0){
			shiftSize = 0;
		}
		byte[] toShift;
		if(shiftSize>0){
			try(var io = target.io()){
				toShift = io.readInts1(shiftSize);
				try(var pio = toPin.io()){
					io.transferTo(pio);
				}
			}
		}else toShift = ZeroArrays.ZERO_BYTE;
		
		var oldCapacity = target.getCapacity();
		var dataEnd     = target.dataEnd();
		
		var toFree = target.next();
		
		target.requireReal();
		
		if(oldCapacity == growth){
			var numSizShrink     = bodyNum.bytes*2;
			var ptrSizeCandidate = nextSiz.bytes + numSizShrink;
			var newPtrSizeO      = NumberSize.FLAG_INFO.firstMatching(n -> n.bytes == ptrSizeCandidate);
			if(newPtrSizeO.isPresent()){
				nextSiz = newPtrSizeO.get();
				bodyNum = NumberSize.VOID;
			}
		}
		
		try{
			target.setNextSize(nextSiz);
			target.setNextPtr(toPin.getPtr());
			target.setSize(shiftSize);
			target.setCapacity(oldCapacity - growth);
			target.setBodyNumSize(bodyNum);
		}catch(OutOfBitDepth e){
			throw new ShouldNeverHappenError(e);
		}
		
		assert dataEnd == target.dataEnd() : target + ": " + dataEnd + "!=" + target.dataEnd();
		
		try(var ignored = source.openIOTransaction()){
			target.syncStruct();
			source.write(target.dataStart(), false, toShift);
		}
		
		var moved = 0L;
		if(toFree != null){
			moved = toFree.chainLength();
			toFree.freeChaining();
		}
		
		return target.getCapacity() + toPin.getCapacity() - moved - oldCapacity;
	}
	
	
	private static IOIterator<ChunkPointer> magnetisedFreeChunkIterator(DataProvider context, OptionalLong magnet) throws IOException{
		var freeChunks = context.getMemoryManager().getFreeChunks();
		if(magnet.isEmpty() || freeChunks.size()<=1){
			return freeChunks.iterator();
		}
		
		var pos = magnet.getAsLong();
		
		long index = IOList.findSortedClosest(freeChunks, ch -> Math.abs(ch.getValue() - pos));
		
		var after  = freeChunks.listIterator(index);
		var before = freeChunks.listIterator(index);
		
		return new IOIterator<>(){
			private boolean                             toggle;
			private IOList.IOListIterator<ChunkPointer> lastRet;
			@Override
			public boolean hasNext(){
				return after.hasNext() || before.hasPrevious();
			}
			@Override
			public ChunkPointer ioNext() throws IOException{
				toggle = !toggle;
				
				if(toggle){
					if(after.hasNext()){
						lastRet = after;
						return after.ioNext();
					}
					lastRet = before;
					return before.ioPrevious();
				}else{
					if(before.hasPrevious()){
						lastRet = before;
						return before.ioPrevious();
					}
					lastRet = after;
					return after.ioNext();
				}
			}
			@Override
			public void ioRemove() throws IOException{
				lastRet.ioRemove();
			}
		};
	}
	
	public static Chunk allocateReuseFreeChunk(DataProvider context, AllocateTicket ticket, boolean allowRemove, boolean dryRun) throws IOException{
		for(var iterator = magnetisedFreeChunkIterator(context, ticket.positionMagnet()); iterator.hasNext(); ){
			Chunk c = iterator.ioNext().dereference(context);
			assert c.getNextSize() == NumberSize.VOID;
			NumberSize neededNextSize    = ticket.calcNextSize();
			var        effectiveCapacity = c.getCapacity() - neededNextSize.bytes;
			if(effectiveCapacity<ticket.bytes()) continue;
			
			var freeSpace = effectiveCapacity - ticket.bytes();
			
			var potentialChunk = chBuilderFromTicket(context, c.getPtr(), ticket).create();
			if(freeSpace>c.getHeaderSize() + potentialChunk.getHeaderSize()){
				Chunk reallocate = chipEndProbe(context, ticket, c, dryRun);
				if(reallocate != null) return reallocate;
			}
			
			if(allowRemove && freeSpace<8){
				if(ticket.approve(c)){
					if(dryRun) return c;
					iterator.ioRemove();
					try{
						var oldHSiz = c.getHeaderSize();
						c.setNextSize(neededNextSize);
						c.setNextPtr(ticket.next());
						var newHSiz = c.getHeaderSize();
						c.setCapacity(c.getCapacity() + oldHSiz - newHSiz);
					}catch(OutOfBitDepth e){
						throw new ShouldNeverHappenError(e);
					}
					c.syncStruct();
					return c;
				}
			}
		}
		return null;
	}
	
	private static Chunk chipEndProbe(DataProvider context, AllocateTicket ticket, Chunk ch, boolean dryRun) throws IOException{
		var ptr     = ch.getPtr();
		var builder = chBuilderFromTicket(context, ptr, ticket);
		
		var reallocate = builder.create();
		
		var siz = reallocate.totalSize();
		var end = ch.dataEnd();
		builder.withPtr(ChunkPointer.of(end - siz));
		reallocate = builder.create();
		
		if(reallocate.dataEnd() != ch.dataEnd()) throw new IllegalStateException();
		
		if(!ticket.approve(reallocate)){
			return null;
		}
		if(dryRun) return reallocate;
		
		reallocate.writeHeader();
		
		ch.setCapacityAndModifyNumSize(ch.getCapacity() - reallocate.totalSize());
		ch.writeHeader();
		context.getChunkCache().add(reallocate);
		return reallocate;
	}
	private static ChunkBuilder chBuilderFromTicket(DataProvider context, ChunkPointer ptr, AllocateTicket ticket){
		return new ChunkBuilder(context, ptr)
			       .withCapacity(ticket.bytes())
			       .withExplicitNextSize(ticket.calcNextSize())
			       .withNext(ticket.next());
	}
	
	public static Chunk allocateAppendToFile(DataProvider context, AllocateTicket ticket, boolean dryRun) throws IOException{
		
		var src   = context.getSource();
		var ioSiz = src.getIOSize();
		
		ChunkBuilder builder = chBuilderFromTicket(context, ChunkPointer.of(ioSiz), ticket);
		
		var chunk = builder.create();
		if(!ticket.approve(chunk)) return null;
		
		if(dryRun) return chunk;
		
		try(var io = src.ioAt(chunk.getPtr().getValue())){
			chunk.writeHeader(io);
			IOUtils.zeroFill(io, chunk.getCapacity());
		}
		context.getChunkCache().add(chunk);
		return chunk;
	}
	
	public static void checkValidityOfChainAlloc(DataProvider context, Chunk firstChunk, Chunk target) throws IOException{
		//TODO: re-enable this once DataProvider.withRouter is replaced with proper router
//		assert firstChunk.getDataProvider()==context;
//		assert target.getDataProvider()==context;
		
		var ptr = firstChunk.getPtr();
		
		var prev = new PhysicalChunkWalker(context.getFirstChunk())
			           .map(Chunk::getNextPtr)
			           .firstMatching(ptr::equals);
		
		if(prev.isPresent()){
			var ch = context.getChunk(prev.get());
			throw new IllegalArgumentException(firstChunk + " is not the first chunk! " + ch + " declares it as next.");
		}
		
		if(firstChunk.walkNext().noneMatch(c -> c == target)){
			throw new IllegalArgumentException(TextUtil.toString(target, "is in the chain of", firstChunk, "descendents:", firstChunk.collectNext()));
		}
	}
	
	public static long growFreeAlloc(MemoryManager manager, Chunk target, long toAllocate, boolean allowRemove) throws IOException{
		var end = target.dataEnd();
		for(var iter = manager.getFreeChunks().listIterator(); iter.hasNext(); ){
			ChunkPointer freePtr = iter.ioNext();
			if(!freePtr.equals(end)){
				if(freePtr.compareTo(end)>0) return 0;
				continue;
			}
			
			var provider  = manager.getDataProvider();
			var freeChunk = freePtr.dereference(provider);
			var size      = freeChunk.totalSize();
			
			var remaining = size - toAllocate;
			if(allowRemove && remaining<16){
				var newCapacity = target.getCapacity() + size;
				
				if(!target.getBodyNumSize().canFit(newCapacity)){
					return 0;
				}
				
				iter.ioRemove();
				try{
					target.setCapacity(newCapacity);
				}catch(OutOfBitDepth e){
					throw new ShouldNeverHappenError(e);
				}
				target.syncStruct();
				
				freeChunk.destroy(false);
				return size;
			}
			
			long safeToAllocate = toAllocate;
			
			Chunk ch;
			long  newCapacity;
			while(true){
				ch = new ChunkBuilder(provider, freePtr.addPtr(safeToAllocate)).create();
				ch.setCapacityAndModifyNumSize(size - ch.getHeaderSize() - safeToAllocate);
				
				newCapacity = target.getCapacity() + safeToAllocate;
				if(!target.getBodyNumSize().canFit(newCapacity)){
					safeToAllocate = target.getBodyNumSize().maxSize() - target.getCapacity();
					continue;
				}
				break;
			}
			if(safeToAllocate == 0) continue;
			
			try(var ignored = provider.getSource().openIOTransaction()){
				ch.writeHeader();
				ch = ch.getPtr().dereference(provider);
				
				iter.ioSet(ch.getPtr());
				
				
				try{
					target.setCapacity(newCapacity);
				}catch(OutOfBitDepth e){
					throw new ShouldNeverHappenError(e);
				}
				target.syncStruct();
			}
			if(safeToAllocate<freeChunk.getHeaderSize()){
				try(var io = provider.getSource().ioAt(freeChunk.getPtr().getValue())){
					IOUtils.zeroFill(io, safeToAllocate);
				}
				provider.getChunkCache().notifyDestroyed(freeChunk);
			}else{
				freeChunk.destroy(false);
			}
			return safeToAllocate;
		}
		return 0;
	}
	
	private static Optional<NumberSize> calcExplicitNextSize(MemoryManager manager, boolean isChain) throws IOException{
		if(!isChain){
			return Optional.empty();
		}
		return Optional.of(NumberSize.bySize(manager.getDataProvider().getSource().getIOSize()));
	}
	
	public static <U extends IOInstance.Unmanaged<U>> void freeSelfAndReferenced(U val) throws IOException{
		var chunks = listSelfAndReferenced(val);
		var man    = val.getDataProvider().getMemoryManager();
		man.free(chunks);
	}
	
	public static <U extends IOInstance.Unmanaged<U>> Set<Chunk> listSelfAndReferenced(U val) throws IOException{
		Set<Chunk> chunks = new HashSet<>();
		var        prov   = val.getDataProvider();
		
		UnsafeConsumer<Reference, IOException> rec = ref -> {
			if(ref.isNull()){
				return;
			}
			ref.getPtr().dereference(prov).addChainTo(chunks);
		};
		
		rec.accept(val.getPointer().makeReference());
		new MemoryWalker(val, false, MemoryWalker.PointerRecord.of(rec)).walk();
		return chunks;
	}
}
