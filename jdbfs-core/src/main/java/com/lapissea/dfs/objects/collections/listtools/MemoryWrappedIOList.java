package com.lapissea.dfs.objects.collections.listtools;

import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.function.Supplier;

public class MemoryWrappedIOList<T> implements IOList<T>{
	
	private final List<T>     data;
	private final Supplier<T> typeConstructor;
	private       Class<T>    elementType;
	
	public MemoryWrappedIOList(List<T> data, Supplier<T> typeConstructor){
		this.data = data;
		this.typeConstructor = typeConstructor;
		
		Class<?> c;
		if(typeConstructor != null) c = typeConstructor.get().getClass();
		else c = Iters.from(data).firstMatching(Objects::nonNull).map(Object::getClass).orElse(null);
		elementType = (Class<T>)c;
	}
	
	@Override
	public Class<T> elementType(){
		if(elementType == null) elementType = (Class<T>)data.stream().filter(Objects::nonNull).findAny().map(Object::getClass).orElseThrow();
		return elementType;
	}
	
	@Override
	public long size(){
		return data.size();
	}
	@Override
	public T get(long index){
		return data.get(Math.toIntExact(index));
	}
	
	@Override
	public void set(long index, T value){
		data.set(Math.toIntExact(index), value);
	}
	
	@Override
	public void add(long index, T value){
		data.add(Math.toIntExact(index), value);
	}
	
	@Override
	public void add(T value){
		data.add(value);
	}
	
	@Override
	public void remove(long index){
		data.remove(Math.toIntExact(index));
	}
	
	@Override
	public T addNew(UnsafeConsumer<T, IOException> initializer) throws IOException{
		if(typeConstructor == null) throw new UnsupportedEncodingException();
		T t = typeConstructor.get();
		if(initializer != null){
			initializer.accept(t);
		}
		add(t);
		return t;
	}
	
	@Override
	public void addMultipleNew(long count, UnsafeConsumer<T, IOException> initializer) throws IOException{
		if(typeConstructor == null) throw new UnsupportedEncodingException();
		if(data instanceof ArrayList<T> l) l.ensureCapacity(Math.toIntExact(l.size() + count));
		for(long i = 0; i<count; i++){
			addNew(initializer);
		}
	}
	
	@Override
	public void clear(){
		data.clear();
	}
	
	@Override
	public void requestCapacity(long capacity){
		if(data instanceof ArrayList<T> al){
			al.ensureCapacity(Math.toIntExact(capacity));
		}
	}
	
	@Override
	public void trim(){
		if(data instanceof ArrayList<T> al){
			al.trimToSize();
		}
	}
	
	@Override
	public long getCapacity(){
		return Integer.MAX_VALUE;
	}
	@Override
	public void free(long index){
		throw new UnsupportedOperationException();
	}
	
	@Override
	public String toString(){
		StringJoiner sj = new StringJoiner(", ", "RAM{size: " + size() + "}" + "[", "]");
		IOList.elementSummary(sj, this);
		return sj.toString();
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public boolean equals(Object obj){
		return obj == this ||
		       obj instanceof IOList<?> l && IOList.elementsEqual(this, (IOList<T>)l);
	}
}
