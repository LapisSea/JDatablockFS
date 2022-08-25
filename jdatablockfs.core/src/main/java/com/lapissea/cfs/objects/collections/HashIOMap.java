package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.io.ValueStorage;
import com.lapissea.cfs.io.impl.MemoryData;
import com.lapissea.cfs.io.instancepipe.ContiguousStructPipe;
import com.lapissea.cfs.io.instancepipe.StructPipe;
import com.lapissea.cfs.logging.Log;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.SizeDescriptor;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOType;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.LogUtil;
import com.lapissea.util.ObjectHolder;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;
import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NULLABLE;
import static com.lapissea.util.PoolOwnThread.async;

public class HashIOMap<K, V> extends AbstractUnmanagedIOMap<K, V>{
	
	@SuppressWarnings({"unchecked"})
	private interface BucketEntry<K, V> extends IOInstance.Def<BucketEntry<K, V>>{
		
		Struct<BucketEntry<Object, Object>>     STRUCT=Struct.of((Class<BucketEntry<Object, Object>>)(Object)BucketEntry.class);
		StructPipe<BucketEntry<Object, Object>> PIPE  =ContiguousStructPipe.of(STRUCT);
		
		static <V, K> BucketEntry<K, V> of(K key, V value){
			var e=(BucketEntry<K, V>)STRUCT.make();
			//TODO: make user ordered constructor
			e.key(key);
			e.value(value);
			return e;
		}
		
		@IONullability(NULLABLE)
		@IOType.Dynamic
		K key();
		void key(K key);
		
		@IONullability(NULLABLE)
		@IOType.Dynamic
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
			if(node==null) return null;
			for(IONode<BucketEntry<K, V>> entry : node){
				var value=entry.getValue();
				if(value!=null&&Objects.equals(value.key(), key)){
					return value;
				}
			}
			
			return null;
		}
		
		public void put(BucketEntry<K, V> entry) throws IOException{
			for(var node : node){
				var e=node.getValue();
				if(e!=null&&Objects.equals(e.key(), entry.key())){
					node.setValue(entry);
					return;
				}
			}
			throw new RuntimeException("bucket entry not found");
		}
		
		private Stream<IONode<BucketEntry<K, V>>> nodeStream(){
			if(node==null) return Stream.of();
			return node.stream();
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
	@IOValue.Unsigned
	private short bucketPO2=1;
	
	@IOValue
	private IOList<Bucket<K, V>> buckets;
	
	private int datasetID;
	
	private final Map<K, IOEntry.Modifiable<K, V>> cache;
	
	
	public HashIOMap(DataProvider provider, Reference reference, TypeLink typeDef) throws IOException{
		super(provider, reference, typeDef);
		cache=readOnly?new HashMap<>():null;
		
		if(!readOnly&&isSelfDataEmpty()){
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
		
		bucketPO2=(short)(oldBucketPO2+1);//calcNewSize(oldBuckets, oldBucketPO2);
		datasetID++;
		
		if(bucketPO2<0) throw new IllegalStateException();
		
		newBuckets();
		fillBuckets(buckets, bucketPO2);
		
		if(size()<512){
			Log.traceCall("method: rewire, size: {}", size());
			try(var ignored=getDataProvider().getSource().openIOTransaction()){
				transferRewire(oldBuckets, buckets, bucketPO2);
				writeManagedFields();
			}
			oldBuckets.clear();
			((Unmanaged<?>)oldBuckets).free();
		}else{
			long pos=0;
			
			boolean disableAsync=size()<=512*RESIZE_TRIGGER*3;
			
			Log.traceCall("method: reallocate and transfer, size: {}, async: {}", size(), !disableAsync);
			
			var semaphore =disableAsync?null:new Semaphore(3);
			var writeTasks=disableAsync?null:Collections.synchronizedList(new LinkedList<Runnable>());
			
			ExecutorService readService=disableAsync?null:new ThreadPoolExecutor(
				2, 2,
				0L, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>()
			);
			
			Executor readExecutor=task->{
				if(disableAsync){
					task.run();
					return;
				}
				readService.execute(()->{
					try{
						semaphore.acquire();
						task.run();
					}catch(InterruptedException e){
						throw new RuntimeException(e);
					}
				});
			}, writeExecutor=task->{
				if(disableAsync){
					task.run();
					return;
				}
				writeTasks.add(task);
			};
			
			Runnable doWrites=disableAsync?()->{}:()->{
				while(!writeTasks.isEmpty()){
					writeTasks.remove(0).run();
					semaphore.release();
				}
			};
			
			while(pos<oldBuckets.size()){
				long from=pos;
				long to  =Math.min(oldBuckets.size(), from+512/RESIZE_TRIGGER);
				pos=to;
				
				var view=oldBuckets.subListView(from, to);
				
				doWrites.run();
				optimizedOrderTransfer(view, buckets, bucketPO2, readExecutor, writeExecutor);
			}
			if(!disableAsync){
				readService.shutdown();
				while(!readService.isTerminated()){
					UtilL.sleep(1);
					doWrites.run();
				}
				doWrites.run();
			}
			
			var nCount=rawNodeStreamWithValues(buckets).count();
			if(nCount!=size()){
				var oldKeys=rawKeyStream(oldBuckets).collect(Collectors.toSet());
				var newKeys=rawKeyStream(buckets).collect(Collectors.toSet());
				if(oldKeys.size()>newKeys.size()){
					oldKeys.removeAll(newKeys);
					throw new IllegalStateException("Failed to transfer keys: "+TextUtil.toShortString(oldKeys));
				}
				LogUtil.println(oldKeys.size(), newKeys.size());
				throw new IllegalStateException(nCount+" "+size());
			}
			
			writeManagedFields();
			((Unmanaged<?>)oldBuckets).free();
		}
	}
	
	private void optimizedOrderTransfer(IOList<Bucket<K, V>> oldData, IOList<Bucket<K, V>> newBuckets, short newPO2, Executor readExecutor, Executor writeExecutor) throws IOException{
		async(()->{
			var hashGroupings=IntStream.range(0, 1<<newPO2)
			                           .mapToObj(i->(ArrayList<IONode<BucketEntry<K, V>>>)null)
			                           .collect(Collectors.toList());
			
			for(var bucket : oldData){
				if(bucket.node==null) continue;
				for(var n : bucket.node){
					int smallHash;
					try{
						smallHash=toSmallHash(n, newPO2);
					}catch(IOException e){
						throw new RuntimeException("failed to run read on "+n.getReference(), e);
					}
					var l=hashGroupings.get(smallHash);
					if(l==null) hashGroupings.set(smallHash, l=new ArrayList<>(RESIZE_TRIGGER));
					l.add(n);
				}
			}
			return hashGroupings;
		}, readExecutor).thenAcceptAsync(hashGroupings->{
			if(hashGroupings.isEmpty()) return;
			
			for(int smallHash=0;smallHash<hashGroupings.size();smallHash++){
				var group=hashGroupings.get(smallHash);
				if(group==null) continue;
				
				try{
					Bucket<K, V> bucket=getBucket(newBuckets, smallHash);
					
					Iterator<IONode<BucketEntry<K, V>>> iter=group.iterator();
					assert iter.hasNext();
					
					var node=bucket.node;
					if(node==null){
						var entry=iter.next().getValue();
						node=allocNewNode(entry, (Unmanaged<?>)newBuckets);
						bucket.node=node;
						setBucket(newBuckets, smallHash, bucket);
					}else{
						node.setValue(iter.next().getValue());
					}
					
					while(iter.hasNext()){
						var newNode=allocNewNode(iter.next().getValue(), node);
						
						node.setNext(newNode);
						node=newNode;
					}
				}catch(Throwable e){
					var e1=new RuntimeException("failed to run copy on "+smallHash, e);
					e1.printStackTrace();
					throw e1;
				}
			}
		}, writeExecutor);
	}
	
	private static IOField<BucketEntry<Object, Object>, ?> keyVar;
	
	private record KeyResult<K>(K key, boolean hasValue){}
	private static <K, V> KeyResult<K> readKey(IONode<BucketEntry<K, V>> n) throws IOException{
		if(keyVar==null){
			//noinspection unchecked
			keyVar=Struct.of((Class<BucketEntry<Object, Object>>)(Object)BucketEntry.class, Struct.STATE_DONE)
			             .getFields()
			             .byName("key")
			             .orElseThrow();
		}
		
		BucketEntry<K, V> be=BucketEntry.of(null, null);
		if(!n.readValueField(be, keyVar)) return new KeyResult<>(null, false);
		return new KeyResult<>(be.key(), true);
	}
	
	private void transferRewire(IOList<Bucket<K, V>> oldBuckets, IOList<Bucket<K, V>> newBuckets, short newPO2) throws IOException{
		for(Bucket<K, V> oldBucket : oldBuckets){
			var oldNodes=oldBucket.nodeStream().toList();
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
	
	private class ModifiableIOEntry extends IOEntry.Modifiable.Abstract<K, V>{
		
		private       int               currentDatasetID=datasetID;
		private       Bucket<K, V>      currentBucket;
		private final BucketEntry<K, V> data;
		
		public ModifiableIOEntry(Bucket<K, V> bucket, BucketEntry<K, V> entry){
			currentBucket=bucket;
			this.data=entry;
		}
		
		@Override
		public K getKey(){return data.key();}
		@Override
		public V getValue(){return data.value();}
		
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
	public IOEntry.Modifiable<K, V> getEntry(K key) throws IOException{
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
			var e=entry.unsupported();
			cache.put(key, e);
			return e;
		}
		return new ModifiableIOEntry(bucket, entry);
	}
	
	@Override
	public Stream<IOEntry<K, V>> stream(){
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
	
	private Stream<IONode<BucketEntry<K, V>>> rawNodeStream(IOList<Bucket<K, V>> buckets){
		return buckets.stream().flatMap(Bucket::nodeStream);
	}
	
	private Stream<IONode<BucketEntry<K, V>>> rawNodeStreamWithValues(IOList<Bucket<K, V>> buckets){
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
		if(size()>=buckets.size()*RESIZE_TRIGGER){
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
		
		getDataProvider().getSource().openIOTransaction(()->{
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
				}
			}
			
			deltaSize(deltaSize);
			return reflow;
		});
		if(size()>=buckets.size()*RESIZE_TRIGGER){
			reflow();
		}
	}
	
	@Override
	public boolean remove(K key) throws IOException{
		var smallHash=toSmallHash(key, bucketPO2);
		var bucket   =getBucket(buckets, smallHash);
		
		if(bucket.node==null){
			return false;
		}
		
		var prevNode=bucket.node;
		for(var node : bucket.node){
			var keyResult=readKey(node);
			if(!keyResult.hasValue) continue;
			if(Objects.equals(keyResult.key, key)){
				if(prevNode==node){
					bucket.node=node.getNext();
					setBucket(buckets, smallHash, bucket);
				}else{
					prevNode.setNext(node.getNext());
				}
				node.free();
				return true;
			}
			prevNode=node;
		}
		return false;
	}
	
	private static final long OVERWRITE      =-1;
	private static final long OVERWRITE_EMPTY=-2;
	
	private long putEntry(IOList<Bucket<K, V>> buckets, short bucketPO2, K key, V value) throws IOException{
		var smallHash=toSmallHash(key, bucketPO2);
		
		Bucket<K, V> bucket=getBucket(buckets, smallHash);
		
		var entry=bucket.getEntryByKey(key);
		if(entry!=null){
			entry.value(value);
			bucket.put(entry);
			return OVERWRITE;
		}
		
		BucketEntry<K, V> newEntry=BucketEntry.of(key, value);
		
		if(bucket.node==null){
			bucket.node=allocNewNode(newEntry, (Unmanaged<?>)buckets);
			setBucket(buckets, smallHash, bucket);
			return OVERWRITE_EMPTY;
		}
		
		long count=0;
		
		IONode<BucketEntry<K, V>> last=bucket.node;
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
		
		
		IONode<BucketEntry<K, V>> newNode=allocNewNode(newEntry, last);
		
		last.setNext(newNode);
		
		return count;
	}
	
	private static final TypeLink BUCKET_NODE_TYPE=new TypeLink(
		IONode.class,
		TypeLink.of(BucketEntry.class)
	);
	
	@SuppressWarnings("unchecked")
	private IONode<BucketEntry<K, V>> allocNewNode(BucketEntry<K, V> newEntry, IOInstance.Unmanaged<?> magnet) throws IOException{
		return IONode.allocValNode(
			newEntry,
			null,
			(SizeDescriptor<BucketEntry<K, V>>)(Object)BucketEntry.PIPE.getSizeDescriptor(),
			BUCKET_NODE_TYPE,
			getDataProvider(),
			OptionalLong.of(magnet.getReference().getPtr().getValue())
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
			if(!read.equals(bucket)) throw new IllegalStateException("Bucket integrity failed:\n"+bucket+"\n"+read);
		}
	}
	
	private int toSmallHash(IONode<BucketEntry<K, V>> entry, short bucketPO2) throws IOException{
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
		var h=key.hashCode();
		return h2h(h);
	}
	private static int h2h(int x){
		long mul=0x5DEECE66DL, mask=0xFFFFFFFFFFFFL;
		return (int)(((((x^mul)&mask)*mul+0xBL)&mask) >>> 17);
	}
}

