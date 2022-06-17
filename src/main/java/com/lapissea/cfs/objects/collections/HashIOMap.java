package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.ValueStorage;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOType;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ObjectHolder;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NULLABLE;
import static com.lapissea.util.PoolOwnThread.async;

public class HashIOMap<K, V> extends AbstractUnmanagedIOMap<K, V>{
	
	private static class BucketEntry<K, V> extends IOInstance<BucketEntry<K, V>>{
		
		@SuppressWarnings("unchecked")
		private static final StructPipe<BucketEntry<Object, Object>> PIPE=ContiguousStructPipe.of((Class<BucketEntry<Object, Object>>)(Object)BucketEntry.class);
		
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
			return "{"+Utils.toShortString(key)+" = "+Utils.toShortString(value)+"}";
		}
		
		private Entry<K, V> unmodifiable;
		
		public BucketEntry(){}
		public BucketEntry(K key, V value){
			this.key=key;
			this.value=value;
		}
		
		public Entry<K, V> unmodifiable(){
			if(unmodifiable==null){
				unmodifiable=Entry.of(key, value);
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
		
		private Stream<LinkedIOList.Node<BucketEntry<K, V>>> nodeStream(){
			if(node==null) return Stream.of();
			return UtilL.stream(node.iterator());
		}
		public BucketEntry<K, V> getEntryByKey(K key) throws IOException{
			if(node==null) return null;
			for(var entry : node){
				var k=readKey(entry);
				if(!k.hasValue) continue;
				if(Objects.equals(k.key, key)){
					return entry.getValue();
				}
			}
			
			return null;
		}
		
		@Override
		public Iterator<BucketEntry<K, V>> iterator(){
			if(node==null) return Collections.emptyIterator();
			return node.valueIterator();
		}
		
		@Override
		public String toString(){
			return "Bucket{"+node+"}";
		}
	}
	
	private static final int RESIZE_TRIGGER=4;
	
	@IOValue
	private short bucketPO2=1;
	
	@IOValue
	private IOList<Bucket<K, V>> buckets;
	
	private int datasetID;
	
	private final Map<K, Entry<K, V>> cache;
	
	
	public HashIOMap(DataProvider provider, Reference reference, TypeLink typeDef) throws IOException{
		super(provider, reference, typeDef);
		cache=readOnly?new HashMap<>():null;
		
		if(isSelfDataEmpty()&&!readOnly){
			newBuckets();
			writeManagedFields();
			fillBuckets(buckets, bucketPO2);
		}
		readManagedFields();
	}
	
	private short calcNewSize(IOList<Bucket<K, V>> buckets, short bucketPO2){
		Map<Integer, ObjectHolder<Integer>> counts=new HashMap<>();
		
		short newBucketPO2=bucketPO2;
		
		int[] hashes=rawKeyStream(buckets).mapToInt(this::toHash).parallel().toArray();
		
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
	
	
	private void fillBuckets(IOList<Bucket<K, V>> buckets, short bucketPO2) throws IOException{
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
		datasetID++;
		
		newBuckets();
		fillBuckets(buckets, bucketPO2);
		
		if(size()<512){
			try(var ignored=getDataProvider().getSource().openIOTransaction()){
				transferRewire(oldBuckets, buckets, bucketPO2);
				writeManagedFields();
			}
			oldBuckets.clear();
			((Unmanaged<?>)oldBuckets).free();
		}else{
			long pos=0;
			
			List<Iterable<LinkedIOList.Node<BucketEntry<K, V>>>> sources=new ArrayList<>();
			ExecutorService                                      service=Executors.newSingleThreadExecutor();
			while(pos<oldBuckets.size()){
				long from=pos;
				long to  =Math.min(oldBuckets.size(), from+256/RESIZE_TRIGGER);
				pos=to;
				
				var view=oldBuckets.subListView(from, to);
				if(false){
					sources.add(asyncNodeSource(service, view));
				}else{
					sources.add(nodeSource(view));
				}
			}
			
			for(int i=0;i<sources.size();i++){
				var source=sources.get(i);
				try{
					optimizedOrderTransfer(source, buckets, bucketPO2);
				}catch(Throwable e){
					throw new RuntimeException("failed to copy on chunk "+i, e);
				}
			}
			service.shutdown();
			writeManagedFields();
			((Unmanaged<?>)oldBuckets).free();
		}
	}
	
	private Iterable<LinkedIOList.Node<BucketEntry<K, V>>> nodeSource(IOList<Bucket<K, V>> view){
		return ()->view.stream().filter(b->b.node!=null).flatMap(b->b.node.stream()).iterator();
	}
	private Iterable<LinkedIOList.Node<BucketEntry<K, V>>> asyncNodeSource(ExecutorService service, IOList<Bucket<K, V>> view){
		LinkedList<LinkedIOList.Node<BucketEntry<K, V>>> nodeBuffer=new LinkedList<>();
		var task=async(()->{
			for(var bucket : view){
				if(bucket.node==null) continue;
				for(var node : bucket.node){
					synchronized(nodeBuffer){
						nodeBuffer.add(node);
					}
				}
			}
		}, service);
		
		return ()->new Iterator<>(){
			private LinkedIOList.Node<BucketEntry<K, V>> next;
			@Override
			public boolean hasNext(){
				if(next==null){
					if(task.isDone()){
						if(task.isCompletedExceptionally()){
							task.join();
						}
						if(nodeBuffer.isEmpty()) return false;
						else{
							next=nodeBuffer.remove(0);
							return true;
						}
					}
					getNext();
				}
				return next!=null;
			}
			private void getNext(){
				while(true){
					if(task.isDone()){
						if(task.isCompletedExceptionally()){
							task.join();
						}
						break;
					}
					if(!nodeBuffer.isEmpty()) break;
					UtilL.sleep(1);
				}
				synchronized(nodeBuffer){
					if(!nodeBuffer.isEmpty()){
						next=nodeBuffer.remove(0);
					}
				}
			}
			
			@Override
			public LinkedIOList.Node<BucketEntry<K, V>> next(){
				if(next==null) getNext();
				if(next==null) throw new NotImplementedException();
				var n=next;
				next=null;
				return n;
			}
		};
	}
	
	private void optimizedOrderTransfer(Iterable<LinkedIOList.Node<BucketEntry<K, V>>> oldData, IOList<Bucket<K, V>> newBuckets, short newPO2) throws IOException{
		var hashGroupings=IntStream.range(0, 1<<newPO2)
		                           .mapToObj(i->(ArrayList<LinkedIOList.Node<BucketEntry<K, V>>>)null)
		                           .collect(Collectors.toList());
		
		int min=hashGroupings.size(), max=0;
		for(var n : oldData){
			var smallHash=toSmallHash(n, newPO2);
			var l        =hashGroupings.get(smallHash);
			if(l==null) hashGroupings.set(smallHash, l=new ArrayList<>(RESIZE_TRIGGER));
			l.add(n);
			min=Math.min(min, smallHash);
			max=Math.max(max, smallHash+1);
		}
		if(max<min) return;
		hashGroupings=hashGroupings.subList(min, max);
		
		for(int smallHash=0;smallHash<hashGroupings.size();smallHash++){
			var group=hashGroupings.get(smallHash);
			if(group==null) continue;
			
			try{
				Bucket<K, V> bucket=getBucket(newBuckets, smallHash);
				if(bucket.node==null){
					bucket.allocateNulls(getDataProvider());
					setBucket(newBuckets, smallHash, bucket);
				}
				
				Iterator<LinkedIOList.Node<BucketEntry<K, V>>> iter=group.iterator();
				assert iter.hasNext();
				
				var node=bucket.node;
				node.setValue(iter.next().getValue());
				
				while(iter.hasNext()){
					var newNode=allocNewNode(iter.next().getValue());
					
					node.setNext(newNode);
					node=newNode;
				}
			}catch(Throwable e){
				throw new RuntimeException("failed to run copy on "+smallHash, e);
			}
		}
	}
	
	private static IOField<BucketEntry<Object, Object>, ?> keyVar;
	
	private record KeyResult<K>(K key, boolean hasValue){}
	private static <K, V> KeyResult<K> readKey(LinkedIOList.Node<BucketEntry<K, V>> n) throws IOException{
		if(keyVar==null){
			//noinspection unchecked
			keyVar=Struct.of((Class<BucketEntry<Object, Object>>)(Object)BucketEntry.class, Struct.STATE_DONE)
			             .getFields()
			             .byName("key")
			             .orElseThrow();
		}
		
		BucketEntry<K, V> be=new BucketEntry<>();
		if(!n.readValueField(be, keyVar)) return new KeyResult<>(null, false);
		return new KeyResult<>(be.key, true);
	}
	
	private void transferRewire(IOList<Bucket<K, V>> oldBuckets, IOList<Bucket<K, V>> newBuckets, short newPO2) throws IOException{
		for(Bucket<K, V> oldBucket : oldBuckets){
			var oldNodes=oldBucket.node.stream().toList();
			for(var node : oldNodes){
				if(node.hasNext()) node.setNext(null);
				
				if(!node.hasValue()){
					node.free();
					continue;
				}
				
				var smallHash=toSmallHash(node, newPO2);
				
				Bucket<K, V> bucket=getBucket(newBuckets, smallHash);
				if(bucket.node==null){
					bucket.node=node;
					setBucket(newBuckets, smallHash, bucket);
					continue;
				}
				
				var last=bucket.node;
				while(true){
					if(!last.hasValue()){
						throw new RuntimeException();
					}
					var next=last.getNext();
					if(next==null) break;
					last=next;
				}
				last.setNext(node);
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
		if(readOnly){
			if(cache.containsKey(key)){
				return cache.get(key);
			}
		}
		Bucket<K, V> bucket=getBucket(buckets, key, bucketPO2);
		if(bucket==null){
			if(readOnly) cache.put(key, null);
			return null;
		}
		
		var entry=bucket.entry(key);
		if(entry==null){
			if(readOnly) cache.put(key, null);
			return null;
		}
		
		if(readOnly){
			var e=entry.unmodifiable();
			cache.put(key, e);
			return e;
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
		return rawNodeStreamWithValues(buckets).map(e->{
			try{
				return e.getValue();
			}catch(IOException ex){
				throw UtilL.uncheckedThrow(ex);
			}
		});
	}
	
	private Stream<K> rawKeyStream(IOList<Bucket<K, V>> buckets){
		return rawNodeStream(buckets)
			       .map(e->{
				       try{
					       return readKey(e);
				       }catch(IOException ex){
					       throw UtilL.uncheckedThrow(ex);
				       }
			       })
			       .filter(KeyResult::hasValue)
			       .map(KeyResult::key);
	}
	
	private Stream<LinkedIOList.Node<BucketEntry<K, V>>> rawNodeStream(IOList<Bucket<K, V>> buckets){
		return buckets.stream().flatMap(Bucket::nodeStream);
	}
	
	private Stream<LinkedIOList.Node<BucketEntry<K, V>>> rawNodeStreamWithValues(IOList<Bucket<K, V>> buckets){
		return rawNodeStream(buckets).filter(e->{
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
		long sizeFlag=putEntry(buckets, bucketPO2, key, value);
		if(sizeFlag==OVERWRITE) return;
		
		deltaSize(1);
		if(sizeFlag==OVERWRITE_EMPTY) return;
		if(sizeFlag>RESIZE_TRIGGER){
			reflow();
		}
	}
	
	private void checkValue(V value){
		if(!(value instanceof IOInstance.Unmanaged)){
			try{
				var d=MemoryData.builder().build();
				var v=ValueStorage.makeStorage(DataProvider.newVerySimpleProvider(d), TypeLink.of(value.getClass()), getGenerics(), false);
				//noinspection unchecked
				((ValueStorage<V>)v).write(d.io(), value);
			}catch(Throwable e){
				String valStr;
				try{
					valStr=value.toString();
				}catch(Throwable e1){
					valStr="<"+value.getClass().getSimpleName()+" failed toString: "+e1.getMessage()+">";
				}
				throw new IllegalArgumentException(valStr+" is not a valid value!", e);
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
		
		Map<Integer, List<Map.Entry<K, V>>> sorted=new HashMap<>();
		
		for(Map.Entry<K, V> kvEntry : values.entrySet()){
			sorted.computeIfAbsent(toSmallHash(kvEntry.getKey(), bucketPO2), i->new ArrayList<>()).add(kvEntry);
		}
		
		var shouldReflow=getDataProvider().getSource().openIOTransaction(()->{
			boolean reflow   =false;
			long    deltaSize=0;
			for(var group : sorted.values()){
				for(Map.Entry<K, V> e : group){
					var key  =e.getKey();
					var value=e.getValue();
					
					long sizeFlag;
					sizeFlag=putEntry(buckets, bucketPO2, key, value);
					if(sizeFlag==OVERWRITE) continue;
					deltaSize++;
					if(sizeFlag>RESIZE_TRIGGER) reflow=true;
				}
			}
			
			deltaSize(deltaSize);
			return reflow;
		});
		if(shouldReflow){
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
			
			assert !bucket.node.hasValue();
			bucket.node.setValue(newEntry);
			return OVERWRITE_EMPTY;
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
	
	private static final TypeLink BUCKET_NODE_TYPE=new TypeLink(
		LinkedIOList.Node.class,
		TypeLink.of(BucketEntry.class)
	);
	
	@SuppressWarnings("unchecked")
	private LinkedIOList.Node<BucketEntry<K, V>> allocNewNode(BucketEntry<K, V> newEntry) throws IOException{
		return LinkedIOList.Node.allocValNode(
			newEntry,
			null,
			(SizeDescriptor<BucketEntry<K, V>>)(Object)BucketEntry.PIPE.getSizeDescriptor(),
			BUCKET_NODE_TYPE,
			getDataProvider()
		);
	}
	
	
	private Bucket<K, V> getBucket(IOList<Bucket<K, V>> buckets, K key, short bucketPO2) throws IOException{
		int smallHash=toSmallHash(key, bucketPO2);
		return getBucket(buckets, smallHash);
	}
	private Bucket<K, V> getBucket(IOList<Bucket<K, V>> buckets, int smallHash) throws IOException{
		return buckets.get(smallHash);
	}
	private void setBucket(IOList<Bucket<K, V>> buckets, K key, short bucketPO2, Bucket<K, V> bucket) throws IOException{
		int smallHash=toSmallHash(key, bucketPO2);
		setBucket(buckets, smallHash, bucket);
	}
	private void setBucket(IOList<Bucket<K, V>> buckets, int smallHash, Bucket<K, V> bucket) throws IOException{
		buckets.set(smallHash, bucket);
		if(DEBUG_VALIDATION){
			var read=buckets.get(smallHash);
			assert read.equals(bucket);
		}
	}
	
	private int toSmallHash(LinkedIOList.Node<BucketEntry<K, V>> entry, short bucketPO2) throws IOException{
		int hash=toHash(readKey(entry).key);
		return hashToSmall(hash, bucketPO2);
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
