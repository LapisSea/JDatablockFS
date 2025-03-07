package com.lapissea.dfs.objects.collections.listtools;

import com.lapissea.dfs.config.GlobalConfig;
import com.lapissea.dfs.objects.Stringify;
import com.lapissea.dfs.objects.Wrapper;
import com.lapissea.dfs.objects.collections.IOIterator;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.utils.RawRandom;
import com.lapissea.dfs.utils.iterableplus.IterablePP.Ldx;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.LogUtil;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

public final class IOListCached<T> implements IOList<T>, Stringify, Wrapper<IOList<T>>{
	
	private sealed interface CacheLayer<T>{
		final class Array<T> implements CacheLayer<T>{
			
			private final ArrayList<T> cache         = new ArrayList<>();
			private       int          size;
			private       int          toRemoveIndex = -1;
			
			@Override
			public Iterable<Ldx<T>> entrySet(){
				return Iters.from(cache).enumerateL().nonNullProps(Ldx::val);
			}
			@Override
			public Iterable<T> values(){
				return Iters.from(cache).nonNulls();
			}
			@Override
			public int size(){ return size; }
			@Override
			public T get(long index){
				if(cache.size()<=index) return null;
				return cache.get((int)index);
			}
			@Override
			public void put(long index, T val){
				int i = (int)index;
				while(cache.size()<=i) cache.add(null);
				var old   = cache.set(i, val);
				var delta = val != null? 1 : 0;
				if(old != null) delta--;
				size += delta;
				if(val != null && toRemoveIndex == 1) toRemoveIndex = i;
			}
			@Override
			public void remove(long index){
				int i = (int)index;
				if(cache.size()<=i) return;
				if(cache.set(i, null) != null){
					if(i == toRemoveIndex) toRemoveIndex = -1;
					size--;
				}
			}
			@Override
			public long yeet(){
				if(size == 0) return -1;
				int tri = toRemoveIndex;
				if(tri != -1){
					cache.set(tri, null);
					toRemoveIndex = -1;
					return tri;
				}
				var rr = new RawRandom(size);
				while(true){
					var i = rr.nextInt(cache.size());
					if(cache.set(i, null) != null){
						size--;
						return i;
					}
				}
			}
			@Override
			public void shiftIds(long greaterThanL, int offset){
				int greaterThan = (int)greaterThanL;
				
				if(toRemoveIndex>greaterThan){
					toRemoveIndex += offset;
				}
				
				if(offset == -1){
					cache.remove(greaterThan);
				}else if(offset == 1){
					cache.add(greaterThan + 1, null);
				}else if(offset>0){
					for(int i = cache.size() - 1; i>greaterThan; i--){
						var val = cache.set(i, null);
						if(val == null) continue;
						var newPos = i + offset;
						while(newPos>=cache.size()) cache.add(null);
						cache.set(newPos, val);
					}
				}else{
					for(int i = greaterThan + 1; i<cache.size(); i++){
						var val = cache.set(i, null);
						if(val == null) continue;
						var newPos = i + offset;
						cache.set(newPos, val);
					}
				}
			}
		}
		
		final class Sparse<T> implements CacheLayer<T>{
			
			private final LinkedHashMap<Long, T> cache = new LinkedHashMap<>();
			
			@Override
			public Iterable<Map.Entry<Long, T>> entrySet(){ return cache.entrySet(); }
			@Override
			public Iterable<T> values(){ return cache.values(); }
			@Override
			public int size(){ return cache.size(); }
			@Override
			public T get(long index){ return cache.get(index); }
			@Override
			public void put(long index, T val){ cache.put(index, val); }
			@Override
			public void remove(long index){ cache.remove(index); }
			@Override
			public long yeet(){ return cache.pollFirstEntry().getKey(); }
			@Override
			public void shiftIds(long greaterThan, int offset){
				List<Map.Entry<Long, T>> buff = new ArrayList<>();
				
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
		}
		
		final class Checked<T> implements CacheLayer<T>{
			private final CacheLayer<T> ref, test;
			
			public Checked(CacheLayer<T> ref, CacheLayer<T> test){
				this.ref = ref;
				this.test = test;
			}
			@Override
			public Iterable<Map.Entry<Long, T>> entrySet(){
				return new Iterable<>(){
					final Iterator<? extends Map.Entry<Long, T>> testI = test.entrySet().iterator();
					final Iterator<? extends Map.Entry<Long, T>> refI  = test.entrySet().iterator();
					@Override
					public Iterator<Map.Entry<Long, T>> iterator(){
						return new Iterator<>(){
							@Override
							public boolean hasNext(){
								return refI.hasNext();
							}
							@Override
							public Map.Entry<Long, T> next(){
								var a = refI.next();
								var b = testI.next();
								if(!a.equals(b)) throw new IllegalStateException(a + " " + b);
								return b;
							}
						};
					}
				};
			}
			@Override
			public Iterable<T> values(){
				return new Iterable<>(){
					final Iterator<T> testI = test.values().iterator();
					final Iterator<T> refI  = test.values().iterator();
					@Override
					public Iterator<T> iterator(){
						return new Iterator<>(){
							@Override
							public boolean hasNext(){
								return refI.hasNext();
							}
							@Override
							public T next(){
								var a = refI.next();
								var b = testI.next();
								if(!a.equals(b)) throw new IllegalStateException(a + " " + b);
								return b;
							}
						};
					}
				};
			}
			@Override
			public int size(){
				var a = ref.size();
				var b = test.size();
				if(a != b) throw new IllegalStateException(a + " " + b);
				return b;
			}
			@Override
			public T get(long index){
				var a = ref.get(index);
				var b = test.get(index);
				if(!Objects.equals(a, b)) throw new IllegalStateException(a + " " + b);
				return b;
			}
			@Override
			public void put(long index, T val){
				ref.put(index, val);
				test.put(index, val);
				checkAll();
			}
			
			private void checkAll(){
				var a = Iters.from(ref.entrySet()).toModMap(e -> e);
				var b = Iters.from(test.entrySet()).toModMap(e -> e);
				if(!a.equals(b)){
					LogUtil.println(a + "\n" + b);
					throw new IllegalStateException("\n" + a + "\n" + b);
				}
			}
			@Override
			public void remove(long index){
				ref.remove(index);
				test.remove(index);
				checkAll();
			}
			@Override
			public long yeet(){
				var key = ref.yeet();
				test.remove(key);
				checkAll();
				return key;
			}
			@Override
			public void shiftIds(long greaterThan, int offset){
				ref.shiftIds(greaterThan, offset);
				test.shiftIds(greaterThan, offset);
				checkAll();
			}
		}
		
		Iterable<? extends Map.Entry<Long, T>> entrySet();
		Iterable<T> values();
		
		int size();
		T get(long index);
		void put(long index, T val);
		void remove(long index);
		long yeet();
		void shiftIds(long greaterThan, int offset);
	}
	
	private final IOList<T> data;
	private final int       maxCacheSize, maxLinearCache;
	private CacheLayer<T> cache = new CacheLayer.Array<>();
	private int           modCount;
	
	public IOListCached(IOList<T> data, int maxCacheSize, int maxLinearCache){
		if(maxCacheSize<=0) throw new IllegalStateException("{maxCacheSize > 0} not satisfied");
		this.data = Objects.requireNonNull(data);
		this.maxCacheSize = maxCacheSize;
		this.maxLinearCache = maxLinearCache;
		if(maxCacheSize<maxLinearCache){
			throw new IllegalArgumentException("maxCacheSize < maxLinearCache (" + maxCacheSize + " < " + maxLinearCache + ")");
		}
	}
	
	private void clearCache(){ cache = new CacheLayer.Array<>(); }
	private void checkOpt(){
//		try{
//			checkCache();
//		}catch(IOException e){
//			throw new RuntimeException(e);
//		}
	}
	private void checkCache() throws IOException{
		for(var e : cache.entrySet()){
			var cached = e.getValue();
			var idx    = e.getKey();
			
			var read = data.get(idx);
			if(!Objects.equals(cached, read)){
				throw new IllegalStateException(idx + "\n" + cached + "\n" + read);
			}
		}
	}
	
	private T getC(long index){
		limitCacheSize();
		return cache.get(index);
	}
	private void limitCacheSize(){
		if(cache.size()>=maxCacheSize) cache.yeet();
	}
	private void setC(Long index, T value){
		if(value == null) cache.remove(index);
		else{
			limitCacheSize();
			cache.put(index, value);
			if(data.size()>maxLinearCache && cache instanceof CacheLayer.Array){
				var c = new CacheLayer.Sparse<T>();
				for(var e : cache.entrySet()){
					c.put(e.getKey(), e.getValue());
				}
				cache = c;
			}
		}
	}
	
	@Override
	public Class<T> elementType(){ return data.elementType(); }
	@Override
	public long size(){ return data.size(); }
	
	@Override
	public T get(long index) throws IOException{
		var cached = getC(index);
		if(cached != null){
			if(GlobalConfig.DEBUG_VALIDATION) checkElement(index, cached);
			return cached;
		}
		
		var read = data.get(index);
		setC(index, read);
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
		var expected = ++modCount;
		data.set(index, value);
		if(expected != modCount) clearCache();
		else{
			setC(index, value);
			checkOpt();
		}
	}
	
	@Override
	public void add(long index, T value) throws IOException{
		var expected = ++modCount;
		data.add(index, value);
		if(expected != modCount) clearCache();
		else{
			shiftIds(index - 1, 1);
			setC(index, value);
			checkOpt();
		}
	}
	
	private void shiftIds(long greaterThan, int offset){
		cache.shiftIds(greaterThan, offset);
	}
	
	@Override
	public void add(T value) throws IOException{
		var expected = ++modCount;
		data.add(value);
		if(expected != modCount) clearCache();
		else{
			setC(data.size() - 1, value);
			checkOpt();
		}
	}
	@Override
	public void remove(long index) throws IOException{
		cacheRemoveElement(index);
		var expected = ++modCount;
		data.remove(index);
		if(expected != modCount) clearCache();
		checkOpt();
	}
	private void cacheRemoveElement(long index){
		cache.remove(index);
		shiftIds(index, -1);
	}
	
	@Override
	public T addNew(UnsafeConsumer<T, IOException> initializer) throws IOException{
		var expected = ++modCount;
		var idx      = data.size();
		var gnu      = data.addNew(initializer);
		if(expected != modCount) clearCache();
		else{
			setC(idx, gnu);
			checkOpt();
		}
		return gnu;
	}
	@Override
	public void addMultipleNew(long count, UnsafeConsumer<T, IOException> initializer) throws IOException{
		var expected = ++modCount;
		data.addMultipleNew(count, initializer);
		if(expected != modCount) clearCache();
		checkOpt();
	}
	@Override
	public void clear() throws IOException{
		clearCache();
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
		var expected = ++modCount;
		data.free(index);
		if(expected != modCount) clearCache();
		else cache.remove(index);
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
				if(c != null){
					if(GlobalConfig.DEBUG_VALIDATION) checkElement(index, c);
					iter.skipNext();
					return c;
				}
				var next = iter.ioNext();
				setC(index, next);
				return next;
			}
			@Override
			public void ioRemove() throws IOException{
				cacheRemoveElement(lastRet);
				var expected = ++modCount;
				iter.ioRemove();
				if(expected != modCount) clearCache();
			}
		};
	}
	@Override
	public IOListIterator<T> listIterator(long startIndex){
		IOListIterator<T> iter = data.listIterator(startIndex);
		return new IOListIterator<>(){
			long lastRet;
			@Override
			public boolean hasNext(){
				return iter.hasNext();
			}
			@Override
			public T ioNext() throws IOException{
				var index = lastRet = iter.nextIndex();
				var c     = getC(index);
				if(c != null){
					if(GlobalConfig.DEBUG_VALIDATION) checkElement(index, c);
					iter.skipNext();
					return c;
				}
				var next = iter.ioNext();
				setC(index, next);
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
				var index = lastRet = iter.previousIndex();
				var c     = getC(index);
				if(c != null){
					iter.skipPrevious();
					return c;
				}
				var next = iter.ioPrevious();
				setC(index, next);
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
				var expected = ++modCount;
				iter.ioSet(t);
				if(expected != modCount) clearCache();
				setC(lastRet, t);
			}
			@Override
			public void ioAdd(T t) throws IOException{
				clearCache();
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
			if(c == null) continue;
			if(Objects.equals(c, value)) return true;
		}
		return data.contains(value);
	}
	@Override
	public long indexOf(T value) throws IOException{
		for(var e : cache.entrySet()){
			var c = e.getValue();
			if(c == null) continue;
			if(Objects.equals(c, value)) return e.getKey();
		}
		return data.indexOf(value);
	}
	
	@Override
	public String toString(){
		checkOpt();
		var sj = new StringJoiner(", ", "CAC{size: " + size() + "}" + "[", "]");
		var c  = cache;
		cache = new CacheLayer.Sparse<>();
		for(var e : c.entrySet()){
			cache.put(e.getKey(), e.getValue());
		}
		IOList.elementSummary(sj, this);
		cache = c;
		return sj.toString();
	}
	
	@Override
	public IOList<T> cachedView(int maxCacheSize, int maxLinearCache){
		return new IOListCached<>(data, maxCacheSize, maxLinearCache);
	}
	
	@Override
	public IOList<T> getWrappedObj(){
		return data;
	}
}
