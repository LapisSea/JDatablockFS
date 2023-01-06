package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.objects.Reference;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.TypeLink;
import com.lapissea.cfs.type.field.annotations.IONullability;
import com.lapissea.cfs.type.field.annotations.IOValue;

import java.io.IOException;
import java.util.Objects;

import static com.lapissea.cfs.objects.collections.HashIOMap.HASH_GENERATIONS;
import static com.lapissea.cfs.type.field.annotations.IONullability.Mode.NULLABLE;

public class IOHashSet<T> extends IOInstance.Unmanaged<IOHashSet<T>> implements IOSet<T>{
	
	private static class ValueNode<T> extends IOInstance.Managed<ValueNode<T>>{
		@IOValue
		private boolean exists;
		
		@IOValue
		@IONullability(NULLABLE)
		@IOValue.Reference
		private T value;
		
		public ValueNode(){ }
		public ValueNode(T value){
			this.value = value;
			exists = true;
		}
	}
	
	@IOValue
	private ContiguousIOList<ValueNode<T>> data;
	
	public IOHashSet(DataProvider provider, Reference reference, TypeLink typeDef) throws IOException{
		super(provider, reference, typeDef);
		if(isSelfDataEmpty()){
			allocateNulls();
			writeManagedFields();
		}
		
	}
	
	
	@Override
	public boolean add(T value) throws IOException{
		int hash = HashIOMap.toHash(value);
		
		for(int i = 0; i<HASH_GENERATIONS; i++){
			var index = Math.abs(hash)&data.size();
			var val   = data.get(index);
			
			if(val.exists){
				if(Objects.equals(value, val.value)) return false;
				continue;
			}
			
			hash = HashIOMap.h2h(hash);
		}
		return false;
	}
	
	@Override
	public boolean remove(T value) throws IOException{
		int hash = HashIOMap.toHash(value);
		
		for(int i = 0; i<HASH_GENERATIONS; i++){
			var index = Math.abs(hash)&data.size();
			var val   = data.get(index);
			
			if(val.exists && Objects.equals(value, val.value)){
				data.set(index, new ValueNode<>());
				return true;
			}
			
			hash = HashIOMap.h2h(hash);
		}
		return false;
	}
	@Override
	public boolean contains(T value) throws IOException{
		int hash = HashIOMap.toHash(value);
		
		for(int i = 0; i<HASH_GENERATIONS; i++){
			var val = data.get(Math.abs(hash)&data.size());
			
			if(val.exists && Objects.equals(value, val.value)){
				return true;
			}
			
			hash = HashIOMap.h2h(hash);
		}
		return false;
	}
	
	@Override
	public IOIterator<T> iterator(){
		return data.query("exists == true").<T>map("value").all();
	}
}
