package com.lapissea.dfs.core.memory;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.MemoryManager;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.ChunkBuilder;
import com.lapissea.dfs.core.chunk.ChunkChainIO;
import com.lapissea.dfs.core.chunk.FreeChunk;
import com.lapissea.dfs.exceptions.CacheOutOfSync;
import com.lapissea.dfs.exceptions.FreeWhileUsed;
import com.lapissea.dfs.exceptions.OutOfBitDepth;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.utils.IOUtils;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;

public final class PersistentMemoryManager implements MemoryManager{
	private static final int SEARCH_LIMIT = 32;
	
	private static final class Entry{
		volatile FreeChunk free;
		boolean claimed;
		Entry(FreeChunk free){ this.free = free; }
		long address()       { return free.getPtr().getValue(); }
	}
	
	private static final Comparator<Entry> SIZE_ORDER = Comparator.comparingLong((Entry e) -> {
		return e.free.totalSize();
	}).thenComparingLong(Entry::address);
	
	private final Cluster                                   context;
	private final UnsafeConsumer<ChunkPointer, IOException> saveRoot;
	
	private final ReentrantLock   metadataLock = new ReentrantLock();
	private final ReentrantLock   tailLock     = new ReentrantLock();
	private final ReentrantLock[] chainLocks   = new ReentrantLock[64];
	
	private final NavigableMap<Long, Entry>                  byAddress   = new TreeMap<>();
	private final NavigableMap<Integer, NavigableSet<Entry>> bySize      = new TreeMap<>();
	private final Map<Thread, ArrayDeque<ChunkChainIO>>      openChains  = new IdentityHashMap<>();
	private final ThreadLocal<ArrayDeque<ChunkChainIO>>      chainStacks = ThreadLocal.withInitial(ArrayDeque::new);
	
	private ChunkPointer head;
	private long         lastChipped;
	private long         physicalEnd;
	
	private IOException publicationFailure;
	
	public PersistentMemoryManager(Cluster context, ChunkPointer head, UnsafeConsumer<ChunkPointer, IOException> saveRoot) throws IOException{
		this.context = context;
		this.head = head;
		this.saveRoot = saveRoot;
		physicalEnd = context.getSource().getIOSize();
		
		for(int i = 0; i<chainLocks.length; i++){
			chainLocks[i] = new ReentrantLock();
		}
		
		reload();
	}
	
	private void reload() throws IOException{
		var source = context.getSource();
		
		byAddress.clear();
		bySize.clear();
		
		var seen = new HashSet<ChunkPointer>();
		for(var ptr = head; !ptr.isNull(); ){
			if(!seen.add(ptr)){
				throw new IOException("Cycle in free chain at " + ptr);
			}
			var free     = FreeChunk.read(source, ptr);
			var previous = byAddress.lowerEntry(ptr.getValue());
			var next     = byAddress.ceilingEntry(ptr.getValue());
			
			if(previous != null && previous.getValue().free.dataEnd()>ptr.getValue() ||
			   next != null && free.dataEnd()>next.getKey()){
				throw new IOException("Overlapping free extents at " + ptr);
			}
			index(new Entry(free));
			ptr = free.nextPtr();
		}
		
		// The persistent chain is in address order, can use index
		var expected = byAddress.isEmpty()? ChunkPointer.NULL : byAddress.firstEntry().getValue().free.getPtr();
		if(!head.equals(expected)){
			throw new IOException("Unsorted free chain root");
		}
		
		for(var e : byAddress.values()){
			var next    = byAddress.higherEntry(e.address());
			var nextPtr = next == null? ChunkPointer.NULL : next.getValue().free.getPtr();
			if(!e.free.nextPtr().equals(nextPtr)){
				throw new IOException("Unsorted free chain at " + e.free.getPtr());
			}
		}
	}
	
	@Override
	public void refreshAfterRollback() throws IOException{
		metadataLock.lock();
		try{
			head = context.getFirstFreeChunkPointer();
			physicalEnd = context.getSource().getIOSize();
			publicationFailure = null;
			lastChipped = 0;
			reload();
		}finally{
			metadataLock.unlock();
		}
	}
	
	@Override
	public DataProvider getDataProvider(){ return context; }
	private static int sizeGroup(long size){ return 63 - Long.numberOfLeadingZeros(size); }
	private void index(Entry e){
		byAddress.put(e.address(), e);
		indexSize(e);
	}
	private void indexSize(Entry e){
		if(!e.claimed){
			bySize.computeIfAbsent(sizeGroup(e.free.totalSize()), k -> new TreeSet<>(SIZE_ORDER)).add(e);
		}
	}
	
	private void unindexSize(Entry e){
		var group = bySize.get(sizeGroup(e.free.totalSize()));
		if(group == null) return;
		group.remove(e);
		if(group.isEmpty()){
			bySize.remove(sizeGroup(e.free.totalSize()));
		}
	}
	
	private void checkHealthy() throws IOException{
		if(publicationFailure != null){
			throw new IOException("Allocator is unusable after a publication failure", publicationFailure);
		}
	}
	private void poison(Throwable failure){
		if(publicationFailure == null){
			publicationFailure = failure instanceof IOException io? io : new IOException("Allocator publication failed", failure);
		}
	}
	
	private void setHead(ChunkPointer ptr) throws IOException{
		saveRoot.accept(ptr);
		head = ptr;
	}
	
	private void replace(Entry e, FreeChunk free) throws IOException{
		boolean changedSize = !e.claimed && e.free.totalSize() != free.totalSize();
		if(changedSize) unindexSize(e);
		free.write(context);
		e.free = free;
		if(changedSize) indexSize(e);
	}
	
	private void unlink(Entry e) throws IOException{
		var prev = byAddress.lowerEntry(e.address());
		if(prev == null){
			setHead(e.free.nextPtr());
		}else{
			replace(prev.getValue(), new FreeChunk(prev.getValue().free.getPtr(), prev.getValue().free.totalSize(), e.free.nextPtr()));
		}
		
		if(!e.claimed) unindexSize(e);
		byAddress.remove(e.address());
		
		if(lastChipped == e.address()) lastChipped = 0;
	}
	
	private Entry insert(long address, long size) throws IOException{ return insert(address, size, false); }
	private Entry insert(long address, long size, boolean claimed) throws IOException{
		var next = byAddress.ceilingEntry(address);
		var prev = byAddress.lowerEntry(address);
		var ptr  = ChunkPointer.of(address);
		var e    = new Entry(new FreeChunk(ptr, size, next == null? ChunkPointer.NULL : next.getValue().free.getPtr()));
		e.claimed = claimed;
		e.free.write(context);
		if(prev == null) setHead(ptr);
		else replace(prev.getValue(), new FreeChunk(prev.getValue().free.getPtr(), prev.getValue().free.totalSize(), ptr));
		index(e);
		return coalesce(e);
	}
	
	private Entry coalesce(Entry e) throws IOException{
		if(e.claimed) return e;
		var prev = byAddress.lowerEntry(e.address());
		if(prev != null && !prev.getValue().claimed && prev.getValue().free.dataEnd() == e.address()){
			var p = prev.getValue();
			unlink(e);
			replace(p, new FreeChunk(p.free.getPtr(), Math.addExact(p.free.totalSize(), e.free.totalSize()), p.free.nextPtr()));
			e = p;
		}
		var next = byAddress.higherEntry(e.address());
		if(next != null && !next.getValue().claimed && e.free.dataEnd() == next.getKey()){
			var n = next.getValue();
			unlink(n);
			replace(e, new FreeChunk(e.free.getPtr(), Math.addExact(e.free.totalSize(), n.free.totalSize()), e.free.nextPtr()));
		}
		return e;
	}
	
	private void release(Entry e) throws IOException{
		e.claimed = false;
		indexSize(e);
		coalesce(e);
	}
	
	private Entry claim(AllocateTicket ticket, long required, Set<Long> tried, boolean earliest, long after, long before){
		if(earliest){
			if(after>=before) return null;
			for(var e : byAddress.subMap(after, false, before, false).values()){
				if(e.claimed || e.free.totalSize()<required) continue;
				e.claimed = true;
				unindexSize(e);
				return e;
			}
			return null;
		}
		
		var hint = lastChipped == 0? null : byAddress.get(lastChipped);
		if(hint != null && !hint.claimed && hint.free.totalSize()>=required && (tried == null || !tried.contains(hint.address()))){
			hint.claimed = true;
			unindexSize(hint);
			return hint;
		}
		
		if(bySize.isEmpty()){
			return null;
		}
		
		var lowerBound = new Entry(new FreeChunk(ChunkPointer.of(1), required, ChunkPointer.NULL));
		for(var group : bySize.tailMap(sizeGroup(required), true).values()){
			Entry best         = null;
			long  bestDistance = Long.MAX_VALUE;
			int   inspected    = 0;
			// Exact sizes within each logarithmic group skip undersized extents without scanning.
			for(var e : group.tailSet(lowerBound, true)){
				if(tried != null && tried.contains(e.address())) continue;
				if(++inspected>SEARCH_LIMIT) break;
				
				if(ticket.positionMagnet().isEmpty()){
					best = e;
					break;
				}
				
				long magnet   = ticket.positionMagnet().getAsLong();
				long distance = e.address()>=magnet? e.address() - magnet : magnet - e.address();
				if(best == null || distance<bestDistance){
					best = e;
					bestDistance = distance;
				}
			}
			if(best == null) continue;
			best.claimed = true;
			unindexSize(best);
			return best;
		}
		return null;
	}
	
	private Chunk splitCandidate(Entry e, AllocateTicket ticket, boolean fromStart){
		var  template  = new ChunkBuilder(context, e.free.getPtr(), ticket).ensureMinSize().create();
		long remaining = e.free.totalSize() - template.totalSize();
		if(remaining>=Chunk.minSafeSize()){
			if(fromStart) return template;
			return new ChunkBuilder(context, ChunkPointer.of(e.free.dataEnd() - template.totalSize()), ticket).ensureMinSize().create();
		}
		// Consume a small remainder as spare capacity, preserving the entire physical extent.
		for(var width : NumberSize.values()){
			long capacity = e.free.totalSize() - (1 + width.bytes*2 + ticket.calcNextSize().bytes);
			if(capacity<ticket.bytes() || !width.canFit(capacity)) continue;
			return new ChunkBuilder(context, e.free.getPtr(), ticket).withExplicitBodyNumSize(width).withCapacity(capacity).create();
		}
		return null;
	}
	
	private Chunk reuse(AllocateTicket ticket, boolean dryRun) throws IOException{ return reuse(ticket, dryRun, false, Long.MAX_VALUE); }
	private Chunk reuse(AllocateTicket ticket, boolean dryRun, boolean earliest, long before) throws IOException{
		context.checkValid();
		long required = new ChunkBuilder(context, ticket.next().isNull()? ChunkPointer.of(1) : ChunkPointer.NULL, ticket)
			                .ensureMinSize()
			                .create()
			                .totalSize();
		Set<Long> tried = null;
		long      after = 0;
		for(int attempt = 0; earliest || attempt<SEARCH_LIMIT; attempt++){
			Entry e;
			metadataLock.lock();
			try{
				checkHealthy();
				e = claim(ticket, required, tried, earliest, after, before);
			}finally{ metadataLock.unlock(); }
			
			if(e == null) return null;
			after = e.address();
			boolean consumed = false;
			try{
				var candidate = splitCandidate(e, ticket, earliest);
				if(candidate == null || !ticket.approve(candidate)){
					// Successful first attempts need no retry bookkeeping. Earliest
					// selection already advances monotonically through addresses.
					if(!earliest){
						if(tried == null) tried = new HashSet<>();
						tried.add(e.address());
					}
					continue;
				}
				if(dryRun){
					return candidate;
				}
				
				try(var ignored = context.getSource().lockIO()){
					metadataLock.lock();
					
					try(var transaction = context.getSource().openIOTransaction()){
						checkHealthy();
						
						long remaining = e.free.totalSize() - candidate.totalSize();
						if(remaining == 0){
							unlink(e);
						}else if(earliest){
							unlink(e);
							var remainder = insert(candidate.dataEnd(), remaining);
							lastChipped = remainder.address();
						}else{
							replace(e, new FreeChunk(e.free.getPtr(), remaining, e.free.nextPtr()));
							e.claimed = false;
							indexSize(e);
							var remainder = coalesce(e);
							lastChipped = remainder.address();
						}
						
						candidate.writeHeader();
						context.getChunkCache().add(candidate);
						
						transaction.commit();
						consumed = true;
					}catch(IOException|RuntimeException failure){
						poison(failure);
						throw failure;
					}finally{ metadataLock.unlock(); }
				}
				
				zero(candidate.dataStart(), candidate.getCapacity());
				return candidate;
			}finally{
				if(!consumed){
					try(var ignored = context.getSource().lockIO()){
						boolean ownsTail = tailLock.tryLock();
						metadataLock.lock();
						try(var transaction = context.getSource().openIOTransaction()){
							
							if(publicationFailure == null){
								release(e);
								if(ownsTail) trimTail();
							}
							
							transaction.commit();
						}catch(IOException|RuntimeException failure){
							poison(failure);
							throw failure;
						}finally{
							metadataLock.unlock();
							if(ownsTail) tailLock.unlock();
						}
					}
				}
			}
		}
		return null;
	}
	private Chunk append(AllocateTicket ticket, boolean dryRun) throws IOException{
		context.checkValid();
		while(true){
			long end   = physicalEnd;
			var  chunk = new ChunkBuilder(context, ChunkPointer.of(end), ticket).ensureMinSize().create();
			if(!ticket.approve(chunk)){
				return null;
			}
			
			boolean retry = false;
			try(var ignored = context.getSource().lockIO()){
				tailLock.lock();
				metadataLock.lock();
				try{
					checkHealthy();
					
					// Approval may perform nested allocations or frees on this same thread
					if(end != physicalEnd){
						retry = true;
					}else if(dryRun){
						return chunk;
					}else{
						try(var transaction = context.getSource().openIOTransaction()){
							if(context.getSource().getIOSize()<physicalEnd){
								throw new IOException("File truncated outside allocator");
							}
							
							chunk.writeHeader();
							
							try(var io = context.getSource().io()){
								if(chunk.getCapacity()>0){
									io.setPos(chunk.dataEnd() - 1).writeInt1(0);
								}
								io.setCapacity(chunk.dataEnd());
							}
							
							context.getChunkCache().add(chunk);
							physicalEnd = chunk.dataEnd();
							
							transaction.commit();
						}
					}
				}catch(IOException|RuntimeException failure){
					poison(failure);
					throw failure;
				}finally{
					metadataLock.unlock();
					tailLock.unlock();
				}
			}
			if(retry) continue;
			
			zero(chunk.dataStart(), chunk.getCapacity());
			return chunk;
		}
	}
	
	private void zero(long start, long size) throws IOException{
		if(size == 0) return;
		try(var io = context.getSource().ioAt(start)){
			IOUtils.zeroFill(io, size);
		}
	}
	
	@Override
	public Chunk alloc(AllocateTicket ticket) throws IOException{
		Chunk chunk = reuse(ticket, false);
		if(chunk == null){
			chunk = append(ticket, false);
		}
		
		return populateChunk(ticket, chunk);
	}
	@Override
	public Chunk allocBefore(AllocateTicket ticket, ChunkPointer before) throws IOException{
		var chunk = reuse(ticket.withApproval(candidate -> {
			return candidate.dataEnd()<=before.getValue();
		}), false, true, before.getValue());
		
		return populateChunk(ticket, chunk);
	}
	private Chunk populateChunk(AllocateTicket ticket, Chunk chunk) throws IOException{
		if(chunk == null) return null;
		
		context.getSource().openIOTransaction(() -> {
			context.getUnmanagedRegistry().allocated(chunk.getPtr());
			if(ticket.dataPopulator() != null) ticket.dataPopulator().accept(chunk);
		});
		return chunk;
	}
	
	@Override
	public boolean canAllocBefore(AllocateTicket ticket, ChunkPointer before) throws IOException{
		return reuse(ticket.withApproval(candidate -> {
			return candidate.dataEnd()<=before.getValue();
		}), true, true, before.getValue()) != null;
	}
	@Override
	public boolean canAlloc(AllocateTicket ticket) throws IOException{
		return reuse(ticket, true) != null || append(ticket, true) != null;
	}
	
	@Override
	public void allocTo(Chunk first, Chunk target, long toAllocate) throws IOException{
		if(toAllocate<0){
			throw new IllegalArgumentException();
		}
		
		try(var ignored = context.getSource().lockIO()){
			context.checkValid();
			
			var lock = chainLocks[(int)((first.getPtr().getValue() >>> 3)&(chainLocks.length - 1))];
			lock.lock();
			try{
				while(toAllocate>0){
					target = target.last();
					
					long added;
					tailLock.lock();
					metadataLock.lock();
					try(var transaction = context.getSource().openIOTransaction()){
						checkHealthy();
						
						added = MemoryOperations.growFileAlloc(target, toAllocate);
						if(added>0){
							physicalEnd = target.dataEnd();
						}
						if(added == 0){
							added = growAdjacent(target, toAllocate);
						}
						
						transaction.commit();
					}catch(IOException|RuntimeException failure){
						poison(failure);
						throw failure;
					}finally{
						metadataLock.unlock();
						tailLock.unlock();
					}
					
					if(added == 0) added = MemoryOperations.allocateBySimpleNextAssign(this, first, target, toAllocate);
					if(added == 0) added = MemoryOperations.allocateByChainWalkUpDefragment(this, first, target, toAllocate);
					if(added == 0) added = MemoryOperations.allocateByGrowingHeaderNextAssign(this, first, target, toAllocate);
					if(added<=0){
						throw new IOException("Cannot extend " + target + " by " + toAllocate);
					}
					
					toAllocate -= added;
				}
			}finally{
				lock.unlock();
			}
		}
	}
	private long growAdjacent(Chunk target, long requested) throws IOException{
		var e = byAddress.get(target.dataEnd());
		if(e == null || e.claimed){
			return 0;
		}
		
		long available = target.getBodyNumSize().remaining(target.getCapacity());
		long take      = Math.min(Math.min(requested, available), e.free.totalSize());
		if(take<=0){
			return 0;
		}
		
		long remaining = e.free.totalSize() - take;
		if(remaining>0 && remaining<Chunk.minSafeSize()){
			if(available>=e.free.totalSize()){
				take = e.free.totalSize();
				remaining = 0;
			}else{
				take = e.free.totalSize() - Chunk.minSafeSize();
				remaining = Chunk.minSafeSize();
				if(take<=0) return 0;
			}
		}
		
		long start = e.address();
		unlink(e);
		zero(start, take);
		
		if(remaining>0){
			insert(start + take, remaining);
		}
		try{
			target.setCapacity(target.getCapacity() + take);
		}catch(OutOfBitDepth impossible){
			throw new ShouldNeverHappenError(impossible);
		}
		target.syncStruct();
		return take;
	}
	
	@Override
	public IOList<ChunkPointer> getFreeChunks(){
		try(var ignored = context.getSource().lockIO()){
			context.checkValid();
			metadataLock.lock();
			try{
				return IOList.wrap(List.copyOf(byAddress.values().stream().map(e -> e.free.getPtr()).toList()));
			}finally{ metadataLock.unlock(); }
		}
	}
	@Override
	public void notifyStart(ChunkChainIO chain){
		try(var ignored = context.getSource().lockIO()){
			context.checkValid();
			metadataLock.lock();
			try{
				if(DEBUG_VALIDATION) try{
					chain.head.requireReal();
				}catch(CacheOutOfSync freed){
					throw new IllegalStateException("Opening a freed chunk", freed);
				}
				
				var thread = Thread.currentThread();
				var stack  = openChains.get(thread);
				if(stack == null){
					stack = chainStacks.get();
					openChains.put(thread, stack);
				}
				stack.push(chain);
			}finally{ metadataLock.unlock(); }
		}
	}
	@Override
	public void notifyEnd(ChunkChainIO chain){
		try(var ignored = context.getSource().lockIO()){
			context.checkValid();
			metadataLock.lock();
			try{
				var stack = openChains.get(Thread.currentThread());
				if(stack == null || stack.isEmpty()){
					throw new IllegalStateException("There is nothing that should have ended!");
				}
				
				if(stack.peek() != chain){
					int ahead = 0;
					for(var opened : stack){
						if(opened == chain)
							throw new IllegalStateException("Chain is closed in wrong order! There is " + ahead + " open chains ahead!");
						ahead++;
					}
					throw new IllegalStateException("Chain was already closed!");
				}
				stack.pop();
				if(stack.isEmpty()){
					openChains.remove(Thread.currentThread());
				}
			}finally{ metadataLock.unlock(); }
		}
	}
	@Override
	public void checkCanFree(Chunk chunk) throws IOException{
		try(var ignored = context.getSource().lockIO()){
			context.checkValid();
			metadataLock.lock();
			try{
				checkHealthy();
				chunk.requireReal();
				assertNotOpen(chunk);
			}finally{ metadataLock.unlock(); }
		}
	}
	
	private void assertNotOpen(Chunk chunk) throws FreeWhileUsed{
		for(var stack : openChains.values()){
			for(var chain : stack){
				for(var pinned : chain.head.walkNext()){
					if(pinned.getPtr().equals(chunk.getPtr())){
						throw new FreeWhileUsed("Cannot free open chunk " + chunk.getPtr());
					}
				}
			}
		}
	}
	@Override
	public void free(Collection<Chunk> chunks) throws IOException{
		try(var ignored = context.getSource().lockIO()){
			context.checkValid();
			freeLocked(chunks);
		}
	}
	private void freeLocked(Collection<Chunk> chunks) throws IOException{
		if(chunks.isEmpty()){
			return;
		}
		
		var sorted = new ArrayList<>(chunks);
		sorted.sort(Comparator.comparingLong(ch -> ch.getPtr().getValue()));
		
		var pending = new ArrayList<Entry>(sorted.size());
		metadataLock.lock();
		try{
			checkHealthy();
			
			// Validate the whole batch before changing any bytes.
			long previousEnd = -1;
			for(var chunk : sorted){
				chunk.requireReal();
				
				long address = chunk.getPtr().getValue();
				if(address<previousEnd || chunk.totalSize()<Chunk.minSafeSize() || chunk.dataEnd()>context.getSource().getIOSize()){
					throw new IOException("Invalid or overlapping free batch at " + chunk.getPtr());
				}
				
				previousEnd = chunk.dataEnd();
				
				var prev = byAddress.floorEntry(address);
				var next = byAddress.ceilingEntry(address);
				if(prev != null && prev.getValue().free.dataEnd()>address || next != null && chunk.dataEnd()>next.getKey()){
					throw new IOException("Already free: " + chunk.getPtr());
				}
				
				assertNotOpen(chunk);
			}
			
			try(var transaction = context.getSource().openIOTransaction()){
				for(var chunk : sorted){
					// Claimed nodes remain linked but cannot be reused or merged
					zero(chunk.getPtr().getValue(), chunk.getHeaderSize());
					context.getChunkCache().notifyDestroyed(chunk);
					pending.add(insert(chunk.getPtr().getValue(), chunk.totalSize(), true));
				}
				transaction.commit();
			}catch(IOException|RuntimeException failure){
				poison(failure);
				throw failure;
			}
		}finally{ metadataLock.unlock(); }
		
		try{
			// Large purges do not block selection or publication for unrelated extents.
			for(var e : pending){
				switch(ConfigDefs.PURGE_ACCIDENTAL_CHUNK_HEADERS.resolve()){
					case ZERO_OUT -> zero(e.free.dataStart(), e.free.getCapacity());
					case ONLY_HEADER_BYTES -> MemoryOperations.purgePossibleChunkHeaders(context, e.free.dataStart(), e.free.getCapacity());
					case NO_OP -> { }
				}
			}
		}catch(IOException|RuntimeException failure){
			metadataLock.lock();
			try{
				poison(failure);
			}finally{
				metadataLock.unlock();
			}
			throw failure;
		}
		
		boolean ownsTail = tailLock.tryLock();
		metadataLock.lock();
		try(var transaction = context.getSource().openIOTransaction()){
			checkHealthy();
			
			for(var e : pending){
				release(e);
			}
			if(ownsTail) trimTail();
			
			transaction.commit();
		}catch(IOException|RuntimeException failure){
			poison(failure);
			throw failure;
		}finally{
			metadataLock.unlock();
			if(ownsTail) tailLock.unlock();
		}
	}
	
	private void trimTail() throws IOException{
		while(!byAddress.isEmpty()){
			var e = byAddress.lastEntry().getValue();
			if(e.claimed || e.free.dataEnd() != physicalEnd) return;
			
			long newEnd = e.address();
			unlink(e);
			try(var io = context.getSource().io()){
				io.setCapacity(newEnd);
			}
			physicalEnd = newEnd;
		}
	}
}
