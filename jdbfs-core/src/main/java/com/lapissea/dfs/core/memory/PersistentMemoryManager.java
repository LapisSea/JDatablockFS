package com.lapissea.dfs.core.memory;

import com.lapissea.dfs.core.Cluster;
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
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public final class PersistentMemoryManager extends MemoryManager.StrategyImpl{
	
	private final List<ChunkPointer>   queuedFreeChunks   = new ArrayList<>();
	private final IOList<ChunkPointer> queuedFreeChunksIO = IOList.wrap(queuedFreeChunks);
	
	private final IOList<ChunkPointer> freeChunks;
	private final Lock                 freeChunksLock = new ReentrantLock();
	private       boolean              defragmentMode;
	
	private boolean adding, allowFreeRemove = true;
	
	private static final class Node extends AbstractList<ChunkChainIO>{
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
			var d   = data;
			var s   = --size;
			var old = d[s];
			d[s] = null;
			return old;
		}
		
		@Override
		public int size(){ return size; }
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
			throw new IllegalStateException();
		}
	}
	
	private void block(){
		if(drainThread == Thread.currentThread()) return;
		UtilL.sleepWhile(() -> drainIO, 0, 0.1F);
	}
	
	public PersistentMemoryManager(Cluster context, IOList<ChunkPointer> freeChunks){
		super(context);
		this.freeChunks = freeChunks;
	}
	
	@Override
	protected List<AllocStrategy> createAllocs(){
		return List.of(
			(ctx, ticket, dryRun) -> {
				if(defragmentMode) return null;
				return MemoryOperations.allocateReuseFreeChunk(ctx, ticket, allowFreeRemove, dryRun);
			},
			MemoryOperations::allocateAppendToFile
		);
	}
	
	@Override
	protected List<AllocToStrategy> createAllocTos(){
		return List.of(
			(first, target, toAllocate) -> MemoryOperations.growFileAlloc(target, toAllocate),
			(first, target, toAllocate) -> MemoryOperations.growFreeAlloc(this, target, toAllocate, allowFreeRemove),
			(first, target, toAllocate) -> MemoryOperations.allocateBySimpleNextAssign(this, first, target, toAllocate),
			(first, target, toAllocate) -> MemoryOperations.allocateByChainWalkUpDefragment(this, first, target, toAllocate),
			(first, target, toAllocate) -> MemoryOperations.allocateByGrowingHeaderNextAssign(this, first, target, toAllocate)
		);
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
						var err = Log.resolveArgs(
							"{}#red was called to be freed but it is currently locked{}!",
							chToFree,
							lockedCH == c.head? "" : Log.resolveArgs(" by {}#yellow", lockedCH)
						).toString();
						throw new FreeWhileUsed(err);
					}
				}
			}
		}
		
		var popped = popFile(toFree);
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
										allStacks.entrySet().stream()
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
