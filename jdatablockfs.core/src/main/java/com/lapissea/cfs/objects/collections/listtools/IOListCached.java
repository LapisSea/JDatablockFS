package com.lapissea.cfs.objects.collections.listtools;

import com.lapissea.cfs.config.GlobalConfig;
import com.lapissea.cfs.objects.Stringify;
import com.lapissea.cfs.objects.collections.IOIterator;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

public class IOListCached<T> implements IOList<T>, Stringify{
	private static class Container<O>{
		private O       obj;
		private boolean hasObj;
		public Container()                        { }
		public Container(O obj) throws IOException{ set(obj); }
		private void set(O obj) throws IOException{
			this.obj = obj;
			hasObj = true;
//			checkCache();
		}
		@Override
		public String toString(){
			return hasObj? Objects.toString(obj) : "<empty>";
		}
	}
	
	private final IOList<T>                         data;
	private final int                               maxCacheSize;
	private       LinkedHashMap<Long, Container<T>> cache = new LinkedHashMap<>();
	
	public IOListCached(IOList<T> data, int maxCacheSize){
		if(maxCacheSize<=0) throw new IllegalStateException("{maxCacheSize > 0} not satisfied");
		this.data = Objects.requireNonNull(data);
		this.maxCacheSize = maxCacheSize;
	}
	
	private void checkCache() throws IOException{
		for(var e : cache.entrySet()){
			var container = e.getValue();
			if(!container.hasObj) continue;
			
			var idx    = e.getKey();
			var cached = container.obj;
			
			var read = data.get(idx);
			if(!Objects.equals(cached, read)){
				throw new IllegalStateException(idx + "\n" + cached + "\n" + read);
			}
		}
	}
	
	private Container<T> getC(long index){
		if(cache.size()>=maxCacheSize) yeet();
		return cache.computeIfAbsent(index, i -> new Container<>());
	}
	
	private void yeet(){
		var iter = cache.entrySet().iterator();
		iter.next();
		iter.remove();
	}
	
	@Override
	public Class<T> elementType(){ return data.elementType(); }
	@Override
	public long size(){ return data.size(); }
	
	@Override
	public T get(long index) throws IOException{
		var container = getC(index);
		if(container.hasObj){
			var cached = container.obj;
			if(GlobalConfig.DEBUG_VALIDATION) checkElement(index, cached);
			return cached;
		}
		
		var read = data.get(index);
		container.set(read);
		return read;
	}
	
	private void checkElement(long index, T cached) throws IOException{
		var read = data.get(index);
		if(!read.equals(cached)){
			throw new IllegalStateException("\n" + cached + "\n" + read);
		}
	}
	
	@Override
	public void set(long index, T value) throws IOException{
		data.set(index, value);
		getC(index).set(value);
	}
	@Override
	public void add(long index, T value) throws IOException{
		data.add(index, value);
		
		shiftIds(index - 1, 1);
		cache.put(index, new Container<>(value));
	}
	
	private void shiftIds(long greaterThan, int offset){
		List<Map.Entry<Long, Container<T>>> buff = new ArrayList<>();
		
		var i = cache.entrySet().iterator();
		while(i.hasNext()){
			var e = i.next();
			if(e.getKey()>greaterThan){
				buff.add(e);
				i.remove();
			}
		}
		
		for(var e : buff){
			cache.put(e.getKey() + offset, e.getValue());
		}
	}
	
	@Override
	public void add(T value) throws IOException{
		data.add(value);
		getC(data.size() - 1).set(value);
	}
	@Override
	public void remove(long index) throws IOException{
		cacheRemoveElement(index);
		data.remove(index);
	}
	private void cacheRemoveElement(long index){
		cache.remove(index);
		shiftIds(index, -1);
	}
	
	@Override
	public T addNew(UnsafeConsumer<T, IOException> initializer) throws IOException{
		var c   = getC(data.size());
		var gnu = data.addNew(initializer);
		c.set(gnu);
		return gnu;
	}
	@Override
	public void addMultipleNew(long count, UnsafeConsumer<T, IOException> initializer) throws IOException{
		data.addMultipleNew(count, initializer);
	}
	@Override
	public void clear() throws IOException{
		cache.clear();
		data.clear();
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
	public void free(long index) throws IOException{
		data.free(index);
		cache.remove(index);
	}
	
	@Override
	public IOIterator.Iter<T> iterator(){
		IOListIterator<T> iter = data.listIterator();
		return new IOIterator.Iter<>(){
			private long lastRet;
			@Override
			public boolean hasNext(){
				return iter.hasNext();
			}
			@Override
			public T ioNext() throws IOException{
				var index = lastRet = iter.nextIndex();
				var c     = getC(index);
				if(c.hasObj){
					iter.skipNext();
					return c.obj;
				}
				var next = iter.ioNext();
				c.set(next);
				return next;
			}
			@Override
			public void ioRemove() throws IOException{
				cacheRemoveElement(lastRet);
				iter.ioRemove();
			}
		};
	}
	@Override
	public IOListIterator<T> listIterator(long startIndex){
		IOListIterator<T> iter = data.listIterator(startIndex);
		return new IOListIterator<T>(){
			long lastRet;
			@Override
			public boolean hasNext(){
				return iter.hasNext();
			}
			@Override
			public T ioNext() throws IOException{
				var c = getC(lastRet = iter.nextIndex());
				if(c.hasObj){
					iter.skipNext();
					return c.obj;
				}
				var next = iter.ioNext();
				c.set(next);
				return next;
			}
			@Override
			public void skipNext(){
				lastRet = iter.nextIndex();
				iter.skipNext();
			}
			@Override
			public boolean hasPrevious(){
				return iter.hasPrevious();
			}
			@Override
			public T ioPrevious() throws IOException{
				var c = getC(lastRet = iter.previousIndex());
				if(c.hasObj){
					iter.skipPrevious();
					return c.obj;
				}
				var next = iter.ioPrevious();
				c.set(next);
				return next;
			}
			@Override
			public void skipPrevious(){
				lastRet = iter.previousIndex();
				iter.skipPrevious();
			}
			@Override
			public long nextIndex(){
				return iter.nextIndex();
			}
			@Override
			public long previousIndex(){
				return iter.previousIndex();
			}
			@Override
			public void ioRemove() throws IOException{
				cacheRemoveElement(lastRet);
				iter.ioRemove();
			}
			@Override
			public void ioSet(T t) throws IOException{
				iter.ioSet(t);
				getC(lastRet).set(t);
			}
			@Override
			public void ioAdd(T t) throws IOException{
				cache.clear();
				iter.ioAdd(t);
			}
		};
	}
	
	@Override
	public void modify(long index, UnsafeFunction<T, T, IOException> modifier) throws IOException{
		cache.remove(index);
		data.modify(index, modifier);
	}
	
	@Override
	public boolean isEmpty(){
		return data.isEmpty();
	}
	@Override
	public T addNew() throws IOException{
		return data.addNew();
	}
	@Override
	public void addMultipleNew(long count) throws IOException{
		data.addMultipleNew(count);
	}
	@Override
	public void requestRelativeCapacity(long extra) throws IOException{
		data.requestRelativeCapacity(extra);
	}
	@Override
	public boolean contains(T value) throws IOException{
		for(var c : cache.values()){
			if(!c.hasObj) continue;
			if(Objects.equals(c.obj, value)) return true;
		}
		return data.contains(value);
	}
	@Override
	public long indexOf(T value) throws IOException{
		for(var e : cache.entrySet()){
			var c = e.getValue();
			if(!c.hasObj) continue;
			if(Objects.equals(c.obj, value)) return e.getKey();
		}
		return data.indexOf(value);
	}
	
	@Override
	public String toString(){
		try{
			checkCache();
		}catch(IOException e){
			throw new RuntimeException(e);
		}
		var sj = new StringJoiner(", ", "CAC{size: " + size() + "}" + "[", "]");
		var c  = cache;
		cache = new LinkedHashMap<>(c);
		IOList.elementSummary(sj, this);
		cache = c;
		return sj.toString();
	}
	
	@Override
	public IOList<T> cachedView(int maxCacheSize){
		return new IOListCached<>(data, maxCacheSize);
	}
}
