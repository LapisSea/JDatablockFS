package com.lapissea.dfs.core;

import com.lapissea.dfs.MagicID;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.objects.Blob;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.util.function.UnsafeFunction;
import com.lapissea.util.function.UnsafeRunnable;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.IOException;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A registry for unmanaged objects. It maps one JVM identity to one unmanaged head chunk, owned by a
 * database. The references are ALWAYS weakly stored!
 */
public final class UnmanagedRegistry{
	
	private static final class Entry extends WeakReference<IOInstance.Unmanaged<?>>{
		
		ChunkPointer pointer;
		Thread       creator = Thread.currentThread();
		boolean      ready, factoryConstruction;
		
		Entry(IOInstance.Unmanaged<?> instance, Chunk identity, ReferenceQueue<IOInstance.Unmanaged<?>> queue){
			super(instance, queue);
			this.pointer = identity.getPtr();
		}
	}
	
	private static final class Cache extends WeakReference<Object>{
		final Consumer<Object> clear;
		Cache(Object owner, Consumer<Object> clear, ReferenceQueue<Object> queue){
			super(owner, queue);
			this.clear = clear;
		}
	}
	
	private final IOInterface              source;
	private final Map<ChunkPointer, Entry> entries = new HashMap<>();
	
	private final ReferenceQueue<IOInstance.Unmanaged<?>> collected = new ReferenceQueue<>();
	private final List<WeakReference<DataProvider>>       providers = new ArrayList<>();
	private final List<WeakReference<UnmanagedRegistry>>  children  = new ArrayList<>();
	
	// Used for old references encountered later in a relocation resolve to the same instance.
	private final Map<ChunkPointer, Chunk> aliases = new HashMap<>();
	
	
	private final Map<Entry, Chunk>        undo      = new IdentityHashMap<>();
	private final Set<Entry>               born      = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<ChunkPointer>        allocated = new HashSet<>();
	private final Map<ChunkPointer, Chunk> destroyed = new HashMap<>();
	
	private boolean active;
	private boolean restoring;
	private int     constructionDepth;
	private long    epoch;
	
	
	public UnmanagedRegistry(IOInterface source){
		this.source = source;
	}
	
	public void registerChild(UnmanagedRegistry child){
		try(var ignored = source.lockIO()){
			children.removeIf(ref -> ref.get() == null);
			children.add(new WeakReference<>(child));
			if(active){
				child.begin();
			}
		}
	}
	
	private void visitChildren(Consumer<UnmanagedRegistry> action){
		children.removeIf(ref -> {
			var child = ref.get();
			if(child == null){
				return true;
			}
			action.accept(child);
			return false;
		});
	}
	
	private final ReferenceQueue<Object> collectedCaches = new ReferenceQueue<>();
	private final Set<Cache>             caches          = new HashSet<>();
	public void registerCache(Object owner, Consumer<Object> clear){
		try(var ignored = source.lockIO()){
			drain();
			caches.add(new Cache(owner, clear, collectedCaches));
		}
	}
	
	private final Map<Object, UnsafeRunnable<IOException>> stateUndo = new IdentityHashMap<>();
	public void enlist(Object owner, UnsafeSupplier<UnsafeRunnable<IOException>, IOException> snapshot) throws IOException{
		try(var ignored = source.lockIO()){
			if(active && !restoring && !stateUndo.containsKey(owner)){
				stateUndo.put(owner, snapshot.get());
			}
		}
	}
	
	/**
	 * Marks newly constructed physical chunks without retaining them or indexing every write
	 */
	public long creationEpoch(){ return active && !restoring? epoch : 0; }
	
	private void drain(){
		Cache cache;
		while((cache = (Cache)collectedCaches.poll()) != null){
			caches.remove(cache);
		}
		Entry entry;
		while((entry = (Entry)collected.poll()) != null){
			entries.remove(entry.pointer, entry);
		}
	}
	public int size(){
		try(var ignored = source.lockIO()){
			drain();
			return entries.size();
		}
	}
	
	public void registerProvider(DataProvider provider){
		try(var ignored = source.lockIO()){
			providers.removeIf(ref -> ref.get() == null);
			for(var ref : providers){
				if(ref.get() == provider){
					return;
				}
			}
			providers.add(new WeakReference<>(provider));
		}
	}
	
	public void register(IOInstance.Unmanaged<?> instance, Chunk identity){
		try(var ignored = source.lockIO()){
			drain();
			
			var previous = entries.get(identity.getPtr());
			if(previous != null && previous.get() != null){
				throw new IllegalStateException("Unmanaged identity already exists: " + identity.getPtr() + "; use Struct.Unmanaged.make");
			}
			
			var entry = new Entry(instance, identity, collected);
			entry.factoryConstruction = constructionDepth != 0;
			
			entries.put(identity.getPtr(), entry);
			
			if(active && allocated.contains(identity.getPtr())){
				born.add(entry);
			}
		}
	}
	
	public void markCompleted(IOInstance.Unmanaged<?> instance){
		try(var ignored = source.lockIO()){
			var entry = entries.get(instance.getPointer());
			if(entry == null || entry.get() != instance){
				throw new IllegalStateException("Unregistered unmanaged constructor");
			}
			entry.ready = true;
			entry.creator = null;
		}
	}
	
	@SuppressWarnings("unchecked")
	public <T extends IOInstance.Unmanaged<T>> T getOrCreate(Chunk identity, Class<T> type, IOType typeDef, boolean readOnly, UnsafeFunction<Chunk, T, IOException> constructor) throws IOException{
		try(var ignored = source.lockIO()){
			drain();
			var entry             = entries.get(identity.getPtr());
			var effectiveIdentity = identity;
			while(entry == null){
				var alias = aliases.get(effectiveIdentity.getPtr());
				if(alias == null){
					break;
				}
				
				effectiveIdentity = alias;
				entry = entries.get(effectiveIdentity.getPtr());
				
				if(entry == null && !aliases.containsKey(effectiveIdentity.getPtr())){
					break;
				}
			}
			
			var existing = entry == null? null : entry.get();
			if(existing != null){
				if(!entry.ready && !(entry.factoryConstruction && entry.creator == Thread.currentThread() && constructionDepth != 0)){
					throw new IllegalStateException("Unmanaged construction has not completed! Use the factory or finishConstruction()");
				}
				if(existing.getDataProvider().isReadOnly() != readOnly){
					throw new IllegalStateException("Canonical unmanaged identities cannot mix read-only and writable providers");
				}
				if(!type.isInstance(existing) || !existing.acceptsType(typeDef)){
					throw new IllegalStateException("Conflicting unmanaged types at " + identity.getPtr());
				}
				return (T)existing;
			}
			
			constructionDepth++;
			try{
				var result = constructor.apply(effectiveIdentity);
				markCompleted(result);
				return result;
			}catch(IOException|RuntimeException|Error failure){
				var partial  = entries.remove(effectiveIdentity.getPtr());
				var instance = partial == null? null : partial.get();
				if(instance != null){
					instance.detachIdentity();
				}
				throw failure;
			}finally{
				constructionDepth--;
			}
		}
	}
	
	public void move(IOInstance.Unmanaged<?> instance, Chunk from, Chunk to){
		try(var ignored = source.lockIO()){
			var entry = entries.get(from.getPtr());
			if(entry == null || entry.get() != instance){
				throw new IllegalStateException("Unregistered unmanaged identity: " + from.getPtr());
			}
			
			var collision = entries.get(to.getPtr());
			if(collision != null && collision != entry && collision.get() != null){
				throw new IllegalStateException("Destination identity already exists: " + to.getPtr());
			}
			
			if(active && !restoring){
				undo.putIfAbsent(entry, from);
			}
			
			entries.remove(from.getPtr(), entry);
			aliases.remove(to.getPtr());
			
			if(active && !restoring && !from.getPtr().equals(to.getPtr())){
				aliases.put(from.getPtr(), to);
			}
			
			entry.pointer = to.getPtr();
			entries.put(to.getPtr(), entry);
			
			instance.restoreIdentity(to);
		}
	}
	
	public void allocated(ChunkPointer pointer){
		aliases.remove(pointer);
		if(active && !restoring){
			allocated.add(pointer);
		}
	}
	public void destroyed(Chunk chunk){
		if(active && !restoring){
			destroyed.putIfAbsent(chunk.getPtr(), chunk);
		}
		freed(chunk);
	}
	public void freed(Chunk chunk){
		aliases.remove(chunk.getPtr());
		var entry = entries.remove(chunk.getPtr());
		if(entry == null){
			return;
		}
		var instance = entry.get();
		if(instance != null){
			if(active && !restoring){
				undo.putIfAbsent(entry, instance.getIdentityForRegistry());
			}
			instance.detachIdentity();
		}
	}
	
	public void begin(){
		epoch++;
		active = true;
		visitChildren(UnmanagedRegistry::begin);
	}
	
	public void committed(){
		visitChildren(child -> {
			if(child.source instanceof Blob blob && blob.isFreed()){
				child.invalidate();
			}else{
				child.committed();
			}
		});
		clearUndo();
	}
	
	private void invalidate(){
		for(var entry : entries.values()){
			var instance = entry.get();
			if(instance != null){
				instance.detachIdentity();
			}
		}
		entries.clear();
		clearCaches();
		visitChildren(UnmanagedRegistry::invalidate);
		clearUndo();
	}
	private void clearUndo(){
		active = false;
		aliases.clear();
		allocated.clear();
		born.clear();
		undo.clear();
		destroyed.clear();
		stateUndo.clear();
	}
	
	public void rollback() throws IOException{
		if(source instanceof Blob blob && (blob.isFreed() || blob.getIOSize()<=MagicID.size())){
			invalidate();
			return;
		}
		restoring = true;
		try{
			drain();
			
			for(var restore : stateUndo.values()){
				restore.run();
			}
			
			for(var entry : born){
				entries.remove(entry.pointer, entry);
				var instance = entry.get();
				if(instance != null){
					instance.detachIdentity();
				}
			}
			
			for(var entry : undo.keySet()){
				entries.remove(entry.pointer, entry);
			}
			
			for(var change : undo.entrySet()){
				var entry    = change.getKey();
				var instance = entry.get();
				if(instance == null || born.contains(entry)){
					continue;
				}
				
				entry.pointer = change.getValue().getPtr();
				instance.restoreIdentity(change.getValue());
				
				entries.put(entry.pointer, entry);
			}
			
			var liveProviders = new ArrayList<DataProvider>();
			for(var ref : providers){
				var provider = ref.get();
				if(provider != null){
					liveProviders.add(provider);
				}
			}
			
			if(liveProviders.isEmpty()){
				rollbackChildren();
				return;
			}
			
			for(var p : liveProviders){
				var removed = destroyed.values().stream().filter(ch -> ch.getDataProvider().getChunkCache() == p.getChunkCache()).toList();
				p.getChunkCache().refreshAfterRollback(epoch, removed);
			}
			
			for(var p : liveProviders){
				p.refreshAfterRollback();
			}
			
			clearCaches();
			
			var live = new ArrayList<IOInstance.Unmanaged<?>>();
			for(var it = entries.values().iterator(); it.hasNext(); ){
				var entry    = it.next();
				var instance = entry.get();
				if(instance != null && entry.ready){
					live.add(instance);
				}else{
					if(instance != null){
						instance.detachIdentity();
					}
					it.remove();
				}
			}
			
			for(var instance : live){
				instance.refreshState();
			}
			for(var p : liveProviders){
				p.refreshAfterRollback();
			}
			
			clearCaches();
			rollbackChildren();
		}finally{
			restoring = false;
			clearUndo();
		}
	}
	
	private void rollbackChildren() throws IOException{
		for(var iterator = children.iterator(); iterator.hasNext(); ){
			var child = iterator.next().get();
			if(child == null){
				iterator.remove();
			}else{
				child.rollback();
			}
		}
	}
	private void clearCaches(){
		caches.removeIf(cache -> {
			var owner = cache.get();
			if(owner == null){
				return true;
			}
			cache.clear.accept(owner);
			return false;
		});
	}
}
