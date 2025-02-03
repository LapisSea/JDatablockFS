package com.lapissea.dfs.core.chunk;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.exceptions.CacheOutOfSync;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.utils.iterableplus.Iters;
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
	
	public ChunkCache(){ }
	
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
	
	public synchronized void notifyDestroyed(Iterable<Chunk> chunks){
		poolRefQueue();
		for(Chunk chunk : chunks){
			rem(chunk);
		}
	}
	public synchronized void notifyDestroyed(Chunk chunk){
		poolRefQueue();
		rem(chunk);
	}
	private void rem(Chunk chunk){
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
	
	public synchronized void validate(DataProvider provider) throws IOException{
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
	
	private Entry box(Chunk chunk){
		return new Entry(chunk, refQueue);
	}
}
