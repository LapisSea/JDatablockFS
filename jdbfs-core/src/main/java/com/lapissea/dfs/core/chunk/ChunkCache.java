package com.lapissea.dfs.core.chunk;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.CacheOutOfSync;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.iterableplus.Iters;
import com.lapissea.util.Nullable;

import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;

public final class ChunkCache{
	
	private static final class Entry extends WeakReference<Chunk>{
		private final ChunkPointer ptr;
		private       boolean      inData = true;
		private Entry(Chunk referent, ReferenceQueue<Chunk> q){
			super(referent, Objects.requireNonNull(q));
			this.ptr = referent.getPtr();
		}
	}
	
	private       Map<ChunkPointer, Entry> data     = new HashMap<>();
	private final ReferenceQueue<Chunk>    refQueue = new ReferenceQueue<>();
	
	
	private void poolRefQueue(){
		int   count = 0;
		Entry ref;
		while((ref = (Entry)refQueue.poll()) != null){
			count++;
			if(ref.inData){
				data.remove(ref.ptr);
			}
		}
		if(count>16 && count>data.size()){
			data = new HashMap<>(data); //Rebuild hashmap when most entries are removed to save memory
		}
	}
	
	
	/**
	 * Registers a written chunk over a reused pointer with a previous chunk no longer exists at it.
	 * Difference with {@link #add(Chunk)} is that a live previous entry is replaced because the pointer was repurposed.
	 */
	public synchronized void addReplacing(Chunk chunk){
		var prev = data.put(chunk.getPtr(), box(chunk));
		chunk.setReal(true);
		if(prev != null){
			var old = prev.get();
			if(old != null) old.setReal(false);
			prev.inData = false;
		}
	}
	
	public synchronized void add(Chunk chunk){
		Objects.requireNonNull(chunk);
		poolRefQueue();
		if(DEBUG_VALIDATION) addChecked(chunk);
		else add0(chunk);
	}
	private void addChecked(Chunk chunk){
		if(get0(chunk.getPtr()) != null){
			throw new IllegalStateException(chunk.getPtr() + " already exists");
		}
		add0(chunk);
	}
	private void add0(Chunk chunk){
		var existing = data.put(chunk.getPtr(), box(chunk));
		chunk.setReal(true);
		if(existing != null){
			handleExisting(existing);
		}
	}
	private void handleExisting(Entry existing){
		if(existing.get() != null){
			throw new IllegalStateException(existing.ptr + " already exists but shouldn't");
		}
		existing.inData = false;
	}
	
	@Nullable
	public synchronized Chunk get(ChunkPointer pointer){
		return get0(pointer);
	}
	
	private Chunk get0(ChunkPointer pointer){
		var ref = data.get(pointer);
		if(ref == null) return null;
		var chunk = ref.get();
		if(chunk == null){
			poolRefQueue();
		}
		return chunk;
	}
	
	public void notifyDestroyed(Iterable<Chunk> chunks){
		var iterator = chunks.iterator();
		if(!iterator.hasNext()) return;
		var first = iterator.next();
		try(var ignored = first.getDataProvider().getSource().lockIO()){
			synchronized(this){
				poolRefQueue();
				rem(first);
				while(iterator.hasNext()) rem(iterator.next());
			}
		}
	}
	public void notifyDestroyed(Chunk chunk){
		try(var ignored = chunk.getDataProvider().getSource().lockIO()){
			synchronized(this){
				poolRefQueue();
				rem(chunk);
			}
		}
	}
	private void rem(Chunk chunk){
		chunk.setReal(false);
		chunk.getDataProvider().getUnmanagedRegistry().destroyed(chunk);
		if(DEBUG_VALIDATION) validateDestroyed(chunk);
		var rem    = data.remove(chunk.getPtr());
		var remVal = rem.get();
		if(remVal != chunk) throw new AssertionError();
		rem.inData = false;
		rem.enqueue();
	}
	
	private void validateDestroyed(Chunk chunk){
		if(Chunk.isChunkValidAt(chunk.getDataProvider(), chunk.getPtr())){
			throw new IllegalStateException("Chunk at " + chunk.getPtr() + " is still valid!");
		}
		
		if(get0(chunk.getPtr()) == null){
			throw new IllegalStateException(chunk.getPtr() + " is not cached");
		}
	}
	
	public synchronized void requireReal(Chunk chunk) throws CacheOutOfSync{
		var cached = get0(chunk.getPtr());
		if(chunk != cached){
			throw new CacheOutOfSync(cached, chunk);
		}
	}
	
	public void validate(DataProvider provider) throws IOException{
		try(var ioAccess = provider.getSource().lockIO()){
			synchronized(this){
				Chunk f;
				try{
					f = provider.getFirstChunk();
				}catch(Throwable e){
					return;
				}
				poolRefQueue();
				var vals = Iters.from(data.values()).map(Reference::get).nonNulls().toList();
				
				for(Chunk cached : vals){
					try{
						var read = Chunk.readChunk(provider, cached.getPtr());
						if(!read.equals(cached)){
							throw new CacheOutOfSync(read, cached);
						}
					}catch(Throwable e){
						throw new RuntimeException(cached + "", e);
					}
					
				}
				
				outer:
				for(Chunk value : vals){
					for(Chunk chunk : new PhysicalChunkWalker(f)){
						if(chunk.getPtr().getValue()>value.getPtr().getValue()) break;
						if(chunk == value){
							continue outer;
						}
					}
					throw new CacheOutOfSync("Unreachable chunk: " + value);
				}
			}
		}
	}
	
	public synchronized void refreshAfterRollback(long epoch, Iterable<Chunk> destroyed) throws IOException{
		poolRefQueue();
		for(Chunk chunk : destroyed){
			if(chunk.createdInTransaction(epoch)) continue;
			var prev = data.put(chunk.getPtr(), box(chunk));
			chunk.setReal(true);
			if(prev != null) prev.inData = false;
		}
		var it = data.entrySet().iterator();
		while(it.hasNext()){
			var ent   = it.next();
			var chunk = ent.getValue().get();
			// A rolled-back free/merge can leave an entry whose pointer is no longer a chunk.
			// Evict those rather than failing the rollback on a slot that no longer exists.
			if(chunk == null || chunk.createdInTransaction(epoch) || !Chunk.isChunkValidAt(chunk.getDataProvider(), chunk.getPtr())){
				if(chunk != null) chunk.setReal(false);
				ent.getValue().inData = false;
				it.remove();
			}else{
				chunk.readHeader();
			}
		}
	}
	
	private Entry box(Chunk chunk){
		return new Entry(chunk, refQueue);
	}
	
	public synchronized void remove(ChunkPointer ptr){
		var e = data.remove(ptr);
		if(e != null){
			var chunk = e.get();
			if(chunk != null) chunk.setReal(false);
			e.inData = false;
		}
	}
}
