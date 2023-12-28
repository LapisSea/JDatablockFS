package com.lapissea.dfs.objects.collections;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.exceptions.InvalidGenericArgument;
import com.lapissea.dfs.internal.HashCommons;
import com.lapissea.dfs.io.IOTransaction;
import com.lapissea.dfs.io.RandomIO;
import com.lapissea.dfs.io.ValueStorage;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.dfs.utils.IterablePP;
import com.lapissea.util.LogUtil;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NULLABLE;

public class HashIOMap<K, V> extends AbstractUnmanagedIOMap<K, V>{
	
	@SuppressWarnings({"unchecked"})
	@Def.ToString.Format("[!!className]{@key: @value}")
	@Def.Order({"key", "value"})
	private interface BucketEntry<K, V> extends IOInstance.Def<BucketEntry<K, V>>{
		
		Struct<BucketEntry<Object, Object>>     STRUCT = Struct.of((Class<BucketEntry<Object, Object>>)(Object)BucketEntry.class);
		StructPipe<BucketEntry<Object, Object>> PIPE   = StandardStructPipe.of(STRUCT);
		
		static <K, V> BucketEntry<K, V> of(){
			return (BucketEntry<K, V>)STRUCT.make();
		}
		
		static <K, V> BucketEntry<K, V> of(K key, V value){
			//noinspection rawtypes
			class Val{
				private static final BiFunction<Object, Object, BucketEntry> NEW =
					IOInstance.Def.constrRef(BucketEntry.class, Object.class, Object.class);
			}
			return Val.NEW.apply(key, value);
		}
		
		@IONullability(NULLABLE)
		@IOValue.Generic
		K key();
		
		@IONullability(NULLABLE)
		@IOValue.Generic
		V value();
		void value(V value);
		
		default IOEntry.Modifiable<K, V> unsupported(){
			return new IOEntry.Modifiable.Unsupported<>(key(), value());
		}
		
		default IOEntry<K, V> unmodifiable(){
			return IOEntry.of(key(), value());
		}
	}
	
	private static class Bucket<K, V> extends IOInstance.Managed<Bucket<K, V>> implements Iterable<BucketEntry<K, V>>{
		@IOValue
		@IONullability(NULLABLE)
		private IONode<BucketEntry<K, V>> node;
		
		public BucketEntry<K, V> entry(K key) throws IOException{
			if(node == null) return null;
			for(IONode<BucketEntry<K, V>> n : node){
				var entry = n.getValue();
				if(entry != null && Objects.equals(entry.key(), key)){
					return entry;
				}
			}
			
			return null;
		}
		
		public void put(BucketEntry<K, V> entry) throws IOException{
			for(var node : node){
				var e = node.getValue();
				if(e != null && Objects.equals(e.key(), entry.key())){
					node.setValue(entry);
					return;
				}
			}
			throw new RuntimeException("bucket entry not found");
		}
		
		private Stream<IONode<BucketEntry<K, V>>> nodeStream(){
			if(node == null) return Stream.of();
			return node.stream();
		}
		public BucketEntry<K, V> getEntryByKey(K key) throws IOException{
			if(node == null) return null;
			for(var entry : node){
				var k = readKey(entry);
				if(!k.hasValue) continue;
				if(Objects.equals(k.key, key)){
					return entry.getValue();
				}
			}
			
			return null;
		}
		
		@Override
		public Iterator<BucketEntry<K, V>> iterator(){
			if(node == null) return Collections.emptyIterator();
			return node.valueIterator();
		}
		
		@Override
		public String toString(){
			return "Bucket{" + node + "}";
		}
	}
	
	private static final class SmallHashes implements Iterable<Integer>{
		private static final class Iter implements Iterator<Integer>{
			
			private final short bucketPO2;
			private       int   hash;
			private       int   itersLeft = HashCommons.HASH_GENERATIONS;
			
			private Iter(short bucketPO2, int hash){
				this.bucketPO2 = bucketPO2;
				this.hash = hash;
			}
			
			@Override
			public boolean hasNext(){
				return itersLeft>0;
			}
			@Override
			public Integer next(){
				itersLeft--;
				
				var hash     = this.hash;
				var hashNext = HashCommons.h2h(hash);
				
				var smallHash     = hashToSmall(hash, bucketPO2);
				var smallHashNext = hashToSmall(hashNext, bucketPO2);
				
				if(smallHash == smallHashNext) hashNext++;
				
				this.hash = hashNext;
				
				return smallHash;
			}
		}
		
		private final short bucketPO2;
		private final int   hash;
		
		private SmallHashes(short bucketPO2, Object key){
			this(bucketPO2, HashCommons.toHash(key));
		}
		private SmallHashes(short bucketPO2, int hash){
			this.bucketPO2 = bucketPO2;
			this.hash = hash;
		}
		
		@Override
		public Iterator<Integer> iterator(){ return new Iter(bucketPO2, hash); }
	}
	
	@IOValue
	@IOValue.Unsigned
	private short bucketPO2 = 1;
	
	@IOValue
	private IOList<Bucket<K, V>> buckets;
	
	private int datasetID;
	
	private final Map<Integer, BucketContainer<K, V>> hotBuckets = new ConcurrentHashMap<>();
	
	public HashIOMap(DataProvider provider, Chunk identity, IOType typeDef) throws IOException{
		super(provider, identity, typeDef);
		
		if(!readOnly && isSelfDataEmpty()){
			newBuckets();
			writeManagedFields();
			fillBuckets(buckets, bucketPO2);
		}
		readManagedFields();
	}
	
	private void fillBuckets(IOList<Bucket<K, V>> buckets, short bucketPO2) throws IOException{
		var siz = 1L<<bucketPO2;
		buckets.addMultipleNew(siz - buckets.size());
	}
	
	@SuppressWarnings("unchecked")
	private void newBuckets() throws IOException{
		getThisStruct().getFields()
		               .requireExactFieldType(RefField.class, "buckets")
		               .allocateUnmanaged(this);
	}
	
	private void reflow() throws IOException{
		var oldBuckets   = buckets;
		var oldBucketPO2 = bucketPO2;
		
		bucketPO2 = (short)(oldBucketPO2 + 1);//calcNewSize(oldBuckets, oldBucketPO2);
		datasetID++;
		
		if(bucketPO2<0) throw new IllegalStateException();
		
		hotBuckets.clear();
		newBuckets();
		fillBuckets(buckets, bucketPO2);
		
		if(size()<512){
//			Log.traceCall("method: rewire, size: {}", size());
			try(var ignored = getDataProvider().getSource().openIOTransaction()){
				transferRewire(oldBuckets, buckets, bucketPO2);
				writeManagedFields();
			}
			disownedFree(oldBuckets);
		}else{
			long pos = 0;
//			Log.traceCall("method: reallocate and transfer, size: {}", size());
			
			while(pos<oldBuckets.size()){
				long from = pos;
				long to   = Math.min(oldBuckets.size(), from + 256);
				pos = to;
				
				var view = oldBuckets.subListView(from, to);
				
				optimizedOrderTransfer(view, buckets, bucketPO2);
			}
			
			var nCount = rawNodeStreamWithValues(buckets).count();
			if(nCount != size()){
				var oldKeys = rawKeyStream(oldBuckets).collect(Collectors.toSet());
				var newKeys = rawKeyStream(buckets).collect(Collectors.toSet());
				if(oldKeys.size()>newKeys.size()){
					oldKeys.removeAll(newKeys);
					throw new IllegalStateException("Failed to transfer keys: " + TextUtil.toShortString(oldKeys));
				}
				LogUtil.println(oldKeys.size(), newKeys.size());
				throw new IllegalStateException(nCount + " " + size());
			}
			
			writeManagedFields();
			disownedFree(oldBuckets);
		}
	}
	
	private void disownedFree(IOList<Bucket<K, V>> oldBuckets) throws IOException{
		//TODO: properly handle memory ownership
		try(var ignored = getDataProvider().getSource().openIOTransaction()){
			for(long i = 0; i<oldBuckets.size(); i++){
				oldBuckets.set(i, new Bucket<>());
			}
		}
		((Unmanaged<?>)oldBuckets).free();
	}
	
	private void optimizedOrderTransfer(IOList<Bucket<K, V>> oldData, IOList<Bucket<K, V>> newBuckets, short newPO2) throws IOException{
		
		var sortedNodes = new HashMap<Integer, List<IONode<BucketEntry<K, V>>>>();
		
		for(var bucket : oldData){
			if(bucket.node == null) continue;
			nood:
			for(var n : bucket.node){
				var kr = readKey(n);
				if(!kr.hasValue) continue;
				var key = kr.key;
				
				for(int smallHash : new SmallHashes(newPO2, key)){
					if(!sortedNodes.containsKey(smallHash) && newBuckets.get(smallHash).node == null){
						sortedNodes.computeIfAbsent(smallHash, k -> new ArrayList<>(1)).add(n);
						continue nood;
					}
				}
				
				sortedNodes.computeIfAbsent(hashToSmall(HashCommons.toHash(key), newPO2), k -> new ArrayList<>(1)).add(n);
			}
		}
		
		for(var e : sortedNodes.entrySet()){
			var noods     = e.getValue();
			int smallHash = e.getKey();
			
			Bucket<K, V>              bucket      = newBuckets.get(smallHash);
			IONode<BucketEntry<K, V>> last        = bucket.nodeStream().reduce((a, b) -> b).orElse(null);
			IOTransaction             transaction = null;
			
			if(last != null || noods.size()>1){
				transaction = getDataProvider().getSource().openIOTransaction();
			}
			try{
				for(var nood : noods){
					var node = allocNewNode(nood::getValueDataIO, (Unmanaged<?>)newBuckets);
					
					if(last == null){
						bucket.node = node;
						setBucket(newBuckets, smallHash, bucket);
					}else{
						last.setNext(node);
					}
					last = node;
				}
			}finally{
				if(transaction != null) transaction.close();
			}
		}
	}
	
	private static IOField<BucketEntry<Object, Object>, ?> keyVar;
	
	private record KeyResult<K>(K key, boolean hasValue){ }
	private static <K, V> KeyResult<K> readKey(IONode<BucketEntry<K, V>> n) throws IOException{
		if(keyVar == null){
			//noinspection unchecked
			keyVar = Struct.of((Class<BucketEntry<Object, Object>>)(Object)BucketEntry.class, Struct.STATE_DONE)
			               .getFields()
			               .requireByName("key");
		}
		
		var be = BucketEntry.<K, V>of();
		if(!n.readValueField(be, keyVar)) return new KeyResult<>(null, false);
		return new KeyResult<>(be.key(), true);
	}
	
	private void transferRewire(IOList<Bucket<K, V>> oldBuckets, IOList<Bucket<K, V>> newBuckets, short newPO2) throws IOException{
		for(Bucket<K, V> oldBucket : oldBuckets){
			var oldNodes = oldBucket.nodeStream().toList();
			for(var node : oldNodes){
				if(node.hasNext()) node.setNext(null);
				
				if(!node.hasValue()){
					node.free();
					continue;
				}
				
				var smallHash = hashToSmall(HashCommons.toHash(readKey(node).key), newPO2);
				
				Bucket<K, V> bucket = getBucket(null, newBuckets, smallHash);
				if(bucket.node == null){
					bucket.node = node;
					setBucket(newBuckets, smallHash, bucket);
					continue;
				}
				
				var last = bucket.node;
				while(true){
					if(!last.hasValue()){
						throw new RuntimeException();
					}
					var next = last.getNext();
					if(next == null) break;
					last = next;
				}
				last.setNext(node);
			}
			
		}
	}
	
	private class ModifiableIOEntry extends IOEntry.Modifiable.Abstract<K, V>{
		
		private       int               currentDatasetID = datasetID;
		private       Bucket<K, V>      currentBucket;
		private final BucketEntry<K, V> data;
		
		public ModifiableIOEntry(Bucket<K, V> bucket, BucketEntry<K, V> entry){
			currentBucket = bucket;
			this.data = entry;
		}
		
		@Override
		public K getKey(){ return data.key(); }
		@Override
		public V getValue(){ return data.value(); }
		
		private void fastSet(V value) throws IOException{
			data.value(value);
			currentBucket.put(data);
		}
		private void mapSet(V value) throws IOException{
			data.value(value);
			HashIOMap.this.put(getKey(), value);
		}
		
		@Override
		public void set(V value) throws IOException{
			if(datasetID != currentDatasetID){
				if(tryUpdateData()){
					fastSet(value);
				}else mapSet(value);
			}else fastSet(value);
		}
		
		private boolean tryUpdateData() throws IOException{
			Bucket<K, V> bucket = getBucket(hotBuckets, buckets, hashToSmall(HashCommons.toHash(getKey()), bucketPO2));
			if(bucket == null) return false;
			
			currentBucket = bucket;
			currentDatasetID = datasetID;
			return true;
		}
	}
	
	@Override
	public IOEntry.Modifiable<K, V> getEntry(K key) throws IOException{
		for(int smallHash : new SmallHashes(bucketPO2, key)){
			var bucket = getBucket(hotBuckets, buckets, smallHash);
			
			if(bucket == null) continue;
			
			var entry = bucket.entry(key);
			if(entry == null) continue;
			
			return new ModifiableIOEntry(bucket, entry);
		}
		return null;
	}
	
	@Override
	public Iterator<IOEntry<K, V>> iterator(){
		return new IOIterator.Iter<>(){
			private IOIterator<IONode<BucketEntry<K, V>>> bucket;
			private final IOIterator<Bucket<K, V>> iter = buckets.iterator();
			
			private boolean has() throws IOException{
				return bucket != null && bucket.hasNext();
			}
			
			private void ensure() throws IOException{
				if(has()) return;
				while(iter.hasNext()){
					var next = iter.ioNext();
					if(next.node != null){
						bucket = next.node.iterator();
						return;
					}
				}
			}
			
			@Override
			public boolean hasNext(){
				try{
					ensure();
					return has();
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
			
			@Override
			public IOEntry<K, V> ioNext() throws IOException{
				ensure();
				var node  = bucket.ioNext();
				var value = node.getValue();
				return value.unmodifiable();
			}
		};
	}
	
	@Override
	public Stream<IOEntry<K, V>> stream(){
		return rawEntryStream(buckets).map(BucketEntry::unmodifiable);
	}
	private IterablePP<BucketEntry<K, V>> rawEntries(IOList<Bucket<K, V>> buckets){
		return () -> rawEntryStream(buckets).iterator();
	}
	private Stream<BucketEntry<K, V>> rawEntryStream(IOList<Bucket<K, V>> buckets){
		return rawNodeStreamWithValues(buckets).map(e -> {
			try{
				return e.getValue();
			}catch(IOException ex){
				throw UtilL.uncheckedThrow(ex);
			}
		});
	}
	
	private Stream<K> rawKeyStream(IOList<Bucket<K, V>> buckets){
		return rawNodeStream(buckets)
			       .map(e -> {
				       try{
					       return readKey(e);
				       }catch(IOException ex){
					       throw UtilL.uncheckedThrow(ex);
				       }
			       })
			       .filter(KeyResult::hasValue)
			       .map(KeyResult::key);
	}
	
	private Stream<IONode<BucketEntry<K, V>>> rawNodeStream(IOList<Bucket<K, V>> buckets){
		return buckets.stream().flatMap(Bucket::nodeStream);
	}
	
	private Stream<IONode<BucketEntry<K, V>>> rawNodeStreamWithValues(IOList<Bucket<K, V>> buckets){
		return rawNodeStream(buckets).filter(e -> {
			try{
				return e.hasValue();
			}catch(IOException ex){
				throw UtilL.uncheckedThrow(ex);
			}
		});
	}
	
	@Override
	public void put(K key, V value) throws IOException{
		if(DEBUG_VALIDATION) checkValue(value);
		var sizeFlag = putEntry(hotBuckets, buckets, bucketPO2, key, value);
		if(sizeFlag.inc == 0) return;
		
		deltaSize(sizeFlag.inc);
		if(size()>=buckets.size()){
			reflow();
		}
	}
	
	private void checkValue(V value){
		if(!(value instanceof IOInstance.Unmanaged)){
			try{
				var d    = MemoryData.empty();
				var link = IOType.of(value.getClass());
				var vCtx = getGenerics().argAsContext("V");
				var v    = ValueStorage.makeStorage(DataProvider.newVerySimpleProvider(d), link, vCtx, new ValueStorage.StorageRule.Default());
				//noinspection unchecked
				((ValueStorage<V>)v).write(d.io(), value);
			}catch(InvalidGenericArgument e){
				Log.smallTrace("Ignored illegal generic argument for {}", value);
			}catch(Throwable e){
				String valStr;
				try{
					valStr = value.toString();
				}catch(Throwable e1){
					valStr = "<" + value.getClass().getSimpleName() + " failed toString: " + e1.getMessage() + ">";
				}
				throw new IllegalArgumentException(valStr + " is not a valid value!", e);
			}
		}
	}
	
	@Override
	public void putAll(Map<K, V> values) throws IOException{
		if(values.isEmpty()) return;
		
		if(DEBUG_VALIDATION){
			for(V value : values.values()){
				checkValue(value);
			}
		}
		
		while(size()>=(buckets.size() + values.size())){
			reflow();
		}
		
		Map<Integer, List<Map.Entry<K, V>>> sorted = new HashMap<>();
		
		for(Map.Entry<K, V> kvEntry : values.entrySet()){
			sorted.computeIfAbsent(hashToSmall(HashCommons.toHash(kvEntry.getKey()), bucketPO2), i -> new ArrayList<>()).add(kvEntry);
		}
		
		try(var ignored = getDataProvider().getSource().openIOTransaction()){
			long deltaSize = 0;
			for(var group : sorted.values()){
				for(Map.Entry<K, V> e : group){
					var key   = e.getKey();
					var value = e.getValue();
					
					var action = putEntry(hotBuckets, buckets, bucketPO2, key, value);
					deltaSize += action.inc;
				}
			}
			
			deltaSize(deltaSize);
		}
	}
	
	@Override
	public boolean remove(K key) throws IOException{
		for(int smallHash : new SmallHashes(bucketPO2, key)){
			var bucket = getBucket(hotBuckets, buckets, smallHash);
			if(bucket.node == null) continue;
			
			var prevNode = bucket.node;
			for(var node : bucket.node){
				var keyResult = readKey(node);
				if(!keyResult.hasValue) continue;
				if(Objects.equals(keyResult.key, key)){
					if(prevNode == node){
						bucket.node = node.getNext();
						setBucket(buckets, smallHash, bucket);
					}else{
						prevNode.setNext(node.getNext());
					}
					node.setNext(null);
					node.free();
					deltaSize(-1);
					return true;
				}
				prevNode = node;
			}
		}
		return false;
	}
	
	@Override
	public void clear() throws IOException{
		datasetID++;
		hotBuckets.clear();
		try(var ignored = getDataProvider().getSource().openIOTransaction()){
			size = 0;
			bucketPO2 = 1;
			buckets.clear();
			fillBuckets(buckets, bucketPO2);
			writeManagedFields();
		}
	}
	
	private enum PutAction{
		OVERWRITE(0),
		OVERWRITE_EMPTY(1),
		BUCKET_APPEND(1);
		
		final int inc;
		PutAction(int inc){ this.inc = inc; }
	}
	
	private PutAction putEntry(Map<Integer, BucketContainer<K, V>> hotBuckets, IOList<Bucket<K, V>> buckets, short bucketPO2, K key, V value) throws IOException{
		
		Bucket<K, V> firstBucket = null;
		
		var newEntry = BucketEntry.of(key, value);
		
		var orgHash = HashCommons.toHash(key);
		
		for(int smallHash : new SmallHashes(bucketPO2, orgHash)){
			var bucket = getBucket(hotBuckets, buckets, smallHash);
			if(firstBucket == null) firstBucket = bucket;
			
			var entry = bucket.getEntryByKey(key);
			if(entry != null){
				entry.value(value);
				bucket.put(entry);
				return PutAction.OVERWRITE;
			}
		}
		assert firstBucket != null;
		
		for(int smallHash : new SmallHashes(bucketPO2, orgHash)){
			var bucket = getBucket(hotBuckets, buckets, smallHash);
			
			if(bucket.node == null){
				bucket.node = allocNewNode(newEntry, ((Unmanaged<?>)buckets).getPointer());
				setBucket(buckets, smallHash, bucket);
				return PutAction.OVERWRITE_EMPTY;
			}
		}
		
		IONode<BucketEntry<K, V>> last = firstBucket.node;
		for(var node : firstBucket.node){
			if(!node.hasValue()){
				if(DEBUG_VALIDATION){
					var val = node.getValue();
					if(val != null) throw new RuntimeException(node + " hasValue is not correct");
				}
				
				node.setValue(newEntry);
				return PutAction.OVERWRITE_EMPTY;
			}
			
			last = node;
		}
		
		last.setNext(allocNewNode(newEntry, last.getPointer()));
		
		return PutAction.BUCKET_APPEND;
	}
	
	private IOType buckedNodeType(){
		return IOType.of(
			IONode.class,
			((IOType.RawAndArg)getTypeDef()).withRaw(BucketEntry.class)
		);
	}
	
	@SuppressWarnings("unchecked")
	private IONode<BucketEntry<K, V>> allocNewNode(BucketEntry<K, V> newEntry, ChunkPointer magnet) throws IOException{
		return IONode.allocValNode(
			newEntry,
			null,
			(SizeDescriptor<BucketEntry<K, V>>)(Object)BucketEntry.PIPE.getSizeDescriptor(),
			buckedNodeType(),
			getDataProvider(),
			OptionalLong.of(magnet.getValue())
		);
	}
	private IONode<BucketEntry<K, V>> allocNewNode(RandomIO.Creator entryBytes, IOInstance.Unmanaged<?> magnet) throws IOException{
		try(var io = entryBytes.io()){
			return IONode.allocValNode(
				io,
				null,
				buckedNodeType(),
				getDataProvider(),
				OptionalLong.of(magnet.getPointer().getValue())
			);
		}
	}
	
	private static final class BucketContainer<K, V>{
		private final Bucket<K, V> bucket;
		private       byte         age = 1;
		private BucketContainer(Bucket<K, V> bucket){
			this.bucket = bucket;
		}
		private void makeOlder(){
			if(age<10) age++;
		}
	}
	
	private Bucket<K, V> getBucket(Map<Integer, BucketContainer<K, V>> hotBuckets, IOList<Bucket<K, V>> buckets, int smallHash) throws IOException{
		if(hotBuckets != null){
			var container = hotBuckets.get(smallHash);
			if(container != null){
				container.makeOlder();
				return container.bucket;
			}
		}
		var bucket = buckets.get(smallHash);
		
		if(hotBuckets != null){
			if(hotBuckets.size()>16) yeet(hotBuckets);
			hotBuckets.put(smallHash, new BucketContainer<>(bucket));
		}
		
		return bucket;
	}
	
	private static <K, V> void yeet(Map<Integer, BucketContainer<K, V>> hotBuckets){
		var chance = 1F/256;
		var random = new Random();
		hotBuckets.entrySet().removeIf(e -> random.nextFloat()<=chance && (e.getValue().age--)<=0);
	}
	
	private void setBucket(IOList<Bucket<K, V>> buckets, int smallHash, Bucket<K, V> bucket) throws IOException{
		buckets.set(smallHash, bucket);
		if(DEBUG_VALIDATION){
			var read = buckets.get(smallHash);
			if(!read.equals(bucket)){
				throw new IllegalStateException("Bucket integrity failed:\n" + bucket + "\n" + read);
			}
		}
	}
	
	private static int hashToSmall(int hash, short bucketPO2){
		return Math.abs(hash)%(1<<bucketPO2);
	}
	
}

