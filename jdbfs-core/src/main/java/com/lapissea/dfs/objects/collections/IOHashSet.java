package com.lapissea.dfs.objects.collections;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.core.AllocateTicket;
import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;
import com.lapissea.dfs.internal.HashCommons;
import com.lapissea.dfs.io.ValueStorage;
import com.lapissea.dfs.type.IOType;
import com.lapissea.dfs.type.field.annotations.IOValue;

import java.io.IOException;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.StringJoiner;

public final class IOHashSet<T> extends UnmanagedIOSet<T>{
	
	@IOValue
	private ContiguousIOList<IONode<T>> data;
	
	public IOHashSet(DataProvider provider, Chunk identity, IOType typeDef) throws IOException{
		super(provider, identity, ((IOType.RawAndArg)typeDef).withArgs(IOType.TypeRaw.OBJ));
		
		if(isSelfDataEmpty()){
			allocateNulls();
			data.addMultipleNew(2);
			writeManagedFields();
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
		
		if(size()>=width){
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
			
			var b2 = data.getPointer().dereference(provider).chainLength()*2;
			var chunk = AllocateTicket.bytes(b2)
			                          .withPositionMagnet(getPointer().getValue())
			                          .submit(provider);
			var newData  = new ContiguousIOList<IONode<T>>(getDataProvider(), chunk, data.getTypeDef());
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
			var genericType = IOType.getArg(getTypeDef(), 0);
			var type        = IOType.of(IONode.class, genericType);
			provider.getTypeDb().toID(type);
			
			var ctx = getGenerics().argAsContext("T");
			@SuppressWarnings("unchecked")
			var valueStorage = (ValueStorage<T>)ValueStorage.makeStorage(provider, genericType, ctx, new ValueStorage.StorageRule.Default());
			var magnet = OptionalLong.of(data.getPointer().getValue());
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
	
	@Override
	public void clear() throws IOException{
		if(isEmpty()) return;
		deltaSize(-size());
		var oldData = data;
		data = null;
		allocateNulls();
		data.addMultipleNew(2);
		writeManagedFields();
		oldData.free();
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
	
	@Override
	public void requestCapacity(long capacity) throws IOException{
		data.requestCapacity(capacity);
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
