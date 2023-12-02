package com.lapissea.dfs.core;

import com.lapissea.dfs.MagicID;
import com.lapissea.dfs.Utils;
import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.config.GlobalConfig;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.ChunkCache;
import com.lapissea.dfs.core.memory.PersistentMemoryManager;
import com.lapissea.dfs.core.versioning.ClassVersionDiff;
import com.lapissea.dfs.core.versioning.Versioning;
import com.lapissea.dfs.exceptions.IncompatibleVersionTransform;
import com.lapissea.dfs.exceptions.MalformedPointer;
import com.lapissea.dfs.exceptions.MalformedStruct;
import com.lapissea.dfs.internal.Runner;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.io.instancepipe.FixedStructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.ObjectID;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.objects.collections.AbstractUnmanagedIOMap;
import com.lapissea.dfs.objects.collections.HashIOMap;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.objects.collections.IOMap;
import com.lapissea.dfs.type.FieldWalker;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOTypeDB;
import com.lapissea.dfs.type.MemoryWalker;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.compilation.FieldCompiler;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.IterablePP;
import com.lapissea.util.LateInit;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.DEFAULT_IF_NULL;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NULLABLE;
import static com.lapissea.dfs.type.field.annotations.IOValue.Reference.PipeType.FLEXIBLE;

public final class Cluster implements DataProvider{
	
	private class Roots implements RootProvider{
		private static final int ROOT_PROVIDER_WARMUP_COUNT = ConfigDefs.ROOT_PROVIDER_WARMUP_COUNT.resolveVal();
		
		private static class Node{
			WeakReference<Object> val;
			int                   warmup;
		}
		
		private final Map<ObjectID, Node> cache = ROOT_PROVIDER_WARMUP_COUNT>0? new HashMap<>() : null;
		
		@SuppressWarnings("unchecked")
		private <T> T requestCached(ObjectID id, UnsafeSupplier<T, IOException> objectGenerator) throws IOException{
			{
				var cached = cache.get(id);
				if(cached != null && cached.val != null){
					Object val = cached.val.get();
					if(val != null) return (T)val;
					else cache.remove(id);
				}
			}
			
			var val = readOrMake(id, (UnsafeSupplier<Object, IOException>)objectGenerator);
			
			var cached = cache.computeIfAbsent(id.clone(), i -> new Node());
			if(cached.warmup<ROOT_PROVIDER_WARMUP_COUNT) cached.warmup++;
			else cached.val = new WeakReference<>(val);
			return (T)val;
		}
		
		private Object readOrMake(ObjectID id, UnsafeSupplier<Object, IOException> objectGenerator) throws IOException{
			return metadata.rootObjects.computeIfAbsent(id, objectGenerator);
		}
		
		@SuppressWarnings("unchecked")
		@Override
		public <T> T request(ObjectID id, UnsafeSupplier<T, IOException> objectGenerator) throws IOException{
			Objects.requireNonNull(id);
			Objects.requireNonNull(objectGenerator);
			
			if(ROOT_PROVIDER_WARMUP_COUNT>0){
				return requestCached(id, objectGenerator);
			}
			
			return (T)readOrMake(id, (UnsafeSupplier<Object, IOException>)objectGenerator);
		}
		
		@Override
		public <T> void provide(ObjectID id, T obj) throws IOException{
			Objects.requireNonNull(obj);
			metadata.rootObjects.put(id, obj);
		}
		
		@Override
		public DataProvider getDataProvider(){
			return Cluster.this;
		}
		
		@Override
		public IterablePP<IOMap.IOEntry<ObjectID, Object>> listAll(){
			return () -> metadata.rootObjects.iterator();
		}
		
		@Override
		public void drop(ObjectID id) throws IOException{
			metadata.rootObjects.remove(id);
		}
	}
	
	private static class RootRef extends IOInstance.Managed<RootRef>{
		@IOValue
		@IOValue.Reference(dataPipeType = FLEXIBLE)
		@IONullability(DEFAULT_IF_NULL)
		private Metadata metadata;
	}
	
	@IOInstance.Def.ToString(name = false, curly = false, fNames = false)
	private interface IOChunkPointer extends IOInstance.Def<IOChunkPointer>{
		ChunkPointer getVal();
	}
	
	@IOValue
	@IONullability(NULLABLE)
	@SuppressWarnings("unused")
	private static class Metadata extends IOInstance.Managed<Metadata>{
		
		@IOValue.OverrideType(value = HashIOMap.class)
		private AbstractUnmanagedIOMap<ObjectID, Object> rootObjects;
		
		private IOTypeDB.PersistentDB db;
		
		private IOList<IOChunkPointer> freeChunks;
		
		@Override
		public String toString(){
			return this.getClass().getSimpleName() + toShortString();
		}
		@Override
		public String toShortString(){
			return "{db: " + db.toShortString() + ", " +
			       freeChunks.size() + " freeChunks, " +
			       "rootObjects: " + rootObjects.stream().map(e -> e.getKey().toString())
			                                    .collect(Collectors.joining(", ", "[", "]"))
			       + "}";
		}
	}
	
	
	static{
		Thread.startVirtualThread(IOTypeDB.PersistentDB::new);
		Thread.startVirtualThread(FieldCompiler::init);
	}
	
	private static final ChunkPointer             FIRST_CHUNK_PTR = ChunkPointer.of(MagicID.size());
	private static final FixedStructPipe<RootRef> ROOT_PIPE       = FixedStructPipe.of(RootRef.class);
	
	private static void initEmptyClusterSnapshot(IOInterface data) throws IOException{
		var provider = DataProvider.newVerySimpleProvider(data);
		var db       = new IOTypeDB.PersistentDB();
		
		try(var io = data.write(true)){
			MagicID.write(io);
		}
		
		var firstChunk = AllocateTicket.withData(ROOT_PIPE, provider, new RootRef())
		                               .withApproval(c -> c.getPtr().equals(FIRST_CHUNK_PTR))
		                               .submit(provider);
		
		db.init(provider);
		
		ROOT_PIPE.modify(firstChunk, root -> {
			root.metadata.db = db;
			root.metadata.allocateNulls(provider, null);
		}, null);
	}
	
	private static WeakReference<ByteBuffer> EMPTY_CLUSTER_SNAP = new WeakReference<>(null);
	private static ByteBuffer getEmptyClusterSnapshot() throws IOException{
		var emptyClusterSnap = EMPTY_CLUSTER_SNAP.get();
		if(emptyClusterSnap == null){
			var mem = MemoryData.builder().withCapacity(MagicID.size()).build();
			initEmptyClusterSnapshot(mem);
			emptyClusterSnap = ByteBuffer.wrap(mem.readAll()).asReadOnlyBuffer();
			
			EMPTY_CLUSTER_SNAP = new WeakReference<>(emptyClusterSnap);
		}
		return emptyClusterSnap;
	}
	
	
	public static Cluster init(IOInterface data) throws IOException{
		data.write(true, getEmptyClusterSnapshot());
		return new Cluster(data);
	}
	
	public static Cluster emptyMem() throws IOException{
		return Cluster.init(MemoryData.builder().withCapacity(getEmptyClusterSnapshot().limit()).build());
	}
	
	
	private final ChunkCache chunkCache = ChunkCache.strong();
	
	private final IOInterface   source;
	private final MemoryManager memoryManager;
	
	private final RootRef  root;
	private final Metadata metadata;
	
	private final RootProvider roots = new Roots();
	
	private final Versioning versioning;
	
	public Cluster(IOInterface source) throws IOException{ this(source, Versioning.JUST_FAIL); }
	public Cluster(IOInterface source, Versioning versioning) throws IOException{
		this.source = source;
		this.versioning = versioning;
		source.read(MagicID::read);
		
		Chunk ch = getFirstChunk();
		
		var s = ROOT_PIPE.getFixedDescriptor().get(WordSpace.BYTE);
		if(s>ch.getSize()){
			throw new IOException("no valid cluster data " + s + " " + ch.getSize());
		}
		
		root = ROOT_PIPE.readNew(this, ch, null);
		metadata = root.metadata;
		
		memoryManager = new PersistentMemoryManager(
			this,
			metadata.freeChunks
				.mappedView(ChunkPointer.class, IOChunkPointer::getVal, IOInstance.Def.constrRef(IOChunkPointer.class, ChunkPointer.class))
				.cachedView(128)
		);
		
		if(GlobalConfig.TYPE_VALIDATION){
			scanTypeDB();
		}
		
		versionForward();
	}
	
	private void versionForward() throws IOException{
		var db               = metadata.db;
		var storedClassNames = db.listStoredTypeDefinitionNames();
		if(storedClassNames.isEmpty()) return;
		
		var transformers =
			storedClassNames
				.stream()
				.map(className -> Runner.async(() -> ClassVersionDiff.of(className, db)))
				.toList()
				.stream()
				.map(LateInit::get)
				.flatMap(Optional::stream)
				.map(versioning::createTransformer)
				.toList();
		
		if(transformers.isEmpty()) return;
		
		if(isReadOnly()){
			throw new IncompatibleVersionTransform("Can not update read only database");
		}
		
		var nameSet = transformers.stream().map(e -> e.matchingClassName).collect(Collectors.toSet());
		
		var index = 0;
		while(true){
			var mod = "€old" + (index == 0? "" : index);
			if(nameSet.stream().anyMatch(name -> storedClassNames.contains(name + mod))){
				index++;
				continue;
			}
			break;
		}
		var mod = "€old" + (index == 0? "" : index);
		db.rename(nameSet, name -> name + mod);
		
		var oldSet = transformers.stream().collect(Collectors.toMap(e -> e.matchingClassName + mod, Function.identity()));
		
		LogUtil.println("Replacing:\n" + oldSet.keySet() + " with:\n" + nameSet);
		
		LogUtil.println("================ WALKING ================");
		FieldWalker.walk(this, root, new FieldWalker.FieldRecord(){
			@Override
			public <I extends IOInstance<I>> int log(I instance, IOField<I, ?> field){
				Object   val  = null;
				Class<?> type = field.getType();
				
				var dyn = field.typeFlag(IOField.DYNAMIC_FLAG);
				if(dyn){
					val = field.get(null, instance);
					if(val != null) type = val.getClass();
				}
				
				var transformer = oldSet.get(type.getName());
				if(transformer != null){
					if(!dyn) val = field.get(null, instance);
					
					var transformed = transformer.apply((IOInstance<?>)val);
					
					LogUtil.println(val, "to", transformed);
					((IOField<I, Object>)field).set(null, instance, transformed);
					return FieldWalker.SAVE|FieldWalker.CONTINUE;
				}
				return FieldWalker.CONTINUE;
			}
		});
		
	}
	
	private void scanTypeDB(){
		var db    = metadata.db;
		var names = db.listStoredTypeDefinitionNames();
		if(names.isEmpty()) return;
		
		var tLoader = db.getTemplateLoader();
		
		List<Struct<?>> structs = new ArrayList<>(names.size());
		for(String name : names){
			if(name.isEmpty()) continue;
			try{
				var def = db.getDefinitionFromClassName(name).orElseThrow();
				if(!def.isIoInstance() || def.isUnmanaged()) continue;
				var clazz = tLoader.loadClass(name);
				if(clazz.getClassLoader() != tLoader){
					continue;
				}
				structs.add(Struct.ofUnknown(clazz));
			}catch(Throwable e){
				throw fail(names, name, e);
			}
		}
		
		for(Struct<?> struct : structs){
			try{
				struct.waitForStateDone();
			}catch(Throwable e){
				throw fail(names, struct.getType().getName(), e);
			}
		}
	}
	
	private static MalformedStruct fail(Set<String> names, String name, Throwable e){
		throw new MalformedStruct(
			"Failed to load type of " + name + "\n" +
			"Stored names:\n" +
			names.stream().map(s -> "\t" + s).collect(Collectors.joining("\n")),
			e
		);
	}
	
	@Override
	public Chunk getFirstChunk() throws IOException{
		try{
			return getChunk(FIRST_CHUNK_PTR);
		}catch(MalformedPointer e){
			throw new IOException("First chunk does not exist", e);
		}
	}
	
	@Override
	public IOTypeDB getTypeDb(){ return metadata == null? null : metadata.db; }
	@Override
	public IOInterface getSource(){ return source; }
	@Override
	public MemoryManager getMemoryManager(){ return memoryManager; }
	@Override
	public ChunkCache getChunkCache(){ return chunkCache; }
	
	public RootProvider roots(){ return roots; }
	
	@Override
	public String toString(){
		var res = new StringJoiner(", ", "Cluster{", "}");
		res.add("source: " + Utils.toShortString(source));
		
		var db    = getTypeDb();
		var defs  = db != null? db.definitionCount() : 0;
		var types = db != null? db.typeLinkCount() : 0;
		if(defs != 0 || types != 0) res.add("db: (" + defs + " defs, " + types + " types)");
		
		var freeChunks = memoryManager != null? memoryManager.getFreeChunks().size() : 0;
		if(freeChunks != 0) res.add("freeChunks: " + freeChunks);
		
		var roots = metadata != null? metadata.rootObjects.size() : 0;
		if(roots != 0) res.add("roots: " + roots);
		
		return res.toString();
	}
	
	
	public record ChunkStatistics(
		long totalBytes,
		long chunkCount,
		double usefulDataRatio,
		double chunkFragmentation,
		double usedChunkEfficiency
	){ }
	
	public ChunkStatistics gatherStatistics() throws IOException{
		
		long totalBytes        = getSource().getIOSize();
		long usedChunkCapacity = 0;
		long usefulBytes       = 0;
		long chunkCount        = 0;
		long hasNextCount      = 0;
		
		Set<ChunkPointer> referenced = new HashSet<>();
		
		rootWalker(MemoryWalker.PointerRecord.of(ref -> {
			if(ref.isNull()) return;
			ref.getPtr().dereference(this).addChainToPtr(referenced);
		}), true).walk();
		
		for(Chunk chunk : getFirstChunk().chunksAhead()){
			if(referenced.contains(chunk.getPtr())){
				usefulBytes += chunk.getSize();
				usedChunkCapacity += chunk.getCapacity();
			}
			chunkCount++;
			if(chunk.hasNextPtr()) hasNextCount++;
		}
		
		return new ChunkStatistics(
			totalBytes,
			chunkCount,
			usefulBytes/(double)totalBytes,
			hasNextCount/(double)chunkCount,
			usefulBytes/(double)usedChunkCapacity
		);
	}
	
	public MemoryWalker rootWalker(MemoryWalker.PointerRecord rec, boolean refRoot) throws IOException{
		return rootWalker(rec, refRoot, false);
	}
	public MemoryWalker rootWalker(MemoryWalker.PointerRecord rec, boolean refRoot, boolean recordStats) throws IOException{
		if(refRoot){
			rec.log(new Reference(), null, null, FIRST_CHUNK_PTR.makeReference());
		}
		return new MemoryWalker(this, root, getFirstChunk().getPtr().makeReference(), ROOT_PIPE, recordStats, rec);
	}
	
	public void defragment() throws IOException{
		new DefragmentManager(this).defragment();
	}
	
	public void scanGarbage(DefragmentManager.FreeFoundAction action) throws IOException{
		new DefragmentManager(this).scanFreeChunks(action);
	}
}
