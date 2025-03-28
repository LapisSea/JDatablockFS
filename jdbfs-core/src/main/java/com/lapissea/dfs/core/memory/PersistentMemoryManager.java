package com.lapissea.dfs.core.memory;

import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.Cluster;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.DefragmentManager;
import com.lapissea.dfs.core.MemoryManager;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.ChunkChainIO;
import com.lapissea.dfs.exceptions.FreeWhileUsed;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.Wrapper;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.utils.OptionalPP;
import com.lapissea.dfs.utils.iterableplus.IterablePPSource;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.dfs.utils.iterableplus.Match;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public final class PersistentMemoryManager
	extends MemoryManager.StrategyImpl<PersistentMemoryManager.AllocStrategy, PersistentMemoryManager.AllocToStrategy>{
	
	protected enum AllocStrategy{
		REUSE_FREE_CHUNKS,
		APPEND_TO_FILE
	}
	
	protected enum AllocToStrategy{
		GROW_FILE_ALLOC,
		GROW_FREE_ALLOC,
		SIMPLE_NEXT_ASSIGN,
		CHAIN_WALK_UP_DEFRAGMENT,
		GROWING_HEADER_NEXT_ASSIGN,
	}
	
	private final List<ChunkPointer>   queuedFreeChunks   = new ArrayList<>();
	private final IOList<ChunkPointer> queuedFreeChunksIO = IOList.wrap(queuedFreeChunks);
	
	private final IOList<ChunkPointer> freeChunks;
	private final Lock                 freeChunksLock = new ReentrantLock();
	private       boolean              defragmentMode;
	
	private boolean adding, allowFreeRemove = true;
	
	private static final class Node extends AbstractList<ChunkChainIO> implements IterablePPSource<ChunkChainIO>{
		private       ChunkChainIO[] data = new ChunkChainIO[1];
		private       int            size;
		private final Thread         thread;
		private Node(Thread thread){ this.thread = thread; }
		@Override
		public boolean add(ChunkChainIO v){
			var d = data;
			var s = size;
			if(d.length == s){
				data = d = Arrays.copyOf(d, d.length*2);
			}
			d[s] = v;
			size = s + 1;
			return true;
		}
		@Override
		public ChunkChainIO get(int index){ return data[index]; }
		private ChunkChainIO pop(){
			var d = data;
			var s = size - 1;
			if(s<0){
				return null;
			}
			
			var old = d[s];
			d[s] = null;
			size = s;
			return old;
		}
		
		@Override
		public int size(){ return size; }
		@Override
		public OptionalInt tryGetSize(){ return OptionalInt.of(size); }
	}
	
	
	private final ThreadLocal<Node> stacks    = new ThreadLocal<>();
	private final Map<Thread, Node> allStacks = new HashMap<>();
	private       Node              last;
	private       boolean           drainIO;
	private       Thread            drainThread;
	
	private Node getStack(){
		var th = Thread.currentThread();
		var l  = last;
		if(l != null && l.thread == th){
			return l;
		}
		
		var s = stacks.get();
		if(s == null){
			s = new Node(th);
			registerNode(th, s);
		}
		return last = s;
	}
	
	private void registerNode(Thread th, Node s){
		stacks.set(s);
		synchronized(allStacks){
			cleanupStacks();
			allStacks.put(th, s);
		}
	}
	private void cleanupStacks(){
		if(allStacks.isEmpty()) return;
		allStacks.keySet().removeIf(e -> !e.isAlive());
	}
	
	@Override
	public void notifyStart(ChunkChainIO chain){
		if(drainIO) block();
		getStack().add(chain);
	}
	@Override
	public void notifyEnd(ChunkChainIO chain){
		var s  = getStack();
		var ch = s.pop();
		if(ch != chain){
			failNotifyEnd(s, ch, chain);
		}
	}
	private static void failNotifyEnd(Node stack, ChunkChainIO popped, ChunkChainIO chain){
		if(popped == null){
			throw new IllegalStateException("There is nothing that should have ended!");
		}
		
		if(stack.enumerated().firstMatchingM(e -> e.val() == chain) instanceof Match.Some(var found)){
			var count = stack.size() - found.index();
			stack.add(popped);
			throw new IllegalStateException("Chain is closed in wrong order! There is " + count + " open chains ahead!");
		}
		
		stack.add(popped);
		throw new IllegalStateException("Chain was already closed!");
	}
	
	private void block(){
		if(drainThread == Thread.currentThread()) return;
		UtilL.sleepWhile(() -> drainIO, 0, 0.1F);
	}
	
	public PersistentMemoryManager(Cluster context, IOList<ChunkPointer> freeChunks){
		super(context, AllocStrategy.class.getEnumConstants(), AllocToStrategy.class.getEnumConstants());
		this.freeChunks = freeChunks;
	}
	
	private final Lock fileSizeLock = new ReentrantLock();
	
	@Override
	protected Chunk alloc(AllocStrategy strategy, DataProvider ctx, AllocateTicket ticket, boolean dryRun) throws IOException{
		return switch(strategy){
			case REUSE_FREE_CHUNKS -> {
				if(defragmentMode) yield null;
				freeChunksLock.lock();
				try{
					yield MemoryOperations.allocateReuseFreeChunk(ctx, ticket, allowFreeRemove, dryRun);
				}finally{
					freeChunksLock.unlock();
				}
			}
			case APPEND_TO_FILE -> {
				fileSizeLock.lock();
				try{
					yield MemoryOperations.allocateAppendToFile(ctx, ticket, dryRun);
				}finally{
					fileSizeLock.unlock();
				}
			}
		};
	}
	@Override
	protected long allocTo(AllocToStrategy strategy, Chunk first, Chunk target, long toAllocate) throws IOException{
		return switch(strategy){
			case GROW_FILE_ALLOC -> {
				fileSizeLock.lock();
				try{
					yield MemoryOperations.growFileAlloc(target, toAllocate);
				}finally{
					fileSizeLock.unlock();
				}
			}
			case GROW_FREE_ALLOC -> {
				freeChunksLock.lock();
				try{
					yield MemoryOperations.growFreeAlloc(this, target, toAllocate, allowFreeRemove);
				}finally{
					freeChunksLock.unlock();
				}
			}
			case SIMPLE_NEXT_ASSIGN -> MemoryOperations.allocateBySimpleNextAssign(this, first, target, toAllocate);
			case CHAIN_WALK_UP_DEFRAGMENT -> MemoryOperations.allocateByChainWalkUpDefragment(this, first, target, toAllocate);
			case GROWING_HEADER_NEXT_ASSIGN -> MemoryOperations.allocateByGrowingHeaderNextAssign(this, first, target, toAllocate);
		};
	}
	
	@Override
	public DefragSes openDefragmentMode(){
		boolean oldDefrag = defragmentMode;
		defragmentMode = true;
		return () -> defragmentMode = oldDefrag;
	}
	@Override
	public IOList<ChunkPointer> getFreeChunks(){
		return adding? queuedFreeChunksIO : freeChunks;
	}
	
	private void addQueue(Collection<Chunk> ptrs){
		synchronized(queuedFreeChunksIO){
			if(ptrs.size()<=2){
				for(Chunk ptr : ptrs){
					UtilL.addRemainSorted(queuedFreeChunks, ptr.getPtr());
				}
			}else{
				for(Chunk ptr : ptrs){
					queuedFreeChunks.add(ptr.getPtr());
				}
				queuedFreeChunks.sort(Comparator.naturalOrder());
			}
		}
	}
	private List<Chunk> popQueue() throws IOException{
		synchronized(queuedFreeChunksIO){
			var chs = new ArrayList<Chunk>(queuedFreeChunks.size());
			while(!queuedFreeChunks.isEmpty()){
				var ptr = queuedFreeChunks.removeLast();
				var ch  = context.getChunk(ptr);
				chs.add(ch);
			}
			return chs;
		}
	}
	
	@Override
	public void free(Collection<Chunk> toFree) throws IOException{
		if(toFree.isEmpty()) return;
		
		for(Chunk chToFree : toFree){
			var stack = getStack();
			for(var c : stack){
				for(Chunk lockedCH : c.head.walkNext()){
					if(chToFree == lockedCH){
						var err = Log.fmt(
							"{}#red was called to be freed but it is currently locked{}!",
							chToFree,
							lockedCH == c.head? "" : Log.fmt(" by {}#yellow", lockedCH)
						);
						throw new FreeWhileUsed(err);
					}
				}
			}
		}
		
		Collection<Chunk> popped;
		fileSizeLock.lock();
		try{
			popped = popFile(toFree);
		}finally{
			fileSizeLock.unlock();
		}
		if(popped.isEmpty()){
			return;
		}
		
		List<Chunk> toAdd = MemoryOperations.mergeChunks(popped);
		
		if(adding){
			addQueue(toAdd);
			return;
		}
		
		freeChunksLock.lock();
		adding = true;
		var oldAllowFreeRemove = allowFreeRemove;
		allowFreeRemove = false;
		try{
			addQueue(toAdd);
			do{
				var capacity        = freeChunks.getCapacity();
				var optimalCapacity = freeChunks.size() + queuedFreeChunks.size();
				if(capacity<optimalCapacity){
					var cap = optimalCapacity + 1;
					freeChunks.requestCapacity(cap);
				}
				
				var chs = popQueue();
				try(var ignored = context.getSource().openIOTransaction()){
					MemoryOperations.mergeFreeChunksSorted(context, freeChunks, chs);
				}
			}while(!queuedFreeChunks.isEmpty());
		}finally{
			adding = false;
			freeChunksLock.unlock();
			allowFreeRemove = oldAllowFreeRemove;
		}
		
		tryPopFree();
	}
	
	private record MoveState(Chunk free, Chunk toMove){ }
	
	private boolean   popping;
	private MoveState badMoveState;
	private void tryPopFree() throws IOException{
		if(!allowFreeRemove || popping) return;
		popping = true;
		try{
			boolean anyPopped;
			do{
				anyPopped = false;
				var lastFreeO = freeChunks.isEmpty()? OptionalPP.<Chunk>empty() : OptionalPP.of(freeChunks.getLast().dereference(context));
				if(lastFreeO.filter(Chunk::checkLastPhysical).isPresent()){
					var lastCh = lastFreeO.get();
					
					freeChunks.removeLast();
					if(lastCh.checkLastPhysical()){
						try(var io = context.getSource().io()){
							io.setCapacity(lastCh.getPtr().getValue());
						}
						context.getChunkCache().notifyDestroyed(lastCh);
						anyPopped = true;
					}else{
						free(lastCh);
						return;
					}
				}else if(freeChunks.size()>1){
					var nextO = lastFreeO.filter(c -> c.getCapacity()>=32).map(Chunk::nextPhysical);
					if(nextO.filter(Chunk::checkLastPhysical).isPresent()){
						var lastFree = lastFreeO.get();
						var toMove   = nextO.get();
						
						if(lastFree.getCapacity()<toMove.getSize()*2){
							return;
						}
						
						if(badMoveState != null && badMoveState.equals(new MoveState(lastFree, toMove))){
							return;
						}
						
						var stack = getStack();
						for(var c : stack){
							if(c.head.equals(toMove)){
								return;
							}
						}
						var oldAllowFreeRemove = allowFreeRemove;
						try{
							drainThread = Thread.currentThread();
							drainIO = true;
							while(true){
								synchronized(allStacks){
									cleanupStacks();
									var anyActive =
										allStacks.size()>1 &&
										Iters.entries(allStacks)
										     .filter(e -> e.getKey().isAlive()).map(Map.Entry::getValue)
										     .anyMatch(l -> l != stack && !l.isEmpty());
									if(!anyActive) break;
								}
								UtilL.sleep(0.1F);
							}
							
							{//Disable modification of the list while it is being moved
								var fch     = (IOInstance.Unmanaged<?>)Wrapper.fullyUnwrappObj(freeChunks);
								var freeRef = fch.getPointer();
								if(freeRef.dereference(context).walkNext().anyIs(toMove)){
									allowFreeRemove = false;
								}
							}
							
							var move = DefragmentManager.moveReference(
								(Cluster)context, toMove.getPtr(),
								t -> t.withApproval(ch -> ch.getPtr().compareTo(lastFree.getPtr())<0),
								false);
							if(move.hasAny()){
								anyPopped = true;
								for(var cha : stack){
									if(move.chainAffected(cha.head)){
										cha.revalidate();
									}
								}
							}else badMoveState = new MoveState(lastFree.clone(), toMove.clone());
						}finally{
							allowFreeRemove = oldAllowFreeRemove;
							drainIO = false;
						}
					}
				}
			}while(anyPopped);
		}finally{
			popping = false;
		}
	}
	
	private Collection<Chunk> popFile(Collection<Chunk> toFree) throws IOException{
		Collection<Chunk> result = toFree;
		boolean           dirty  = true;
		
		if(toFree.isEmpty()) return toFree;
		var end = context.getSource().getIOSize();
		
		List<Chunk> toNotify = new ArrayList<>();
		boolean     any      = false;
		
		Map<ChunkPointer, Chunk> nextMap = null;
		Set<Chunk>               toClear = null;
		
		wh:
		while(true){
			for(var i = result.iterator(); i.hasNext(); ){
				Chunk chunk = i.next();
				if(chunk.dataEnd()<end) continue;
				
				if(dirty){
					dirty = false;
					result = new ArrayList<>(result);
					continue wh;
				}
				any = true;
				end = chunk.getPtr().getValue();
				var ptr = chunk.getPtr();
				
				i.remove();
				if(toClear != null) toClear.remove(chunk);
				toNotify.add(chunk);
				
				if(nextMap == null){
					nextMap = new HashMap<>();
					for(var c : result){
						var next = c.getNextPtr();
						if(!next.isNull()) nextMap.put(next, c);
					}
				}
				
				var prev = nextMap.get(ptr);
				if(prev != null){
					if(toClear == null) toClear = new HashSet<>();
					toClear.add(prev);
				}
				continue wh;
			}
			
			if(!any){
				return result;
			}
			any = false;
			
			try(var io = context.getSource().io()){
				if(toClear != null) for(var c : toClear){
					if(c.getPtr().compareTo(end)>=0) continue;
					c.setSize(0);
					c.clearAndCompressHeader();
					if(!c.dirty()) continue;
					var b = c.writeHeaderToBuf();
					io.setPos(b.ioOffset()).write(b.data(), b.dataOffset(), b.dataLength());
				}
				io.setCapacity(end);
			}
			
			context.getChunkCache().notifyDestroyed(toNotify);
			toNotify.clear();
			
			var before = context.getSource().getIOSize();
			tryPopFree();
			var after = context.getSource().getIOSize();
			if(after>=before){
				return result;
			}
			end = after;
			if(toClear != null) toClear.clear();
		}
	}
}
