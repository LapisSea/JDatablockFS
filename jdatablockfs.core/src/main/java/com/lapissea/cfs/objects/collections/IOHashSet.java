package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.chunk.AllocateTicket;
import com.lapissea.cfs.chunk.Cluster;
import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.internal.HashCommons;
import com.lapissea.cfs.io.RandomIO;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.field.annotations.IOValue;

import java.io.IOException;
import java.util.Objects;

public final class IOHashSet<T> extends IOInstance.Unmanaged<IOHashSet<T>> implements IOSet<T>{
	
	public static void main(String[] args) throws IOException{
		var mem = Cluster.emptyMem();
		
		var map = mem.getRootProvider().<IOHashSet<Object>>request("hi", IOHashSet.class);
		
		map.add(1);
		map.add(2);
		map.add(1);
		
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
			
			int count = 2;
			data.requestCapacity(count);
			for(int i = 0; i<count; i++){
				data.add(null);
			}
		}
	}
	
	@Override
	public boolean add(T value) throws IOException{
		if(size>data.size()) grow();
		
		int hash = HashCommons.toHash(value);
		
		long      emptyIndex = -1;
		IONode<T> strayNext  = null;
		
		for(int i = 0; i<HashCommons.HASH_GENERATIONS; i++){
			var index    = smallHash(hash);
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
				if(strayNext == null){
					strayNext = last;
				}
			}
			
			hash = HashCommons.h2h(hash);
		}
		
		IONode<T> newNode = allocByValue(value);
		if(emptyIndex == -1){
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
		var siz      = data.getReference().ioMap(provider, RandomIO::remaining)*2;
		var chunk = AllocateTicket.bytes(siz)
		                          .withPositionMagnet(getReference().calcGlobalOffset(provider))
		                          .submit(provider);
		var newData = new ContiguousIOList<IONode<T>>(getDataProvider(), chunk.getPtr().makeReference(), data.getTypeDef());
		
		for(var rootNode : data){
			if(rootNode == null) continue;
			values:
			for(var node : rootNode){
				var value = node.getValue();
				
				IONode<T> strayNext = null;
				
				var hash = HashCommons.toHash(value);
				
				for(int i = 0; i<HashCommons.HASH_GENERATIONS; i++){
					var index       = smallHash(hash);
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
		int hash = HashCommons.toHash(value);
		
		for(int i = 0; i<HashCommons.HASH_GENERATIONS; i++){
			var index    = smallHash(hash);
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
		int hash = HashCommons.toHash(value);
		
		for(int i = 0; i<HashCommons.HASH_GENERATIONS; i++){
			var node = data.get(smallHash(hash));
			
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
		return data.query("exists == true").<T>map("value").all();
	}
	
	private long smallHash(int hash){
		return Math.abs(hash)%data.size();
	}
	
	@Override
	public String toString(){
		return data.toString();
	}
}
