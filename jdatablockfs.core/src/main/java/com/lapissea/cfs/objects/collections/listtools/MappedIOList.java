package com.lapissea.cfs.objects.collections.listtools;

import com.lapissea.cfs.objects.collections.IOIterator;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.cfs.utils.OptionalPP;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Stream;

public abstract class MappedIOList<From, To> implements IOList<To>{
	private final IOList<From> data;
	private final Class<To>    mappedType;
	
	protected MappedIOList(IOList<From> data, Class<To> mappedType){
		this.data = data;
		this.mappedType = mappedType;
	}
	
	
	protected abstract To map(From v);
	protected abstract From unmap(To v);
	
	@Override
	public Class<To> elementType(){
		return mappedType;
	}
	
	@Override
	public long size(){
		return data.size();
	}
	@Override
	public To get(long index) throws IOException{
		var v = data.get(index);
		return map(v);
	}
	
	@Override
	public void set(long index, To value) throws IOException{
		data.set(index, unmap(value));
	}
	@Override
	public void add(long index, To value) throws IOException{
		data.add(index, unmap(value));
	}
	@Override
	public void add(To value) throws IOException{
		data.add(unmap(value));
	}
	@Override
	public void remove(long index) throws IOException{
		data.remove(index);
	}
	@Override
	public To addNew(UnsafeConsumer<To, IOException> initializer) throws IOException{
		return map(data.addNew(from -> initializer.accept(map(from))));
	}
	@Override
	public void addMultipleNew(long count, UnsafeConsumer<To, IOException> initializer) throws IOException{
		data.addMultipleNew(count, from -> initializer.accept(map(from)));
	}
	
	@Override
	public void clear() throws IOException{
		data.clear();
	}
	
	@Override
	public String toString(){
		StringJoiner sj = new StringJoiner(", ", "{size: " + size() + "}" + "[", "]");
		IOList.elementSummary(sj, this);
		return sj.toString();
	}
	
	@Override
	public Stream<To> stream(){
		return data.stream().map(this::map);
	}
	
	@Override
	public OptionalPP<To> first(){
		return data.first().map(this::map);
	}
	
	@Override
	public OptionalPP<To> peekFirst() throws IOException{
		return data.peekFirst().map(this::map);
	}
	
	@Override
	public OptionalPP<To> popFirst() throws IOException{
		return data.popFirst().map(this::map);
	}
	
	@Override
	public OptionalPP<To> peekLast() throws IOException{
		return data.peekLast().map(this::map);
	}
	
	@Override
	public OptionalPP<To> popLast() throws IOException{
		return data.popLast().map(this::map);
	}
	
	@Override
	public void pushFirst(To newFirst) throws IOException{
		data.pushFirst(unmap(newFirst));
	}
	
	@Override
	public void pushLast(To newLast) throws IOException{
		data.pushLast(unmap(newLast));
	}
	
	@Override
	public int hashCode(){
		return data.hashCode();
	}
	
	@Override
	public boolean equals(Object o){
		
		if(this == o){
			return true;
		}
		if(!(o instanceof IOList<?> that)){
			return false;
		}
		
		var siz = size();
		if(siz != that.size()){
			return false;
		}
		
		var iThis = iterator();
		var iThat = that.iterator();
		
		for(long i = 0; i<siz; i++){
			var vThis = iThis.next();
			var vThat = iThat.next();
			
			if(!vThis.equals(vThat)){
				return false;
			}
		}
		
		return true;
	}
	
	@Override
	public To getUnsafe(long index){
		return map(data.getUnsafe(index));
	}
	
	@Override
	public void addAll(Collection<To> values) throws IOException{
		List<From> mapped = new ArrayList<>(values.size());
		for(To v : values){
			mapped.add(unmap(v));
		}
		data.addAll(mapped);
	}
	
	@Override
	public IOIterator.Iter<To> iterator(){
		return new IOIterator.Iter<>(){
			private final Iter<From> src = data.iterator();
			@Override
			public boolean hasNext(){
				return src.hasNext();
			}
			@Override
			public To ioNext() throws IOException{
				return map(src.ioNext());
			}
			@Override
			@Deprecated
			public To next(){
				return map(src.next());
			}
			@Override
			@Deprecated
			public void remove(){
				src.remove();
			}
			@Override
			public void ioRemove() throws IOException{
				src.ioRemove();
			}
		};
	}
	
	@Override
	public IOListIterator<To> listIterator(long startIndex){
		IOListIterator<From> src = data.listIterator(startIndex);
		return new IOListIterator<>(){
			@Override
			public boolean hasNext(){
				return src.hasNext();
			}
			@Override
			public To ioNext() throws IOException{
				return map(src.ioNext());
			}
			@Override
			public void skipNext(){
				src.skipNext();
			}
			@Override
			public boolean hasPrevious(){
				return src.hasPrevious();
			}
			@Override
			public To ioPrevious() throws IOException{
				return map(src.ioPrevious());
			}
			@Override
			public void skipPrevious(){
				src.skipPrevious();
			}
			@Override
			public long nextIndex(){
				return src.nextIndex();
			}
			@Override
			public long previousIndex(){
				return src.previousIndex();
			}
			@Override
			public void ioRemove() throws IOException{
				src.ioRemove();
			}
			@Override
			public void ioSet(To to) throws IOException{
				src.ioSet(unmap(to));
			}
			@Override
			public void ioAdd(To to) throws IOException{
				src.ioAdd(unmap(to));
			}
		};
	}
	
	@Override
	public void modify(long index, UnsafeFunction<To, To, IOException> modifier) throws IOException{
		data.modify(index, obj -> unmap(modifier.apply(map(obj))));
	}
	@Override
	public boolean isEmpty(){
		return data.isEmpty();
	}
	@Override
	public To addNew() throws IOException{
		return map(data.addNew());
	}
	@Override
	public void addMultipleNew(long count) throws IOException{
		data.addMultipleNew(count);
	}
	@Override
	public void requestCapacity(long capacity) throws IOException{
		data.requestCapacity(capacity);
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
	public boolean contains(To value) throws IOException{
		return data.contains(unmap(value));
	}
	@Override
	public long indexOf(To value) throws IOException{
		return data.indexOf(unmap(value));
	}
	@Override
	public void free(long index) throws IOException{
		data.free(index);
	}
}
