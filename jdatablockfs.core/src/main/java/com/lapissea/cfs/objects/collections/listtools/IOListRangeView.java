package com.lapissea.cfs.objects.collections.listtools;

import com.lapissea.cfs.objects.collections.IOIterator;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.util.*;
import java.util.stream.Stream;

public class IOListRangeView<T> implements IOList<T>{
	
	private final IOList<T> data;
	private final long      from;
	private final long      to;
	private final long      subSize;
	
	public IOListRangeView(IOList<T> data, long from, long to){
		Objects.requireNonNull(data);
		if(from<0) throw new IllegalArgumentException();
		if(to<0) throw new IllegalArgumentException();
		if(to<from) throw new IllegalArgumentException("from is bigger than to!");
		if(data.size()<to) throw new IndexOutOfBoundsException();
		
		this.data=data;
		this.from=from;
		this.to=to;
		subSize=to-from;
	}
	
	private long toGlobalIndex(long index){
		if(index<0) throw new IndexOutOfBoundsException();
		return index+from;
	}
	private long toLocalIndex(long index){
		return index-from;
	}
	
	@Override
	public Stream<T> stream(){
		return data.stream().skip(from).limit(subSize);
	}
	
	@Override
	public Class<T> elementType(){
		return data.elementType();
	}
	
	@Override
	public long size(){
		var size=data.size();
		if(size<=from) return 0;
		return Math.min(size-from, subSize);
	}
	@Override
	public T getUnsafe(long index){
		return data.getUnsafe(toGlobalIndex(index));
	}
	@Override
	public T get(long index) throws IOException{
		return data.get(toGlobalIndex(index));
	}
	@Override
	public void set(long index, T value) throws IOException{
		data.set(toGlobalIndex(index), value);
	}
	@Override
	public void add(long index, T value) throws IOException{
		data.add(toGlobalIndex(index), value);
	}
	@Override
	public void add(T value) throws IOException{
		if(data.size()<=from) throw new IndexOutOfBoundsException();
		if(data.size()>to-1){
			throw new IndexOutOfBoundsException();
		}
		add(size(), value);
	}
	@Override
	public void addAll(Collection<T> values) throws IOException{
		if(data.size()<=from) throw new IndexOutOfBoundsException();
		if(data.size()>to-values.size()){
			throw new IndexOutOfBoundsException();
		}
		data.addAll(values);
	}
	@Override
	public void remove(long index) throws IOException{
		if(data.size()<=from) throw new IndexOutOfBoundsException();
		data.remove(toGlobalIndex(index));
	}
	@Override
	public Spliterator<T> spliterator(){
		return data.spliterator(toGlobalIndex(0));
	}
	@Override
	public IOIterator.Iter<T> iterator(){
		var iter=listIterator(0);
		return new IOIterator.Iter<>(){
			private long i=0;
			@Override
			public boolean hasNext(){
				if(i==subSize) return false;
				return iter.hasNext();
			}
			@Override
			public T ioNext() throws IOException{
				if(i==subSize) throw new NoSuchElementException();
				i++;
				return iter.ioNext();
			}
			@Override
			public void ioRemove() throws IOException{
				iter.ioRemove();
			}
		};
	}
	
	@Override
	public IOListIterator<T> listIterator(long startIndex){
		var iter=data.listIterator(toGlobalIndex(startIndex));
		return new IOListIterator<>(){
			@Override
			public boolean hasNext(){
				return iter.hasNext()&&iter.nextIndex()<to;
			}
			@Override
			public T ioNext() throws IOException{
				if(!hasNext()) throw new NoSuchElementException();
				return iter.ioNext();
			}
			@Override
			public boolean hasPrevious(){
				return iter.hasPrevious()&&iter.previousIndex()>=from;
			}
			@Override
			public T ioPrevious() throws IOException{
				if(!hasPrevious()) throw new NoSuchElementException();
				return iter.ioPrevious();
			}
			@Override
			public long nextIndex(){
				return toLocalIndex(iter.nextIndex());
			}
			@Override
			public long previousIndex(){
				return toLocalIndex(iter.previousIndex());
			}
			@Override
			public void ioRemove() throws IOException{
				iter.ioRemove();
			}
			@Override
			public void ioSet(T t) throws IOException{
				iter.ioSet(t);
			}
			@Override
			public void ioAdd(T t) throws IOException{
				iter.ioAdd(t);
			}
		};
	}
	@Override
	public void modify(long index, UnsafeFunction<T, T, IOException> modifier) throws IOException{
		data.modify(toGlobalIndex(index), modifier);
	}
	@Override
	public boolean isEmpty(){
		return size()==0;
	}
	@Override
	public T addNew() throws IOException{
		return data.addNew();
	}
	@Override
	public T addNew(UnsafeConsumer<T, IOException> initializer) throws IOException{
		return data.addNew(initializer);
	}
	@Override
	public void addMultipleNew(long count) throws IOException{
		data.addMultipleNew(count);
	}
	@Override
	public void addMultipleNew(long count, UnsafeConsumer<T, IOException> initializer) throws IOException{
		data.addMultipleNew(count, initializer);
	}
	@Override
	public void clear() throws IOException{
		for(long i=data.size()-1;i>=from;i--){
			data.remove(i);
		}
	}
	@Override
	public void requestCapacity(long capacity) throws IOException{
		data.requestCapacity(capacity+from);
	}
	@Override
	public void trim() throws IOException{
		data.trim();
	}
	
	@Override
	public long getCapacity() throws IOException{
		return data.getCapacity();
	}
	@Override
	public boolean contains(T value) throws IOException{
		return IOList.super.contains(value);
	}
	@Override
	public long indexOf(T value) throws IOException{
		return IOList.super.indexOf(value);
	}
	@Override
	public Optional<T> first(){
		if(isEmpty()) return Optional.empty();
		try{
			return Optional.of(get(0));
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	@Override
	public IOList<T> subListView(long from, long to){
		return new IOListRangeView<>(data, this.from+from, this.from+to);
	}
	
	@Override
	public String toString(){
		StringJoiner sj=new StringJoiner(", ", "{size: "+size()+"}"+"[", "]");
		IOList.elementSummary(sj, this);
		return sj.toString();
	}
}
