package com.lapissea.dfs.objects.collections;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.exceptions.InvalidGenericArgument;
import com.lapissea.dfs.internal.HashCommons;
import com.lapissea.dfs.internal.Preload;
import com.lapissea.dfs.io.ValueStorage;
import com.lapissea.dfs.io.impl.MemoryData;
import com.lapissea.dfs.io.instancepipe.StandardStructPipe;
import com.lapissea.dfs.io.instancepipe.StructPipe;
import com.lapissea.dfs.logging.Log;
import com.lapissea.dfs.objects.collections.BucketResult.EmptyIndex;
import com.lapissea.dfs.objects.collections.BucketResult.EqualsResult;
import com.lapissea.dfs.objects.collections.BucketResult.TailNode;
import com.lapissea.dfs.type.GenericContext;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.ShouldNeverHappenError;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.BiFunction;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.dfs.type.field.annotations.IONullability.Mode.NULLABLE;

public class HashIOMap<K, V> extends UnmanagedIOMap<K, V>{
	
	static{ Preload.preloadFn(BucketEntry.class, "of", null, null); }
	
	@SuppressWarnings({"unchecked"})
	@StrFormat.Custom("[!!className]{@key: @value}")
	@Order({"key", "value"})
	private interface BucketEntry<K, V> extends IOInstance.Def<BucketEntry<K, V>>{
		
		Struct<BucketEntry<Object, Object>>     STRUCT = Struct.of((Class<BucketEntry<Object, Object>>)(Object)BucketEntry.class);
		StructPipe<BucketEntry<Object, Object>> PIPE   = StandardStructPipe.of(STRUCT);
		
		static <K, V> BucketEntry<K, V> of(K key, V value){
			//noinspection rawtypes
			class Cache{
				private static BiFunction<Object, Object, BucketEntry> make;
			}
			var c = Cache.make;
			if(c == null) c = Cache.make = IOInstance.Def.constrRef(BucketEntry.class, Object.class, Object.class);
			return c.apply(key, value);
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
	
	private static final class BucketSet<K, V> extends IOInstance.Managed<BucketSet<K, V>>{
		@IOValue
		private ContiguousIOList<IONode<BucketEntry<K, V>>> data;
		
		@IOValue
		@IOValue.Unsigned
		@IODependency.VirtualNumSize
		private long entryCount;
		@IOValue
		@IOValue.Unsigned
		@IODependency.VirtualNumSize
		private long capacity;
		
		private void init(DataProvider provider, GenericContext ctx, long capacity) throws IOException{
			allocateNulls(provider, ctx);
			data.addMultipleNew(capacity);
			this.capacity = capacity;
		}
		
		private void deltaCount(long delta){
			entryCount += delta;
			if(entryCount<0) throw new ShouldNeverHappenError();
		}
		
		private int transferTo(BucketSet<K, V> destSet, int toMove) throws IOException{
			if(toMove<=0) throw new IllegalArgumentException("toMove should be positive");
			var moveNodes = new ArrayList<IONode<BucketEntry<K, V>>>(4);
			while(!data.isEmpty()){
				var node = data.getLast();
				if(node == null){
					data.removeLast();
					continue;
				}
				while(node != null && moveNodes.size()<toMove){
					moveNodes.add(node);
					var next = node.getNext();
					node.setNext(null);
					node = next;
				}
				if(node != null){
					data.set(data.size() - 1, node);
				}else{
					data.set(data.size() - 1, null);
					data.removeLast();
				}
				break;
			}
			var inc = 0;
			for(var node : moveNodes){
				K key;
				{
					var keyRes = readKey(node);
					if(!keyRes.hasValue) continue;
					key = keyRes.key;
				}
				
				switch(destSet.find(HashCommons.toHash(key), key)){
					case EmptyIndex(var index) -> {
						destSet.data.set(index, node);
						inc++;
					}
					case EqualsResult<BucketEntry<K, V>> ignore2 -> {
						Log.warn("Found duplicate key in map! Possible corruption. Key: {}#red", key);
					}
					case TailNode(var destNode) -> {
						destNode.setNext(node);
						inc++;
					}
				}
			}
			if(inc != 0){
				destSet.deltaCount(inc);
				deltaCount(-inc);
			}
			return moveNodes.size();
		}
		private boolean replace(int keyHash, BucketEntry<K, V> entry) throws IOException{
			if(find(keyHash, entry.key()) instanceof EqualsResult(var i, var p, var node)){
				node.setValue(entry);
				return true;
			}
			return false;
		}
		
		private boolean contains(int keyHash, K key) throws IOException{
			return find(keyHash, key) instanceof EqualsResult;
		}
		
		private BucketEntry<K, V> get(int keyHash, K key) throws IOException{
			if(find(keyHash, key) instanceof EqualsResult<BucketEntry<K, V>> v){
				return v.node().getValue();
			}
			return null;
		}
		
		private BucketResult<BucketEntry<K, V>> find(int keyHash, K key) throws IOException{
			var index = keyHash%capacity;
			if(index>=data.size()) return new BucketResult.EmptyIndex<>(index);
			
			var root = data.get(index);
			if(root == null){
				return new BucketResult.EmptyIndex<>(index);
			}
			
			IONode<BucketEntry<K, V>> last = null;
			for(IONode<BucketEntry<K, V>> node : root){
				KeyResult<K> keyResult = readKey(node);
				if(!keyResult.hasValue) continue;
				if(Objects.equals(keyResult.key, key)){
					return new BucketResult.EqualsResult<>(index, last, node);
				}
				last = node;
			}
			
			return new BucketResult.TailNode<>(last);
		}
		
		private double occupancy(){
			return entryCount/(double)capacity;
		}
		private boolean isFull(long delta){
			return occupancy(delta)>=MAX_OCCUPANCY;
		}
		private double occupancy(long delta){
			return (entryCount + delta)/(double)capacity;
		}
		
		@Override
		public String toString(){
			return "BucketSet{" + entryCount + "/" + capacity + " " + occupancy() + "}";
		}
	}
	
	private static final int    MIN_SIZE      = 4;
	private static final double MIN_OCCUPANCY = 0.3;
	private static final double MAX_OCCUPANCY = 0.8;
	private static final double GROWTH_FACTOR = 1.618;
	
	@IOValue
	@IONullability(NULLABLE)
	private BucketSet<K, V> amortizedSet;
	@IOValue
	@IONullability(NULLABLE)
	private BucketSet<K, V> mainSet;
	
	public HashIOMap(DataProvider provider, Chunk identity, IOType typeDef) throws IOException{
		super(provider, identity, typeDef);
		
		if(!readOnly && isSelfDataEmpty()){
			writeManagedFields();
		}
		readManagedFields();
	}
	
	private void putToSet(BucketSet<K, V> dest, int keyHash, BucketEntry<K, V> entry) throws IOException{
		switch(dest.find(keyHash, entry.key())){
			case EmptyIndex(var index) -> {
				var node = allocNewNode(entry, dest.data.magentPos(index));
				
				try(var ignore = getDataProvider().getSource().openIOTransaction()){
					dest.data.set(index, node);
					dest.deltaCount(1);
					writeManagedFields();
				}
			}
			case EqualsResult<BucketEntry<K, V>> place -> {
				place.node().setValue(entry);
			}
			case TailNode(var node) -> {
				var nextNode = allocNewNode(entry, node.getPointer().getValue());
				try(var ignore = getDataProvider().getSource().openIOTransaction()){
					node.setNext(nextNode);
					dest.deltaCount(1);
					writeManagedFields();
				}
			}
		}
	}
	private boolean removeFromSet(BucketSet<K, V> set, int keyHash, K key) throws IOException{
		if(!(set.find(keyHash, key) instanceof EqualsResult(var index, var prev, var node))){
			return false;
		}
		try(var ignore = getDataProvider().getSource().openIOTransaction()){
			var next = node.getNext();
			if(prev == null){
				set.data.set(index, next);
			}else{
				prev.setNext(next);
			}
			set.deltaCount(-1);
			writeManagedFields();
		}
		node.setNext(null);
		node.free();
		return true;
	}
	
	private IOType buckedNodeType(){
		return IOType.of(
			IONode.class,
			((IOType.RawAndArg)getTypeDef()).withRaw(BucketEntry.class)
		);
	}
	
	@SuppressWarnings({"unchecked", "OverlyStrongTypeCast"})
	private IONode<BucketEntry<K, V>> allocNewNode(BucketEntry<K, V> newEntry, long magnet) throws IOException{
		return IONode.allocValNode(
			newEntry,
			null,
			(SizeDescriptor<BucketEntry<K, V>>)(Object)BucketEntry.PIPE.getSizeDescriptor(),
			buckedNodeType(),
			getDataProvider(),
			OptionalLong.of(magnet)
		);
	}
	
	private void newMainSet(long size) throws IOException{
		mainSet = new BucketSet<>();
		
		var bType   = ((IOType.RawAndArg)getTypeDef()).withRaw(BucketSet.class);
		var generic = bType.generic(getDataProvider().getTypeDb());
		var ctx     = GenericContext.of(BucketSet.class, generic);
		mainSet.init(getDataProvider(), ctx, size);
	}
	
	private static IOField<BucketEntry<Object, Object>, ?> keyVar;
	
	private record KeyResult<K>(K key, boolean hasValue){ }
	private static <K, V> KeyResult<K> readKey(IONode<BucketEntry<K, V>> n) throws IOException{
		if(keyVar == null) initKey();
		
		var res = n.readValueField(keyVar);
		if(res.empty()) return new KeyResult<>(null, false);
		var v = res.val();
		return new KeyResult<>(v.key(), true);
	}
	private static void initKey(){
		keyVar = BucketEntry.STRUCT.getFields().requireByName("key");
	}
	
	
	private class ModifiableIOEntry extends IOEntry.Modifiable.Abstract<K, V>{
		
		private final BucketEntry<K, V> data;
		
		public ModifiableIOEntry(BucketEntry<K, V> entry){
			this.data = entry;
		}
		
		@Override
		public K getKey(){ return data.key(); }
		@Override
		public V getValue(){ return data.value(); }
		
		private void mapSet(V value) throws IOException{
			data.value(value);
			HashIOMap.this.put(getKey(), value);
		}
		
		@Override
		public void set(V value) throws IOException{
			mapSet(value);
		}
	}
	
	@Override
	public long size(){
		long size = 0;
		var  ms   = mainSet;
		if(ms != null) size += ms.entryCount;
		var as = amortizedSet;
		if(as != null) size += as.entryCount;
		return size;
	}
	@Override
	public IOEntry.Modifiable<K, V> getEntry(K key) throws IOException{
		if(mainSet == null) return null;
		var hash  = HashCommons.toHash(key);
		var entry = mainSet.get(hash, key);
		if(entry == null && amortizedSet != null){
			entry = amortizedSet.get(hash, key);
		}
		return entry == null? null : new ModifiableIOEntry(entry);
	}
	
	@Override
	public boolean containsKey(K key) throws IOException{
		if(mainSet == null) return false;
		var hash = HashCommons.toHash(key);
		return containsKeyHashed(key, hash);
	}
	private boolean containsKeyHashed(K key, int hash) throws IOException{
		if(mainSet == null) return false;
		if(amortizedSet != null && amortizedSet.contains(hash, key)) return true;
		return mainSet.contains(hash, key);
	}
	
	@Override
	public IOIterator.Iter<IOEntry<K, V>> iterator(){
		return new IOIterator.Iter<>(){
			private final Iterator<BucketSet<K, V>>             setsIterator = Iters.of(amortizedSet, mainSet).nonNulls().iterator();
			private       IOIterator<IONode<BucketEntry<K, V>>> bucketSetIter;
			private       IOIterator<BucketEntry<K, V>>         bucketIter;
			
			private IOIterator<BucketEntry<K, V>> tryFindNextBucket() throws IOException{
				IONode<BucketEntry<K, V>> next = null;
				while(next == null){
					while(bucketSetIter == null || !bucketSetIter.hasNext()){
						if(!setsIterator.hasNext()) return null;
						var set = setsIterator.next();
						bucketSetIter = set.data.iterator();
					}
					next = bucketSetIter.ioNext();
				}
				var iter = next.valueIterator();
				return bucketIter = iter;
			}
			
			@Override
			public boolean hasNext(){
				try{
					var bi = bucketIter;
					if(bi != null && bi.hasNext()) return true;
					return tryFindNextBucket() != null;
				}catch(IOException e){
					throw new UncheckedIOException(e);
				}
			}
			
			@Override
			public IOEntry<K, V> ioNext() throws IOException{
				var bi = bucketIter;
				if(bi == null || !bi.hasNext()){
					if((bi = tryFindNextBucket()) == null) throw new NoSuchElementException();
				}
				var value = bi.ioNext();
				return value.unmodifiable();
			}
		};
	}
	
	
	private long reconcileFully() throws IOException{
		if(amortizedSet == null) return 0;
		return reconcile(amortizedSet.entryCount);
	}
	private long reconcile(long count) throws IOException{
		Unmanaged<?> toFree = null;
		
		var  transaction  = getDataProvider().getSource().openIOTransaction();
		long removedTotal = 0;
		try{
			var remaining = count;
			int acum      = 0;
			while(remaining>0 && !amortizedSet.data.isEmpty()){
				if(acum>64){
					transaction.close();
					transaction = getDataProvider().getSource().openIOTransaction();
					acum = 0;
				}
				var removed = amortizedSet.transferTo(mainSet, (int)Math.min(Integer.MAX_VALUE, remaining));
				remaining -= removed;
				acum += removed;
				removedTotal += removed;
			}
			var data = amortizedSet.data;
			while(!data.isEmpty() && data.getLast() == null){
				data.removeLast();
			}
			if(data.isEmpty()){
				amortizedSet = null;
				toFree = data;
			}
			writeManagedFields();
			return removedTotal;
		}finally{
			transaction.close();
			if(toFree != null){
				toFree.free();
			}
		}
	}
	
	@Override
	public void put(K key, V value) throws IOException{
		if(DEBUG_VALIDATION) checkValue(value);
		if(mainSet == null) initMainSet();
		
		var hash = HashCommons.toHash(key);
		
		if(amortizedSet != null){
			reconcile(2);
		}
		if(mainSet.isFull(1)){
			resizeAmortized((long)(mainSet.capacity*GROWTH_FACTOR));
		}
		var e = BucketEntry.of(key, value);
		if(amortizedSet != null && amortizedSet.replace(hash, e)){
			return;
		}
		
		putToSet(mainSet, hash, e);
		if(DEBUG_VALIDATION) checkOccupancy();
	}
	
	private void initMainSet() throws IOException{
		newMainSet(MIN_SIZE);
		writeManagedFields();
	}
	
	private void checkOccupancy(){
		var occ = mainSet.occupancy();
		if(occ>MAX_OCCUPANCY){
			throw new AssertionError(occ);
		}
	}
	
	private void resizeAmortized(long newSize) throws IOException{
		if(amortizedSet != null){
			var removed = reconcileFully();
			if(removed>5){
				Log.trace("Reconciled {}#yellow elements before HashIOMap resize", removed);
			}
			assert amortizedSet == null;
		}
		try(var ignore = getDataProvider().getSource().openIOTransaction()){
			amortizedSet = mainSet;
			newMainSet(newSize);
			writeManagedFields();
			reconcile((int)Math.max(2, Math.min(10, amortizedSet.capacity/8)));
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
		record HashEntry<K, V>(K key, V value, int hash){ }
		var toAdd   = new ArrayList<HashEntry<K, V>>(values.size());
		var newKeys = 0;
		for(var e : values.entrySet()){
			var key  = e.getKey();
			var hash = HashCommons.toHash(key);
			if(!containsKeyHashed(key, hash)){
				newKeys++;
			}
			toAdd.add(new HashEntry<>(key, e.getValue(), hash));
		}
		
		if(toAdd.isEmpty()) return;
		
		if(mainSet == null) initMainSet();
		
		if(amortizedSet != null){
			reconcile(toAdd.size()*2L);
		}
		
		if(mainSet.isFull(newKeys)){
			long regularSize = (long)(mainSet.capacity*GROWTH_FACTOR);
			long keySize     = (long)((mainSet.entryCount + newKeys)*1.25);
			resizeAmortized(Math.max(regularSize, keySize) + 1);
		}
		
		for(var e : toAdd){
			var entry = BucketEntry.of(e.key, e.value);
			if(amortizedSet != null && amortizedSet.replace(e.hash, entry)){
				continue;
			}
			putToSet(mainSet, e.hash, entry);
		}
		if(DEBUG_VALIDATION) checkOccupancy();
	}
	
	@Override
	public boolean remove(K key) throws IOException{
		if(mainSet == null) return false;
		if(amortizedSet != null) reconcile(1);
		
		var hash = HashCommons.toHash(key);
		
		if(removeFromSet(mainSet, hash, key)){
			//Shrink
			if(amortizedSet == null && mainSet.capacity>MIN_SIZE*2 && mainSet.occupancy()<=MIN_OCCUPANCY){
				resizeAmortized(mainSet.capacity/2);
			}
			return true;
		}
		
		if(amortizedSet == null) return false;
		
		var removed = removeFromSet(amortizedSet, hash, key);
		if(removed){
			reconcile(1);
		}
		return removed;
	}
	
	@Override
	public void clear() throws IOException{
		var as = amortizedSet;
		var ms = mainSet;
		
		try(var ignored = getDataProvider().getSource().openIOTransaction()){
			amortizedSet = null;
			mainSet = null;
			writeManagedFields();
		}
		
		if(as != null){
			as.data.free();
		}
		if(ms != null){
			ms.data.free();
		}
	}
	
}
