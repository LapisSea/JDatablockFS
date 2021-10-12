package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.TypeDefinition;
import com.lapissea.cfs.type.field.annotations.IODependency;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOType;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.ObjectHolder;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.*;

public class HashIOMap<K, V> extends AbstractUnmanagedIOMap<K, V, HashIOMap<K, V>>{
	
	private static class BucketEntry<K, V> extends IOInstance<BucketEntry<K, V>>{
		
		@IOValue
		@IONullability(NULLABLE)
		@IOType.Dynamic
		private K key;
		
		@IOValue
		@IONullability(NULLABLE)
		@IOType.Dynamic
		private V value;
		
		private Entry<K, V> unmodifiable;
		
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
	
	private static class Bucket<K, V> extends IOInstance<Bucket<K, V>>{
		
		@IOValue
		@IODependency.VirtualNumSize(name="hSiz")
		private int hash;
		
		@IOValue
		@IOValue.OverrideType(LinkedIOList.class)
		private IOList<BucketEntry<K, V>> entries;
		
		public void newEntry(K key, V value) throws IOException{
			entries.addNew(e->{
				e.key=key;
				e.value=value;
			});
		}
		
		public BucketEntry<K, V> entry(K key) throws IOException{
			for(var entry : entries){
				if(Objects.equals(entry.key, key)){
					return entry;
				}
			}
			
			return null;
		}
		
		public void put(BucketEntry<K, V> entry) throws IOException{
			var iter=entries.listIterator();
			
			while(iter.hasNext()){
				var e=iter.ioNext();
				
				if(Objects.equals(e.key, entry.key)){
					iter.ioSet(entry);
					break;
				}
			}
		}
		
		private long size(){
			return entries.size();
		}
	}
	
	private static final int RESIZE_TRIGGER=4;
	
	@IOValue
	@IODependency.VirtualNumSize(name="bSiz")
	private int bucketSize=1;
	
	@IOValue
	@IOValue.OverrideType(ContiguousIOList.class)
	private IOList<Bucket<K, V>> buckets;
	
	private long size;
	
	private int datasetID;
	
	public HashIOMap(ChunkDataProvider provider, Reference reference, TypeDefinition typeDef) throws IOException{
		super(provider, reference, typeDef);
		
		if(isSelfDataEmpty()){
			allocateNulls();
			buckets.requestCapacity(bucketSize);
			writeManagedFields();
		}
		readManagedFields();
		
		size=buckets.stream().mapToLong(Bucket::size).sum();
	}
	
	private void reflow() throws IOException{
		
		Map<Integer, ObjectHolder<Integer>> counts=new HashMap<>();
		
		int newSize=bucketSize;
		
		int[] hashes=entries().stream().map(Entry::getKey).mapToInt(this::toHash).toArray();
		
		boolean overflow=true;
		while(overflow){
			overflow=false;
			counts.clear();
			newSize++;
			
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
		
		var old=buckets;
		
		buckets=null;
		bucketSize=newSize;
		
		allocateNulls();
		buckets.requestCapacity(counts.size());
		datasetID++;
		transfer(old, buckets, newSize);
		
		writeManagedFields();
	}
	
	private void transfer(IOList<Bucket<K, V>> oldBuckets, IOList<Bucket<K, V>> newBuckets, int newSize) throws IOException{
		for(var e : entries(oldBuckets)){
			putEntry(newBuckets, newSize, e.getKey(), e.getValue());
		}
	}
	
	@Override
	public long size(){
		return size;
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
		
		private boolean tryUpdateData(){
			int smallHash=toSmallHash(getKey(), bucketSize);
			
			Bucket<K, V> bucket=getByHash(buckets, smallHash);
			if(bucket==null) return false;
			
			currentBucket=bucket;
			currentDatasetID=datasetID;
			return true;
		}
	}
	
	@Override
	public Entry<K, V> getEntry(K key) throws IOException{
		int smallHash=toSmallHash(key, bucketSize);
		
		Bucket<K, V> bucket=getByHash(buckets, smallHash);
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
	public IterablePP<Entry<K, V>> entries(){
		return entries(buckets);
	}
	
	private IterablePP<Entry<K, V>> entries(IOList<Bucket<K, V>> buckets){
		return ()->buckets.stream().flatMap(e->e.entries.stream().map(BucketEntry::unmodifiable)).iterator();
	}
	
	@Override
	public void put(K key, V value) throws IOException{
		var b=putEntry(buckets, bucketSize, key, value);
		if(b==null) return;
		size++;
		if(b.size()>RESIZE_TRIGGER){
			reflow();
		}
	}
	
	private Bucket<K, V> putEntry(IOList<Bucket<K, V>> buckets, int bucketSize, K key, V value) throws IOException{
		int smallHash=toSmallHash(key, bucketSize);
		
		Bucket<K, V> bucket=getByHash(buckets, smallHash);
		if(bucket==null){
			bucket=buckets.addNew(b->{
				b.hash=smallHash;
				b.allocateNulls(getChunkProvider());
			});
		}
		
		var entry=getBucketEntry(bucket, key);
		if(entry!=null){
			entry.value=value;
			bucket.put(entry);
			return null;
		}
		
		bucket.newEntry(key, value);
		return bucket;
	}
	
	
	private BucketEntry<K, V> getBucketEntry(Bucket<K, V> bucket, K key){
		if(bucket.entries!=null){
			for(var e : bucket.entries){
				if(Objects.equals(e.key, key)){
					return e;
				}
			}
		}
		return null;
	}
	
	private Bucket<K, V> getByHash(IOList<Bucket<K, V>> buckets, int hash){
		for(var bucket : buckets){
			if(bucket.hash==hash){
				return bucket;
			}
		}
		return null;
	}
	
	private int toSmallHash(K key, int bucketSize){
		int hash=toHash(key);
		return hashToSmall(hash, bucketSize);
	}
	
	private int hashToSmall(int hash, int bucketSize){
		return Math.abs(hash)%bucketSize;
	}
	
	private int toHash(K key){
		if(key==null){
			return 0;
		}
		return key.hashCode();
	}
}
