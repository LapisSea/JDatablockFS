package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOType;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.ObjectHolder;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.*;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NULLABLE;

public class HashIOMap<K, V> extends AbstractUnmanagedIOMap<K, V>{
	
	private static class BucketEntry<K, V> extends IOInstance<BucketEntry<K, V>>{
		
		@IOValue
		@IONullability(NULLABLE)
		@IOType.Dynamic
		private K key;
		
		@IOValue
		@IONullability(NULLABLE)
		@IOType.Dynamic
		private V value;
		
		@Override
		public String toString(){
			return this.getClass().getSimpleName()+toShortString();
		}
		@Override
		public String toShortString(){
			return "{"+TextUtil.toShortString(key)+" = "+TextUtil.toShortString(value)+"}";
		}
		
		private Entry<K, V> unmodifiable;
		
		public BucketEntry(){}
		public BucketEntry(K key, V value){
			this.key=key;
			this.value=value;
		}
		
		public Entry<K, V> unmodifiable(){
			if(unmodifiable==null){
				unmodifiable=new Entry.Abstract<>(){
					@Override
					public K getKey(){
						return key;
					}
					@Override
					public V getValue(){
						return value;
					}
					@Override
					public void set(V value){
						throw new UnsupportedOperationException();
					}
				};
			}
			return unmodifiable;
		}
	}
	
	private static class Bucket<K, V> extends IOInstance<Bucket<K, V>> implements Iterable<BucketEntry<K, V>>{
		@IOValue
		@IONullability(NULLABLE)
		private LinkedIOList.Node<BucketEntry<K, V>> node;
		
		public BucketEntry<K, V> entry(K key) throws IOException{
			if(node==null) return null;
			for(LinkedIOList.Node<BucketEntry<K, V>> entry : node){
				var value=entry.getValue();
				if(value!=null&&Objects.equals(value.key, key)){
					return value;
				}
			}
			
			return null;
		}
		
		public void put(BucketEntry<K, V> entry) throws IOException{
			for(var node : node){
				var e=node.getValue();
				if(e!=null&&Objects.equals(e.key, entry.key)){
					node.setValue(entry);
					return;
				}
			}
			throw new RuntimeException("bucket entry not found");
		}
		
		private Stream<BucketEntry<K, V>> stream(){
			if(node==null) return Stream.of();
			return UtilL.stream(node.valueIterator());
		}
		public BucketEntry<K, V> getEntryByKey(K key) throws IOException{
			if(node==null) return null;
			for(var entry : node){
				var value=entry.getValue();
				if(value!=null&&Objects.equals(value.key, key)){
					return value;
				}
			}
			
			return null;
		}
		
		@Override
		public Iterator<BucketEntry<K, V>> iterator(){
			if(node==null) return Collections.emptyIterator();
			return node.valueIterator();
		}
	}
	
	private static final int RESIZE_TRIGGER=4;
	
	@IOValue
	private short bucketPO2=1;
	
	@IOValue
	@IOValue.OverrideType(ContiguousIOList.class)
	private IOList<Bucket<K, V>> buckets;
	
	private int datasetID;
	
	public HashIOMap(DataProvider provider, Reference reference, TypeLink typeDef) throws IOException{
		super(provider, reference, typeDef);
		
		if(isSelfDataEmpty()){
			newBuckets();
			writeManagedFields();
			fillBuckets();
		}
		readManagedFields();
	}
	
	private short calcNewSize(IOList<Bucket<K, V>> buckets, short bucketPO2){
		Map<Integer, ObjectHolder<Integer>> counts=new HashMap<>();
		
		short newBucketPO2=bucketPO2;
		
		int[] hashes=rawEntryStream(buckets).map(e->e.key).mapToInt(this::toHash).toArray();
		
		boolean overflow=true;
		while(overflow){
			overflow=false;
			counts.clear();
			
			if((((int)newBucketPO2)+1)>Short.MAX_VALUE){
				throw new IndexOutOfBoundsException();
			}
			
			newBucketPO2++;
			int newSize=1<<newBucketPO2;
			
			for(int hash : hashes){
				int smallHash=hash%newSize;
				var val      =counts.computeIfAbsent(smallHash, h->new ObjectHolder<>(0));
				val.obj++;
				if(val.obj>RESIZE_TRIGGER){
					overflow=true;
					break;
				}
			}
		}
		return newBucketPO2;
	}
	
	
	private void fillBuckets() throws IOException{
		var siz=1L<<bucketPO2;
		buckets.addAll(LongStream.range(0, siz-buckets.size()).mapToObj(i->new Bucket<K, V>()).toList());
	}
	
	@SuppressWarnings("unchecked")
	private void newBuckets() throws IOException{
		getThisStruct().getFields()
		               .requireExactFieldType(IOField.Ref.class, "buckets")
		               .allocateUnmanaged(this);
	}
	
	private void reflow() throws IOException{
		var oldBuckets  =buckets;
		var oldBucketPO2=bucketPO2;
		
		bucketPO2=calcNewSize(oldBuckets, oldBucketPO2);
		
		newBuckets();
		fillBuckets();
		
		datasetID++;
		transfer(oldBuckets, buckets, bucketPO2, size()<256);
		
		try(var ignored=getDataProvider().getSource().openIOTransaction()){
			writeManagedFields();
		}
		
		((Unmanaged<?>)oldBuckets).free();
		
	}
	
	private void transfer(IOList<Bucket<K, V>> oldBuckets, IOList<Bucket<K, V>> newBuckets, short newPO2, boolean optimizedOrder) throws IOException{
		if(optimizedOrder){
			Map<Integer, List<BucketEntry<K, V>>> hashGroupings=new TreeMap<>();
			
			for(var e : rawEntries(oldBuckets)){
				hashGroupings.computeIfAbsent(toSmallHash(e.key, newPO2), i->new ArrayList<>()).add(e);
			}
			for(var group : hashGroupings.values()){
				var key=group.get(0).key;
				
				Bucket<K, V> bucket=getBucket(newBuckets, key, newPO2);
				assert bucket.node==null;
				bucket.allocateNulls(getDataProvider());
				setBucket(newBuckets, key, newPO2, bucket);
				
				Iterator<BucketEntry<K, V>> iter=group.iterator();
				assert iter.hasNext();
				
				var node=bucket.node;
				node.setValue(iter.next());
				
				while(iter.hasNext()){
					var newNode=allocNewNode(iter.next());
					
					node.setNext(newNode);
					node=newNode;
				}
			}
		}else{
			for(var e : rawEntries(oldBuckets)){
				putEntry(newBuckets, newPO2, e.key, e.value);
			}
		}
	}
	
	private class ModifiableEntry extends Entry.Abstract<K, V>{
		
		private       int               currentDatasetID=datasetID;
		private       Bucket<K, V>      currentBucket;
		private final BucketEntry<K, V> data;
		
		public ModifiableEntry(Bucket<K, V> bucket, BucketEntry<K, V> entry){
			currentBucket=bucket;
			this.data=entry;
		}
		
		@Override
		public K getKey(){return data.key;}
		@Override
		public V getValue(){return data.value;}
		
		private void fastSet(V value) throws IOException{
			data.value=value;
			currentBucket.put(data);
		}
		private void mapSet(V value) throws IOException{
			data.value=value;
			HashIOMap.this.put(getKey(), value);
		}
		
		@Override
		public void set(V value) throws IOException{
			if(datasetID!=currentDatasetID){
				if(tryUpdateData()){
					fastSet(value);
				}else mapSet(value);
			}else fastSet(value);
		}
		
		private boolean tryUpdateData() throws IOException{
			Bucket<K, V> bucket=getBucket(buckets, getKey(), bucketPO2);
			if(bucket==null) return false;
			
			currentBucket=bucket;
			currentDatasetID=datasetID;
			return true;
		}
	}
	
	@Override
	public Entry<K, V> getEntry(K key) throws IOException{
		Bucket<K, V> bucket=getBucket(buckets, key, bucketPO2);
		if(bucket==null){
			return null;
		}
		
		var entry=bucket.entry(key);
		if(entry==null){
			return null;
		}
		
		return new ModifiableEntry(bucket, entry);
	}
	
	@Override
	public Stream<Entry<K, V>> stream(){
		return rawEntryStream(buckets).map(BucketEntry::unmodifiable);
	}
	private IterablePP<BucketEntry<K, V>> rawEntries(IOList<Bucket<K, V>> buckets){
		return ()->rawEntryStream(buckets).iterator();
	}
	private Stream<BucketEntry<K, V>> rawEntryStream(IOList<Bucket<K, V>> buckets){
		return buckets.stream().flatMap(e->e.stream().filter(Objects::nonNull));
	}
	
	@Override
	public void put(K key, V value) throws IOException{
		long sizeFlag;
		try(var ignored=getDataProvider().getSource().openIOTransaction()){
			sizeFlag=putEntry(buckets, bucketPO2, key, value);
		}
		if(sizeFlag==OVERWRITE) return;
		
		deltaSize(1);
		if(sizeFlag==OVERWRITE_EMPTY) return;
		if(sizeFlag>RESIZE_TRIGGER){
			reflow();
		}
	}
	
	private static final long OVERWRITE      =-1;
	private static final long OVERWRITE_EMPTY=-2;
	
	private long putEntry(IOList<Bucket<K, V>> buckets, short bucketPO2, K key, V value) throws IOException{
		
		Bucket<K, V> bucket=getBucket(buckets, key, bucketPO2);
		
		var entry=bucket.getEntryByKey(key);
		if(entry!=null){
			entry.value=value;
			bucket.put(entry);
			return OVERWRITE;
		}
		
		BucketEntry<K, V> newEntry=new BucketEntry<>(key, value);
		
		if(bucket.node==null){
			bucket.allocateNulls(getDataProvider());
			setBucket(buckets, key, bucketPO2, bucket);
		}
		
		long count=0;
		
		LinkedIOList.Node<BucketEntry<K, V>> last=bucket.node;
		for(var node : bucket.node){
			if(!node.hasValue()){
				if(DEBUG_VALIDATION){
					var val=node.getValue();
					if(val!=null) throw new RuntimeException(node+" hasValue is not correct");
				}
				
				node.setValue(newEntry);
				return OVERWRITE_EMPTY;
			}
			
			last=node;
			count++;
		}
		
		
		LinkedIOList.Node<BucketEntry<K, V>> newNode=allocNewNode(newEntry);
		
		last.setNext(newNode);
		
		return count;
	}
	private LinkedIOList.Node<BucketEntry<K, V>> allocNewNode(BucketEntry<K, V> newEntry) throws IOException{
		return LinkedIOList.Node.allocValNode(
			newEntry,
			null,
			ContiguousStructPipe.of((Class<BucketEntry<K, V>>)(Object)BucketEntry.class).getSizeDescriptor(),
			new TypeLink(
				LinkedIOList.Node.class,
				TypeLink.of(BucketEntry.class)
			),
			getDataProvider()
		);
	}
	
	
	private Bucket<K, V> getBucket(IOList<Bucket<K, V>> buckets, K key, short bucketPO2) throws IOException{
		int smallHash=toSmallHash(key, bucketPO2);
		return buckets.get(smallHash);
	}
	private void setBucket(IOList<Bucket<K, V>> buckets, K key, short bucketPO2, Bucket<K, V> bucket) throws IOException{
		int smallHash=toSmallHash(key, bucketPO2);
		buckets.set(smallHash, bucket);
		if(DEBUG_VALIDATION){
			var read=buckets.get(smallHash);
			assert read.equals(bucket);
		}
	}
	
	private int toSmallHash(K key, short bucketPO2){
		int hash=toHash(key);
		return hashToSmall(hash, bucketPO2);
	}
	
	private int hashToSmall(int hash, short bucketPO2){
		return Math.abs(hash)%(1<<bucketPO2);
	}
	
	private int toHash(K key){
		if(key==null){
			return 0;
		}
		return key.hashCode();
	}
}
