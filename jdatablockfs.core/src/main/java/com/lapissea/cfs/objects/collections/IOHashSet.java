package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.internal.HashCommons;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.field.annotations.IOValue;

import java.io.IOException;
import java.util.Objects;

public class IOHashSet<T> extends IOInstance.Unmanaged<IOHashSet<T>> implements IOSet<T>{
	
	@IOValue
	private ContiguousIOList<IONode<T>> data;
	
	public IOHashSet(DataProvider provider, Reference reference, TypeLink typeDef) throws IOException{
		super(provider, reference, typeDef);
		if(isSelfDataEmpty()){
			allocateNulls();
			writeManagedFields();
		}
		
	}
	
	@Override
	public boolean add(T value) throws IOException{
		int hash = HashCommons.toHash(value);
		
		long      emptyIndex = -1;
		IONode<T> strayNext  = null;
		
		for(int i = 0; i<HashCommons.HASH_GENERATIONS; i++){
			var index    = Math.abs(hash)&data.size();
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
		
		return true;
	}
	
	private IONode<T> allocByValue(T value){
		return null;
	}
	
	@Override
	public boolean remove(T value) throws IOException{
		int hash = HashCommons.toHash(value);
		
		for(int i = 0; i<HashCommons.HASH_GENERATIONS; i++){
			var index    = Math.abs(hash)&data.size();
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
			var node = data.get(Math.abs(hash)&data.size());
			
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
}
