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
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.type.field.SizeDescriptor;
import com.lapissea.dfs.type.field.annotations.IODependency;
import com.lapissea.dfs.type.field.annotations.IONullability;
import com.lapissea.dfs.type.field.annotations.IOValue;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldPrimitive;
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
	
	private sealed interface BucketResult<T>{
		record EmptyIndex<T>(long index) implements BucketResult<T>{
			public EmptyIndex{
				if(index<0) throw new IllegalArgumentException("index should not be negative");
			}
		}
		
		record TailNode<T>(IONode<T> node) implements BucketResult<T>{
			public TailNode{ Objects.requireNonNull(node); }
		}
		
		record EqualsNode<T>(long index, IONode<T> previous, IONode<T> node) implements BucketResult<T>{
			public EqualsNode{ Objects.requireNonNull(node); }
		}
	}
	
	private static final class BucketSet<K, V> extends IOInstance.Unmanaged<BucketSet<K, V>>{
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
		
		private BucketSet(DataProvider provider, Chunk identity, IOType typeDef) throws IOException{
			super(provider, identity, typeDef);
			if(!isSelfDataEmpty()){
				readManagedFields();
			}
		}
		
		private void init(long capacity) throws IOException{
			allocateNulls();
			data.addMultipleNew(capacity);
			this.capacity = capacity;
			writeManagedFields();
		}
		
		private IOFieldPrimitive.FLong<BucketSet<K, V>> sizeField;
		private void deltaCount(long delta) throws IOException{
			if(sizeField == null) sizeField = getThisStruct().getFields().requireExactLong("entryCount");
			entryCount += delta;
			if(entryCount<0) throw new ShouldNeverHappenError();
			writeManagedField(sizeField);
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
					case BucketResult.EmptyIndex(var index) -> {
						destSet.data.set(index, node);
						inc++;
					}
					case BucketResult.EqualsNode<BucketEntry<K, V>> ignore2 -> {
						Log.warn("Found duplicate key in map! Possible corruption. Key: {}#red", key);
					}
					case BucketResult.TailNode(var destNode) -> {
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
			if(find(keyHash, entry.key()) instanceof BucketResult.EqualsNode<BucketEntry<K, V>> place){
				place.node.setValue(entry);
				return true;
			}
			return false;
		}
		private void put(int keyHash, BucketEntry<K, V> entry) throws IOException{
			switch(find(keyHash, entry.key())){
				case BucketResult.EmptyIndex(var index) -> {
					var node = allocNewNode(entry, data.magentPos(index));
					
					try(var ignore = getDataProvider().getSource().openIOTransaction()){
						data.set(index, node);
						deltaCount(1);
					}
				}
				case BucketResult.EqualsNode<BucketEntry<K, V>> place -> {
					place.node.setValue(entry);
				}
				case BucketResult.TailNode(var node) -> {
					var nextNode = allocNewNode(entry, node.getPointer().getValue());
					try(var ignore = getDataProvider().getSource().openIOTransaction()){
						node.setNext(nextNode);
						deltaCount(1);
					}
				}
			}
		}
		
		private boolean contains(int keyHash, K key) throws IOException{
			return find(keyHash, key) instanceof BucketResult.EqualsNode;
		}
		
		private BucketEntry<K, V> get(int keyHash, K key) throws IOException{
			if(find(keyHash, key) instanceof BucketResult.EqualsNode<BucketEntry<K, V>> v){
				return v.node.getValue();
			}
			return null;
		}
		
		private boolean remove(int keyHash, K key) throws IOException{
			if(!(find(keyHash, key) instanceof BucketResult.EqualsNode(var index, var prev, var node))){
				return false;
			}
			try(var ignore = getDataProvider().getSource().openIOTransaction()){
				var next = node.getNext();
				if(prev == null){
					data.set(index, next);
				}else{
					prev.setNext(next);
				}
				deltaCount(-1);
			}
			node.setNext(null);
			node.free();
			return true;
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
					return new BucketResult.EqualsNode<>(index, last, node);
				}
				last = node;
			}
			
			return new BucketResult.TailNode<>(last);
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
		
		private double occupancy(){
			return entryCount/(double)capacity;
		}
		private double occupancy(long delta){
			return (entryCount + delta)/(double)capacity;
		}
		
		@Override
		public String toString(){
			return "BucketSet{" + entryCount + "/" + capacity + " " + occupancy() + "}";
		}
	}
	
	@IOValue
	@IONullability(NULLABLE)
	private BucketSet<K, V> amortizedSet;
	@IOValue
	@IONullability(NULLABLE)
	private BucketSet<K, V> mainSet;
	
	public HashIOMap(DataProvider provider, Chunk identity, IOType typeDef) throws IOException{
		super(provider, identity, typeDef);
		
		if(!readOnly && isSelfDataEmpty()){
			newMainSet(4);
			writeManagedFields();
		}
		readManagedFields();
	}
	
	@SuppressWarnings("unchecked")
	private void newMainSet(long size) throws IOException{
		getThisStruct().getFields()
		               .requireExactFieldType(RefField.class, "mainSet")
		               .allocateUnmanaged(this);
		mainSet.init(size);
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
		var hash  = HashCommons.toHash(key);
		var entry = mainSet.get(hash, key);
		if(entry == null && amortizedSet != null){
			entry = amortizedSet.get(hash, key);
		}
		return entry == null? null : new ModifiableIOEntry(entry);
	}
	
	@Override
	public boolean containsKey(K key) throws IOException{
		var hash = HashCommons.toHash(key);
		return containsKeyHashed(key, hash);
	}
	private boolean containsKeyHashed(K key, int hash) throws IOException{
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
		}finally{
			transaction.close();
		}
		if(amortizedSet.data.isEmpty()){
			var s = amortizedSet;
			amortizedSet = null;
			writeManagedFields();
			s.free();
		}
		return removedTotal;
	}
	
	@Override
	public void put(K key, V value) throws IOException{
		if(DEBUG_VALIDATION) checkValue(value);
		
		var hash = HashCommons.toHash(key);
		
		if(amortizedSet != null){
			reconcile(2);
		}
		if(mainSet.occupancy(1)>=0.8){
			resizeAmortized((long)(mainSet.capacity*1.618));
		}
		var e = BucketEntry.of(key, value);
		if(amortizedSet != null && amortizedSet.replace(hash, e)){
			return;
		}
		
		mainSet.put(hash, e);
		if(DEBUG_VALIDATION) checkOccupancy();
	}
	
	private void checkOccupancy(){
		var occ = mainSet.occupancy();
		if(occ>0.8){
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
		
		if(amortizedSet != null){
			reconcile(toAdd.size()*2L);
		}
		
		if(mainSet.occupancy(newKeys)>=0.8){
			long regularSize = (long)(mainSet.capacity*1.618);
			long keySize     = (long)((mainSet.entryCount + newKeys)*1.25);
			resizeAmortized(Math.max(regularSize, keySize) + 1);
		}
		
		for(var e : toAdd){
			var entry = BucketEntry.of(e.key, e.value);
			if(amortizedSet != null && amortizedSet.replace(e.hash, entry)){
				continue;
			}
			mainSet.put(e.hash, entry);
		}
		if(DEBUG_VALIDATION) checkOccupancy();
	}
	
	@Override
	public boolean remove(K key) throws IOException{
		if(amortizedSet != null) reconcile(1);
		
		var hash = HashCommons.toHash(key);
		
		if(mainSet.remove(hash, key)){
			//Shrink
			if(amortizedSet == null && mainSet.capacity>8 && mainSet.occupancy()<=0.3){
				resizeAmortized(mainSet.capacity/2);
			}
			return true;
		}
		
		if(amortizedSet == null) return false;
		
		var removed = amortizedSet.remove(hash, key);
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
			newMainSet(4);
			writeManagedFields();
		}
		
		if(as != null){
			as.free();
		}
		ms.free();
		
	}
}
