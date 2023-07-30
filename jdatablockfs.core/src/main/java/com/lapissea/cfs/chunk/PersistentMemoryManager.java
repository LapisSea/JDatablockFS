package com.lapissea.cfs.chunk;

import com.lapissea.cfs.io.ChunkChainIO;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.Wrapper;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PersistentMemoryManager extends MemoryManager.StrategyImpl{
	
	private final List<ChunkPointer>   queuedFreeChunks   = new ArrayList<>();
	private final IOList<ChunkPointer> queuedFreeChunksIO = IOList.wrap(queuedFreeChunks);
	
	private final IOList<ChunkPointer> freeChunks;
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
				var ptr = queuedFreeChunks.remove(queuedFreeChunks.size() - 1);
				var ch  = context.getChunk(ptr);
				chs.add(ch);
			}
			return chs;
		}
	}
	
	@Override
	public void free(Collection<Chunk> toFree) throws IOException{
		if(toFree.isEmpty()) return;
		
		var popped = popFile(toFree);
		if(popped.isEmpty()){
			tryPopFree();
			return;
		}
		
		List<Chunk> toAdd = MemoryOperations.mergeChunks(popped);
		
		if(adding){
			addQueue(toAdd);
			return;
		}
		
		adding = true;
		try{
			addQueue(toAdd);
			do{
				var capacity        = freeChunks.getCapacity();
				var optimalCapacity = freeChunks.size() + queuedFreeChunks.size();
				if(capacity<optimalCapacity){
					var cap = optimalCapacity + 1;
					synchronized(freeChunks){
						freeChunks.requestCapacity(cap);
					}
				}
				
				var chs = popQueue();
				synchronized(freeChunks){
					try(var ignored = context.getSource().openIOTransaction()){
						MemoryOperations.mergeFreeChunksSorted(context, freeChunks, chs);
					}
				}
			}while(!queuedFreeChunks.isEmpty());
		}finally{
			adding = false;
		}
		
		tryPopFree();
	}
	
	private boolean popping;
	private void tryPopFree() throws IOException{
		if(adding || popping) return;
		popping = true;
		try{
			boolean anyPopped;
			do{
				anyPopped = false;
				var lastChO = freeChunks.peekLast().map(p -> p.dereference(context));
				if(lastChO.filter(Chunk::checkLastPhysical).isPresent()){
					var lastCh = lastChO.get();
					
					freeChunks.popLast();
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
					var nextO = lastChO.map(Chunk::nextPhysical);
					if(nextO.filter(Chunk::checkLastPhysical).isPresent()){
						var lastFree = lastChO.get();
						var toMove   = nextO.get();
						
						var stack = getStack();
						for(var c : stack){
							if(c.head.equals(toMove)){
								return;
							}
						}
						
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
								var freeRef = fch.getReference().getPtr();
								if(freeRef.dereference(context).walkNext().anyMatch(c -> c == toMove)){
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
							}
						}finally{
							allowFreeRemove = true;
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
		var end = toFree.iterator().next().getDataProvider().getSource().getIOSize();
		
		List<Chunk> toNotify = new ArrayList<>();
		
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
				end = chunk.getPtr().getValue();
				var ptr = chunk.getPtr();
				
				i.remove();
				toNotify.add(chunk);
				
				for(Chunk c : result){
					if(c.getNextPtr().equals(ptr)){
						c.modifyAndSave(Chunk::clearNextPtr);
					}
				}
				continue wh;
			}
			if(!dirty){
				try(var io = context.getSource().io()){
					io.setCapacity(end);
				}
				for(Chunk chunk : toNotify){
					context.getChunkCache().notifyDestroyed(chunk);
				}
			}
			return result;
		}
	}
}
