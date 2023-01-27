package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.internal.HashCommons;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.LogUtil;

import java.io.IOException;
import java.util.Objects;
import java.util.StringJoiner;

public final class IOHashSet<T> extends IOInstance.Unmanaged<IOHashSet<T>> implements IOSet<T>{
	
	public static void main(String[] args) throws IOException{
		var mem = Cluster.emptyMem();
		
		var map = mem.getRootProvider().<IOHashSet<Object>>request("hi", IOHashSet.class);
		
		for(int i = 0; i<10; i++){
			map.add(i);
			LogUtil.println(i, map);
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
	}
	
	@Override
	public boolean add(T value) throws IOException{
		int hash  = HashCommons.toHash(value);
		var width = data.size();
		
		long      emptyIndex = -1;
		IONode<T> strayNext  = null;
		
		for(int i = 0; i<HashCommons.HASH_GENERATIONS; i++){
			var index    = smallHash(hash, width);
			var rootNode = data.get(index);
			if(rootNode == null){
				if(emptyIndex == -1) emptyIndex = index;
			}else{
				IONode<T> last = null;
				for(var node : rootNode){
					if(Objects.equals(value, rootNode.getValue())){
						return false;
					}
					last = node;
				}
				if(emptyIndex == -1 && strayNext == null){
					strayNext = last;
				}
			}
			
			hash = HashCommons.h2h(hash);
		}
		
		if(size>=width){
			grow();
			return add(value);
		}
		
		IONode<T> newNode = allocByValue(value);
		if(emptyIndex != -1){
			data.set(emptyIndex, newNode);
		}else{
			assert strayNext != null;
			strayNext.setNext(newNode);
		}
		size++;
		writeManagedFields();
		return true;
	}
	
	private void grow() throws IOException{
		var provider = getDataProvider();
		var b2       = data.getReference().ioMap(provider, RandomIO::remaining)*2;
		var chunk = AllocateTicket.bytes(b2)
		                          .withPositionMagnet(getReference().calcGlobalOffset(provider))
		                          .submit(provider);
		var newData  = new ContiguousIOList<IONode<T>>(getDataProvider(), chunk.getPtr().makeReference(), data.getTypeDef());
		var oldWidth = data.size();
		var newWidth = oldWidth*2;
		newData.addMultipleNew(newWidth);
		
		for(var rootNode : data){
			if(rootNode == null) continue;
			values:
			for(var node : rootNode){
				var value = node.getValue();
				
				IONode<T> strayNext = null;
				
				var hash = HashCommons.toHash(value);
				
				for(int i = 0; i<HashCommons.HASH_GENERATIONS; i++){
					var index       = smallHash(hash, newWidth);
					var rootNodeNew = newData.get(index);
					
					if(rootNodeNew == null){
						newData.set(index, allocByValue(value));
						continue values;
					}else{
						if(strayNext != null) continue;
						strayNext = rootNodeNew.getLast();
					}
					
					hash = HashCommons.h2h(hash);
				}
				
				strayNext.setNext(allocByValue(value));
			}
		}
		
		data = newData;
		writeManagedFields();
	}
	
	private IONode<T> allocByValue(T value) throws IOException{
		var provider = getDataProvider();
		try(var ignored = provider.getSource().openIOTransaction()){
			var type = new TypeLink(IONode.class, getTypeDef().arg(0));
			provider.getTypeDb().toID(type);
			var chunk = AllocateTicket.bytes(4)
			                          .withPositionMagnet(getReference().calcGlobalOffset(provider))
			                          .submit(provider);
			return new IONode<>(provider, chunk.getPtr().makeReference(), type, value, null);
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
					data.set(index, next);
					rootNode.free();
					return true;
				}
				if(next == null) break check;
				IONode<T> last = rootNode;
				for(var node : next){
					if(Objects.equals(value, node.getValue())){
						last.setNext(node.getNext());
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
	
	private long smallHash(int hash, long size){
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
