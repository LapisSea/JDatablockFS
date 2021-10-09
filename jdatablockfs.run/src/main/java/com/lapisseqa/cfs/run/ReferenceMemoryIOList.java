package com.lapisseqa.cfs.run;

import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.util.TextUtil;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ReferenceMemoryIOList<T> implements IOList<T>{
	
	private final List<T> data=new ArrayList<>();
	
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
		throw new UnsupportedOperationException();
	}
	
	@Override
	public String toString(){
		return stream().map(TextUtil::toShortString).collect(Collectors.joining(", ", "[", "]"));
	}
}
