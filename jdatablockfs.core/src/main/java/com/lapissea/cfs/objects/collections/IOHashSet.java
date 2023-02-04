package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Chunk;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.internal.HashCommons;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.io.ValueStorage;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.LogUtil;

import java.io.IOException;
import java.util.*;

public final class IOHashSet<T> extends IOInstance.Unmanaged<IOHashSet<T>> implements IOSet<T>{
	
	private static int idx(Random r, List<Chunk> ch){
		int index = r.nextInt(ch.size());
		for(int i = 0; i<ch.size(); i++){
			var j = (i + index)%ch.size();
			if(ch.get(j) != null){
				return j;
			}
		}
		return -1;
	}
	
	public static void alocFuzz() throws IOException{
		var provider = Cluster.emptyMem();
		
		List<Chunk> chs = new ArrayList<>();
		var         r   = new Random(69);
		a:
		for(int i = 0; i<5000000; i++){
			if(i%100000 == 0) LogUtil.println(i/5000000D, chs.size(), provider.getMemoryManager().getFreeChunks().size());
			if(chs.isEmpty() || r.nextBoolean()){
				var t = AllocateTicket.bytes(r.nextInt(50));
				if(r.nextBoolean()){
					t = t.withPositionMagnet(provider.getSource().getIOSize());
				}
				for(int i1 = 0; i1<chs.size(); i1++){
					if(chs.get(i1) == null){
						chs.set(i1, t.submit(provider));
						continue a;
					}
				}
				chs.add(t.submit(provider));
			}else{
				var id = idx(r, chs);
				var ch = chs.get(id);
				if(id == chs.size() - 1){
					chs.remove(id);
					while(!chs.isEmpty() && chs.get(chs.size() - 1) == null) chs.remove(chs.size() - 1);
				}else chs.set(id, null);
				
				provider.getMemoryManager().free(ch);
			}
		}
	}
	public static void main(String[] args) throws IOException{
		var provider = Cluster.emptyMem();
		
		var set = provider.getRootProvider().<IOHashSet<Object>>request("hi", IOHashSet.class);
		
		var r    = new Random(69);
		var iter = 50000000;
		for(int i = 0; i<iter; i++){
			if(i%100000 == 0) LogUtil.println(i/((double)iter));
			Integer num = r.nextInt(400);
			
			switch(r.nextInt(3)){
				case 0 -> {
					var added1 = set.add(num);
				}
				case 1 -> {
					var removed1 = set.remove(num);
				}
				case 2 -> {
					var c1 = set.contains(num);
				}
			}
		}
	}
	
	
	@IOValue
	private ContiguousIOList<IONode<T>> data;
	
	@IOValue
	private long size;
	
	public IOHashSet(DataProvider provider, Reference reference, TypeLink typeDef) throws IOException{
		super(provider, reference, typeDef.argCount() == 0? typeDef.withArgs(TypeLink.of(Object.class)) : typeDef);
		
		if(isSelfDataEmpty()){
			allocateNulls();
			writeManagedFields();
			
			data.addMultipleNew(2);
		}
		
		readManagedFields();
	}
	
	private record AddPlace<T>(long index, IONode<T> node){
		private AddPlace{
			boolean hasIndex = index != -1;
			boolean hasNode  = node != null;
			if(hasIndex == hasNode){
				throw new IllegalArgumentException(index + " " + node);
			}
		}
	}
	
	private static <T> AddPlace<T> findAddPlace(IOList<IONode<T>> data, T value) throws IOException{
		int hash  = HashCommons.toHash(value);
		var width = data.size();
		
		long      emptyIndex = -1;
		IONode<T> strayNext  = null;
		
		for(int i = 0; i<HashCommons.HASH_GENERATIONS; i++){
			var index    = smallHash(hash, width);
			var rootNode = data.get(index);
			if(rootNode == null){
				if(emptyIndex == -1){
					emptyIndex = index;
					strayNext = null;
				}
			}else{
				IONode<T> last = null;
				for(var node : rootNode){
					var v = node.getValue();
					if(Objects.equals(value, v)){
						return null;
					}
					last = node;
				}
				if(emptyIndex == -1 && strayNext == null){
					strayNext = last;
				}
			}
			
			hash = HashCommons.h2h(hash);
		}
		
		return new AddPlace<>(emptyIndex, strayNext);
	}
	
	private static <T> void applyAdd(IOList<IONode<T>> data, AddPlace<T> place, IONode<T> toAdd) throws IOException{
		if(place.index != -1){
			data.set(place.index, toAdd);
		}else{
			place.node.setNext(toAdd);
		}
	}
	
	@Override
	public boolean add(T value) throws IOException{
		var width = data.size();
		
		var place = findAddPlace(data, value);
		if(place == null) return false;
		
		if(size>=width){
			grow();
			return add(value);
		}
		
		var node = allocByValue(value);
		
		try(var ignored = data.getDataProvider().getSource().openIOTransaction()){
			applyAdd(data, place, node);
			deltaSize(1);
		}
		return true;
	}
	
	private void grow() throws IOException{
		var provider = getDataProvider();
		try(var ignored = provider.getSource().openIOTransaction()){
			
			var b2 = data.getReference().ioMap(provider, RandomIO::remaining)*2;
			var chunk = AllocateTicket.bytes(b2)
			                          .withPositionMagnet(getReference().calcGlobalOffset(provider))
			                          .submit(provider);
			var newData  = new ContiguousIOList<IONode<T>>(getDataProvider(), chunk.getPtr().makeReference(), data.getTypeDef());
			var oldWidth = data.size();
			var newWidth = oldWidth*2;
			newData.addMultipleNew(newWidth);
			
			for(var rootNode : data){
				if(rootNode == null) continue;
				
				var nodes = rootNode.iterator().toList();
				
				for(var node : nodes){
					node.setNext(null);
					
					var value = node.getValue();
					
					var place = findAddPlace(newData, value);
					Objects.requireNonNull(place);
					
					applyAdd(newData, place, node);
				}
			}
			
			var oldData = data;
			data = newData;
			writeManagedFields();
			oldData.clear();
			oldData.free();
		}
	}
	
	private IONode<T> allocByValue(T value) throws IOException{
		var provider = getDataProvider();
		try(var ignored = provider.getSource().openIOTransaction()){
			var genericType = getTypeDef().arg(0);
			var type        = new TypeLink(IONode.class, genericType);
			provider.getTypeDb().toID(type);
			
			var valueStorage = (ValueStorage<T>)ValueStorage.makeStorage(provider, genericType, getGenerics(), new ValueStorage.StorageRule.Default());
			var magnet       = OptionalLong.of(data.getReference().calcGlobalOffset(provider));
			return IONode.allocValNode(value, null, valueStorage.getSizeDescriptor(), type, provider, magnet);
		}
	}
	
	@Override
	public boolean remove(T value) throws IOException{
		int hash  = HashCommons.toHash(value);
		var width = data.size();
		
		for(int i = 0; i<HashCommons.HASH_GENERATIONS; i++){
			var index    = smallHash(hash, width);
			var rootNode = data.get(index);
			check:
			if(rootNode != null){
				var next = rootNode.getNext();
				if(Objects.equals(value, rootNode.getValue())){
					try(var ignored = getDataProvider().getSource().openIOTransaction()){
						data.set(index, next);
						deltaSize(-1);
					}
					rootNode.setNext(null);
					rootNode.free();
					return true;
				}
				if(next == null) break check;
				IONode<T> last = rootNode;
				for(var node : next){
					if(Objects.equals(value, node.getValue())){
						try(var ignored = getDataProvider().getSource().openIOTransaction()){
							last.setNext(node.getNext());
							deltaSize(-1);
						}
						node.setNext(null);
						node.free();
						return true;
					}
					last = node;
				}
			}
			
			hash = HashCommons.h2h(hash);
		}
		
		return false;
	}
	private void deltaSize(int delta) throws IOException{
		size += delta;
		writeManagedFields();
	}
	@Override
	public boolean contains(T value) throws IOException{
		int hash  = HashCommons.toHash(value);
		var width = data.size();
		for(int i = 0; i<HashCommons.HASH_GENERATIONS; i++){
			var node = data.get(smallHash(hash, width));
			
			if(node != null){
				for(IONode<T> tioNode : node){
					if(Objects.equals(value, tioNode.getValue())){
						return true;
					}
				}
			}
			
			hash = HashCommons.h2h(hash);
		}
		
		return false;
	}
	
	@Override
	public long size(){
		return size;
	}
	
	@Override
	public IOIterator<T> iterator(){
		return new IOIterator<>(){
			private final IOIterator<IONode<T>> raw = data.iterator();
			
			private IOIterator<T> bucket;
			
			private boolean next() throws IOException{
				while(raw.hasNext()){
					var n = raw.ioNext();
					if(n == null) continue;
					bucket = n.valueIterator();
					return true;
				}
				return false;
			}
			
			private boolean has() throws IOException{
				return bucket != null && bucket.hasNext();
			}
			
			@Override
			public boolean hasNext() throws IOException{
				var has = has();
				if(!has) has = next();
				return has;
			}
			@Override
			public T ioNext() throws IOException{
				if(!has()) next();
				return bucket.ioNext();
			}
		};
	}
	
	private static long smallHash(int hash, long size){
		return Math.abs(hash)%size;
	}
	
	@Override
	public String toString(){
		try{
			var iter = iterator();
			var j    = new StringJoiner(", ", "{", "}");
			
			while(iter.hasNext()){
				j.add(Utils.toShortString(iter.ioNext()));
			}
			
			return j.toString();
		}catch(Throwable e){
			return "CORRUPTED_SET{" + e.getMessage() + "}";
		}
	}
}
