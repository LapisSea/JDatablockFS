package com.lapissea.cfs.objects.collections.listtools;

import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.function.Supplier;

public class MemoryWrappedIOList<T> implements IOList<T>{
	
	private final List<T>     data;
	private final Supplier<T> typeConstructor;
	
	public MemoryWrappedIOList(List<T> data, Supplier<T> typeConstructor){
		this.data=data;
		this.typeConstructor=typeConstructor;
	}
	
	@Override
	public long size(){
		return data.size();
	}
	@Override
	public T get(long index) throws IOException{
		return data.get(Math.toIntExact(index));
	}
	
	@Override
	public void set(long index, T value) throws IOException{
		data.set(Math.toIntExact(index), value);
	}
	
	@Override
	public void add(long index, T value) throws IOException{
		data.add(Math.toIntExact(index), value);
	}
	
	@Override
	public void add(T value) throws IOException{
		data.add(value);
	}
	
	@Override
	public void remove(long index) throws IOException{
		data.remove(Math.toIntExact(index));
	}
	
	@Override
	public T addNew(UnsafeConsumer<T, IOException> initializer) throws IOException{
		if(typeConstructor==null) throw new UnsupportedEncodingException();
		T t=typeConstructor.get();
		if(initializer!=null){
			initializer.accept(t);
		}
		add(t);
		return t;
	}
	
	@Override
	public void addMultipleNew(long count, UnsafeConsumer<T, IOException> initializer) throws IOException{
		if(typeConstructor==null) throw new UnsupportedEncodingException();
		if(data instanceof ArrayList<T> l) l.ensureCapacity(Math.toIntExact(l.size()+count));
		for(long i=0;i<count;i++){
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
	public long getCapacity() throws IOException{
		return Integer.MAX_VALUE;
	}
	
	@Override
	public String toString(){
		StringJoiner sj=new StringJoiner(", ", "RAM{size: "+size()+"}"+"[", "]");
		IOList.elementSummary(sj, this);
		return sj.toString();
	}
}