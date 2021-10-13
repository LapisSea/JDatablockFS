package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.chunk.ChunkDataProvider;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.TypeDefinition;
import com.lapissea.cfs.type.field.IOField;
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
	private short bucketPO2=1;
	
	@IOValue
	@IOValue.OverrideType(ContiguousIOList.class)
	private IOList<Bucket<K, V>> buckets;
	
	private int datasetID;
	
	public HashIOMap(ChunkDataProvider provider, Reference reference, TypeDefinition typeDef) throws IOException{
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
		
		int[] hashes=entries(buckets).stream().map(Entry::getKey).mapToInt(this::toHash).toArray();
		
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
		buckets.requestCapacity(siz);
		while(buckets.size()<siz){
			buckets.addNew(b->b.allocateNulls(getChunkProvider()));
		}
	}
	
	@SuppressWarnings("unchecked")
	private void newBuckets() throws IOException{
		getThisStruct().getFields()
		               .requireExactFieldType(IOField.Ref.class, "buckets")
		               .allocateUnmanaged(this);
	}
	
	private void reflow() throws IOException{
		
		var old=buckets;
		
		buckets=null;
		bucketPO2=calcNewSize(old, bucketPO2);
		newBuckets();
		fillBuckets();
		datasetID++;
		transfer(old, buckets, bucketPO2);
		
		((Unmanaged<?>)old).free();
		
		writeManagedFields();
	}
	
	private void transfer(IOList<Bucket<K, V>> oldBuckets, IOList<Bucket<K, V>> newBuckets, short newPO2) throws IOException{
		for(var e : entries(oldBuckets)){
			putEntry(newBuckets, newPO2, e.getKey(), e.getValue());
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
	public IterablePP<Entry<K, V>> entries(){
		return entries(buckets);
	}
	
	private IterablePP<Entry<K, V>> entries(IOList<Bucket<K, V>> buckets){
		return ()->buckets.stream().flatMap(e->e.entries.stream().map(BucketEntry::unmodifiable)).iterator();
	}
	
	@Override
	public void put(K key, V value) throws IOException{
		var b=putEntry(buckets, bucketPO2, key, value);
		if(b==null) return;
		deltaSize(1);
		if(b.size()>RESIZE_TRIGGER){
			reflow();
		}
	}
	
	private Bucket<K, V> putEntry(IOList<Bucket<K, V>> buckets, short bucketPO2, K key, V value) throws IOException{
		
		Bucket<K, V> bucket=getBucket(buckets, key, bucketPO2);
		
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
	
	private Bucket<K, V> getBucket(IOList<Bucket<K, V>> buckets, K key, short bucketPO2) throws IOException{
		int smallHash=toSmallHash(key, bucketPO2);
		return getBySmallHash(buckets, smallHash);
	}
	private Bucket<K, V> getBySmallHash(IOList<Bucket<K, V>> buckets, int smallHash) throws IOException{
		return buckets.get(smallHash);
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
