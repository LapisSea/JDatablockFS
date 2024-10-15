package com.lapissea.dfs.core;

import com.lapissea.dfs.MagicID;
import com.lapissea.dfs.Utils;
import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.config.GlobalConfig;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.core.chunk.ChunkCache;
import com.lapissea.dfs.core.memory.MemoryOperations;
import com.lapissea.dfs.core.memory.PersistentMemoryManager;
import com.lapissea.dfs.exceptions.MalformedClusterData;
import com.lapissea.dfs.exceptions.MalformedPointer;
import com.lapissea.dfs.exceptions.MalformedStruct;
import com.lapissea.dfs.internal.Preload;
import com.lapissea.dfs.io.IOInterface;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.io.instancepipe.FixedStructPipe;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.ObjectID;
import com.lapissea.dfs.objects.Reference;
import com.lapissea.dfs.objects.collections.HashIOMap;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.objects.collections.IOMap;
import com.lapissea.dfs.objects.collections.UnmanagedIOMap;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOTypeDB;
import com.lapissea.dfs.type.MemoryWalker;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.WordSpace;
import com.lapissea.dfs.type.compilation.FieldCompiler;
import com.lapissea.dfs.type.field.IOFieldTools;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.function.UnsafeSupplier;

import java.io.IOException;
import java.io.UncheckedIOException;
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

import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.DEFAULT_IF_NULL;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NULLABLE;
import static com.lapissea.dfs.type.field.annotations.IOValue.Reference.PipeType.FLEXIBLE;

public final class Cluster implements DataProvider{
	
	public enum Version{
		INDEV(0, 1), V1_0;
		
		public static final Version CURRENT = INDEV;
		
		public final short major, minor;
		
		Version(){
			var parts = name().split("\\D+");
			this.major = Short.parseShort(parts[1]);
			this.minor = Short.parseShort(parts[2]);
		}
		Version(int major, int minor){
			this.major = (short)major;
			this.minor = (short)minor;
		}
	}
	
	@IOValue
	private static class VersionTag extends IOInstance.Managed<VersionTag>{
		
		private short major, minor;
		
		public VersionTag(){ }
		public VersionTag(Version version){
			this.major = version.major;
			this.minor = version.minor;
		}
		
		Optional<Version> get(){
			for(var v : Version.values()){
				if(v.minor == minor && v.major == major){
					return Optional.of(v);
				}
			}
			return Optional.empty();
		}
		@Override
		public String toString(){
			return get().map(Enum::toString).orElse("<unknown>");
		}
	}
	
	private class Roots implements RootProvider{
		private static final int ROOT_PROVIDER_WARMUP_COUNT = ConfigDefs.ROOT_PROVIDER_WARMUP_COUNT.resolveValLocking();
		
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
			if(ROOT_PROVIDER_WARMUP_COUNT>0){
				cache.remove(id);
			}
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
	
	@IOValue
	private static class RootRef extends IOInstance.Managed<RootRef>{
		
		private VersionTag version = new VersionTag(Version.CURRENT);
		
		@IOValue.Reference(dataPipeType = FLEXIBLE)
		@IONullability(DEFAULT_IF_NULL)
		@IODependency("version")
		private Metadata metadata;
	}
	
	@IOInstance.StrFormat(name = false, curly = false, fNames = false)
	private interface IOChunkPointer extends IOInstance.Def<IOChunkPointer>{
		ChunkPointer getVal();
	}
	
	@IOValue
	@IONullability(NULLABLE)
	@SuppressWarnings("unused")
	private static class Metadata extends IOInstance.Managed<Metadata>{
		
		@IOValue.OverrideType(value = HashIOMap.class)
		private UnmanagedIOMap<ObjectID, Object> rootObjects;
		
		private IOTypeDB.PersistentDB db;
		
		private IOList<IOChunkPointer> freeChunks;
		
		@Override
		public String toString(){
			return this.getClass().getSimpleName() + toShortString();
		}
		@Override
		public String toShortString(){
			if(db == null) return IOFieldTools.UNINITIALIZED_FIELD_SIGN;
			return "{db: " + db.toShortString() + ", " +
			       freeChunks.size() + " freeChunks, " +
			       "rootObjects: " + Iters.from(rootObjects).joinAsStr(", ", "[", "]", e -> e.getKey().toString())
			       + "}";
		}
	}
	
	
	static{
//		Preload.preloadPipe(IOTypeDB.PersistentDB.class, StandardStructPipe.class);
		Preload.preload(IOTypeDB.PersistentDB.class);
		Preload.preload(FieldCompiler.class);
		Preload.preload(Reference.class);
	}
	
	private static ChunkPointer             FIRST_CHUNK_PTR;
	private static FixedStructPipe<RootRef> ROOT_PIPE;
	
	private static FixedStructPipe<RootRef> rootPipe(){
		if(ROOT_PIPE == null) ROOT_PIPE = FixedStructPipe.of(RootRef.class);
		return ROOT_PIPE;
	}
	private static ChunkPointer firstChunkPtr(){
		if(FIRST_CHUNK_PTR == null) FIRST_CHUNK_PTR = ChunkPointer.of(MagicID.size());
		return FIRST_CHUNK_PTR;
	}
	
	private static void initEmptyClusterSnapshot(IOInterface data) throws IOException{
		var provider = DataProvider.newVerySimpleProvider(data);
		var db       = new IOTypeDB.PersistentDB();
		
		try(var io = data.write(true)){
			MagicID.write(io);
		}
		
		var rrSiz = 16 + 4;
		
		var firstChunk = MemoryOperations.allocateAppendToFile(provider, AllocateTicket.bytes(rrSiz), false);
		if(!firstChunk.getPtr().equals(firstChunkPtr())) throw new ShouldNeverHappenError();
		
		db.init(provider);
		
		var ref = new RootRef();
		ref.metadata = new Metadata();
		ref.metadata.db = db;
		ref.metadata.allocateNulls(provider, null);
		
		rootPipe().write(firstChunk, ref);
		
		assert firstChunk.chainSize() == rrSiz : firstChunk.chainSize() + " != " + rrSiz;
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
	
	public static Cluster emptyMem(){
		try{
			return Cluster.init(MemoryData.builder().withCapacity(getEmptyClusterSnapshot().limit()).build());
		}catch(IOException e){
			throw new UncheckedIOException(e);
		}
	}
	
	
	private final ChunkCache chunkCache = ChunkCache.strong();
	
	private final IOInterface   source;
	private final MemoryManager memoryManager;
	
	private final RootRef  root;
	private final Metadata metadata;
	
	private final RootProvider roots = new Roots();
	
	public Cluster(IOInterface source) throws IOException{
		this.source = source;
		source.read(MagicID::read);
		
		Chunk ch = getFirstChunk();
		var   rp = rootPipe();
		
		var version = ch.ioMap(io -> rp.readNewSelective(this, io, Set.of("version"), null).version);
		handleVersion(version);
		
		
		var s = rp.getFixedDescriptor().get(WordSpace.BYTE);
		if(s>ch.getSize()){
			throw new IOException("no valid cluster data " + s + " " + ch.getSize());
		}
		
		root = rp.readNew(this, ch, null);
		metadata = root.metadata;
		
		memoryManager = new PersistentMemoryManager(
			this,
			metadata.freeChunks
				.mappedView(ChunkPointer.class, IOChunkPointer::getVal, IOInstance.Def.constrRef(IOChunkPointer.class, ChunkPointer.class))
				.cachedView(128, 128)
		);
		
		if(GlobalConfig.TYPE_VALIDATION){
			scanTypeDB();
		}
	}
	
	private void handleVersion(VersionTag ver) throws MalformedClusterData{
		var v = ver.get();
		if(v.isEmpty()){
			throw new MalformedClusterData("No known version found! Listed version number: " + ver.major + "." + ver.minor);
		}
		var version = v.get();
		if(version != Version.CURRENT){
			throw new NotImplementedException("Cluster version update not implemented yet! " +
			                                  "Listed version is " + version + " but current is " + Version.CURRENT);
		}
	}
	
	private void scanTypeDB() throws MalformedClusterData{
		var db = metadata.db;
		
		Set<String> names;
		try{
			names = db.listStoredTypeDefinitionNames();
		}catch(Throwable e){
			throw new MalformedClusterData("Failed to read list of stored types ", e);
		}
		if(names.isEmpty()) return;
		
		var tLoader = db.getTemplateLoader();
		
		List<Struct<?>> structs = new ArrayList<>(names.size());
		for(String name : names){
			if(name.isEmpty()) continue;
			try{
				var def = db.getDefinitionFromClassName(name).orElseThrow();
				if(!def.ioInstance || def.unmanaged) continue;
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
			Iters.from(names).joinAsStr("\n", s -> "\t" + s),
			e
		);
	}
	
	@Override
	public Chunk getFirstChunk() throws IOException{
		try{
			return getChunk(firstChunkPtr());
		}catch(MalformedPointer e){
			throw new IOException("First chunk does not exist", e);
		}
	}
	
	@Override
	public IOTypeDB.PersistentDB getTypeDb(){ return metadata == null? null : metadata.db; }
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
			rec.log(Reference.NULL, null, null, firstChunkPtr().makeReference(), null);
		}
		return new MemoryWalker(this, root, getFirstChunk().getPtr().makeReference(), rootPipe(), recordStats, rec);
	}
	
	public void defragment() throws IOException{
		new DefragmentManager(this).defragment();
	}
	
	public void scanGarbage(DefragmentManager.FreeFoundAction action) throws IOException{
		new DefragmentManager(this).scanFreeChunks(action);
	}
}
