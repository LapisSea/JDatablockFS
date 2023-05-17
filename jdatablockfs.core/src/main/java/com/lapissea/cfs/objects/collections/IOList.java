package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.objects.Stringify;
import com.lapissea.cfs.objects.collections.listtools.IOListRangeView;
import com.lapissea.cfs.objects.collections.listtools.MappedIOList;
import com.lapissea.cfs.objects.collections.listtools.MemoryWrappedIOList;
import com.lapissea.cfs.query.Query;
import com.lapissea.cfs.query.QueryCheck;
import com.lapissea.cfs.query.QuerySupport;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.cfs.utils.IterablePP;
import com.lapissea.util.Nullable;
import com.lapissea.util.function.FunctionOL;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.RandomAccess;
import java.util.Set;
import java.util.Spliterator;
import java.util.StringJoiner;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

@SuppressWarnings("unused")
@IOValue.OverrideType.DefaultImpl(ContiguousIOList.class)
public interface IOList<T> extends IterablePP<T>{
	
	class Cached<T> implements IOList<T>, Stringify{
		private static class Container<T>{
			private T       obj;
			private boolean hasObj;
			public Container()     { }
			public Container(T obj){ set(obj); }
			private void set(T obj){
				this.obj = obj;
				hasObj = true;
			}
		}
		
		private final IOList<T>               data;
		private final int                     maxCacheSize;
		private final Map<Long, Container<T>> cache = new LinkedHashMap<>();
		
		public Cached(IOList<T> data, int maxCacheSize){
			if(maxCacheSize<=0) throw new IllegalStateException("{maxCacheSize > 0} not satisfied");
			this.data = Objects.requireNonNull(data);
			this.maxCacheSize = maxCacheSize;
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
			var cached = getC(index);
			if(cached.hasObj) return cached.obj;
			
			var read = data.get(index);
			cached.set(read);
			return read;
		}
		
		@Override
		public void set(long index, T value) throws IOException{
			data.set(index, value);
			getC(index).set(value);
		}
		@Override
		public void add(long index, T value) throws IOException{
			data.add(index, value);
			
			var toReadd = pull(index, i -> i>=index);
			toReadd.forEach((k, v) -> cache.put(k + 1, v));
			cache.put(index, new Container<>(value));
		}
		
		private Map<Long, Container<T>> pull(long index, LongPredicate check){
			Map<Long, Container<T>> buff = new LinkedHashMap<>();
			cache.entrySet().removeIf(e -> {
				if(check.test(e.getKey())) return false;
				buff.put(e.getKey(), e.getValue());
				return true;
			});
			return buff;
		}
		
		@Override
		public void add(T value) throws IOException{
			getC(data.size()).set(value);
			data.add(value);
		}
		@Override
		public void remove(long index) throws IOException{
			data.remove(index);
			cache.remove(index);
			var toReadd = pull(index, i -> i>index);
			toReadd.forEach((k, v) -> cache.put(k - 1, v));
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
				@Override
				public boolean hasNext(){
					return iter.hasNext();
				}
				@Override
				public T ioNext() throws IOException{
					var c = getC(iter.nextIndex());
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
					cache.clear();
					iter.ioRemove();
				}
			};
		}
		@Override
		public IOListIterator<T> listIterator(long startIndex){
			IOListIterator<T> iter = data.listIterator(startIndex);
			return new IOListIterator<T>(){
				@Override
				public boolean hasNext(){
					return iter.hasNext();
				}
				@Override
				public T ioNext() throws IOException{
					var c = getC(iter.nextIndex());
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
					iter.skipNext();
				}
				@Override
				public boolean hasPrevious(){
					return iter.hasPrevious();
				}
				@Override
				public T ioPrevious() throws IOException{
					var c = getC(iter.previousIndex());
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
					iter.ioRemove();
					cache.clear();
				}
				@Override
				public void ioSet(T t) throws IOException{
					iter.ioSet(t);
					cache.clear();
				}
				@Override
				public void ioAdd(T t) throws IOException{
					iter.ioRemove();
					cache.clear();
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
		public int hashCode(){
			return data.hashCode();
		}
		@Override
		public String toString(){
			return data.toString();
		}
		@Override
		public String toShortString(){
			return data instanceof Stringify s? s.toShortString() : data.toString();
		}
	}
	
	static <T> void elementSummary(StringJoiner sb, IOList<T> data){
		var iter = data.iterator();
		
		var push = new Consumer<String>(){
			private String repeatBuff = null;
			private int repeatCount = 0;
			private long repeatIndexStart;
			private long count = 0;
			
			@Override
			public void accept(String str){
				if(repeatBuff == null){
					repeatBuff = str;
					repeatCount = 1;
					repeatIndexStart = count;
					return;
				}
				if(repeatBuff.equals(str)){
					repeatCount++;
					return;
				}
				
				if(repeatCount != 0){
					flush();
				}
				
				accept(str);
			}
			private void flush(){
				if(repeatCount>4){
					if(repeatIndexStart == 0) sb.add(repeatBuff + " (" + repeatCount + " times)");
					else sb.add(repeatBuff + " (" + repeatCount + " times @" + repeatIndexStart + ")");
				}else{
					for(int i = 0; i<repeatCount; i++){
						sb.add(repeatBuff);
					}
				}
				repeatBuff = null;
				repeatCount = 0;
				repeatIndexStart = 0;
			}
		};
		
		while(true){
			if(!iter.hasNext()){
				push.flush();
				return;
			}
			if(sb.length() + (push.repeatCount == 0? 0 : (push.repeatCount*(push.repeatBuff.length() + 2) + 8))>300){
				push.flush();
				sb.add("... " + (data.size() - push.count) + " more");
				return;
			}
			Object e;
			try{
				e = iter.ioNext();
			}catch(Throwable ex){
				e = "CORRUPT: " + ex.getClass().getSimpleName();
			}
			push.count++;
			
			push.accept(Utils.toShortString(e));
		}
	}
	
	@SafeVarargs
	static <T> IOList<T> of(T... data){
		return new MemoryWrappedIOList<>(List.of(data), null);
	}
	static <T> IOList<T> wrap(List<T> data){
		return new MemoryWrappedIOList<>(data, null);
	}
	static <T> IOList<T> wrap(List<T> data, Supplier<T> typeConstructor){
		return new MemoryWrappedIOList<>(data, typeConstructor);
	}
	
	static boolean isWrapped(IOList<?> list){
		return list instanceof MemoryWrappedIOList;
	}
	
	static <T> long findSortedClosest(IOList<T> freeChunks, FunctionOL<T> distanceMapping) throws IOException{
		switch((int)freeChunks.size()){
			case 0 -> { return -1; }
			case 1 -> { return 0; }
		}
		
		long min = 0, max = freeChunks.size() - 1;
		
		long minDist = distanceMapping.apply(freeChunks.get(min));
		long maxDist = distanceMapping.apply(freeChunks.get(max));
		
		while(max - min>1){
			var mid     = (min + max)/2;
			var midDist = distanceMapping.apply(freeChunks.get(mid));
			
			if(midDist<minDist){
				minDist = midDist;
				min = mid;
				continue;
			}
			if(midDist<maxDist){
				maxDist = midDist;
				max = mid;
			}
		}
		
		return minDist<maxDist? min : max;
	}
	
	static <T extends Comparable<T>> long addRemainSorted(IOList<T> list, T value) throws IOException{
		if(list.isEmpty()){
			list.add(value);
			return 0;
		}
		
		if(value.compareTo(list.peekLast().orElseThrow())<0){
			list.pushFirst(value);
			return 0;
		}
		if(value.compareTo(list.peekLast().orElseThrow())>0){
			var index = list.size();
			list.add(value);
			return index;
		}
		
		long lo = 0;
		long hi = list.size() - 1;
		
		while(lo<=hi){
			long mid = (hi + lo)/2;
			
			int comp = value.compareTo(list.get(mid));
			if(comp<0){
				hi = mid - 1;
			}else if(comp>0){
				lo = mid + 1;
			}else{
				list.add(mid, value);
				return mid;
			}
		}
		
		list.add(lo, value);
		return lo;
	}
	
	static <T extends Comparable<T>> void scuffedCycleSort(IOList<T> list) throws IOException{
		
		// Loop through the array to find cycles to rotate.
		for(long cycleStart = 0; cycleStart<list.size() - 1; cycleStart++){
			T item = list.get(cycleStart);
			
			// Find where to put the item.
			long pos = cycleStart;
			for(long i = cycleStart + 1; i<list.size(); i++){
				if(list.get(i).compareTo(item)<0) pos++;
			}
			
			// If the item is already there, this is not a cycle.
			if(pos == cycleStart) continue;
			
			// Otherwise, put the item there or right after any duplicates.
			while(item.equals(list.get(pos))){
				pos++;
			}
			{
				T temp = list.get(pos);
				list.set(pos, item);
				
				list.set(cycleStart, temp);
				cycleStart--;
			}
		}
	}
	
	static <T> boolean elementsEqual(IOList<T> thisL, IOList<T> thatL){
		if(thisL == thatL){
			return true;
		}
		var siz = thisL.size();
		if(siz != thatL.size()){
			return false;
		}
		
		var iThis = thisL.iterator();
		var iThat = thatL.iterator();
		
		for(long i = 0; i<siz; i++){
			try{
				var vThis = iThis.ioNext();
				var vThat = iThat.ioNext();
				
				if(!Objects.equals(vThis, vThat)){
					return false;
				}
			}catch(IOException e){
				throw new RuntimeException(e);
			}
		}
		
		return true;
	}
	
	interface IOListIterator<T> extends IOIterator<T>{
		
		abstract class AbstractIndex<T> implements IOListIterator<T>{
			
			private long cursor;
			private long lastRet = -1;
			
			public AbstractIndex(long cursorStart){
				this.cursor = cursorStart;
			}
			
			protected abstract T getElement(long index) throws IOException;
			protected abstract void removeElement(long index) throws IOException;
			protected abstract void setElement(long index, T value) throws IOException;
			protected abstract void addElement(long index, T value) throws IOException;
			protected abstract long getSize();
			
			@Override
			public boolean hasNext(){
				return cursor<getSize();
			}
			@Override
			public T ioNext() throws IOException{
				var i    = cursor;
				var next = getElement(i);
				lastRet = i;
				cursor = i + 1;
				return next;
			}
			@Override
			public void skipNext(){
				if(!hasNext()) throw new NoSuchElementException();
				cursor++;
			}
			@Override
			public boolean hasPrevious(){
				return cursor != 0;
			}
			
			@Override
			public T ioPrevious() throws IOException{
				var i        = cursor - 1;
				var previous = getElement(i);
				lastRet = cursor = i;
				return previous;
			}
			@Override
			public void skipPrevious(){
				if(!hasPrevious()) throw new NoSuchElementException();
				cursor--;
			}
			
			@Override
			public long nextIndex(){ return cursor; }
			@Override
			public long previousIndex(){ return cursor - 1; }
			
			@Override
			public void ioRemove() throws IOException{
				if(lastRet<0) throw new IllegalStateException();
				
				removeElement(lastRet);
				
				if(lastRet<cursor) cursor--;
				lastRet = -1;
			}
			
			@Override
			public void ioSet(T t) throws IOException{
				if(lastRet<0) throw new IllegalStateException();
				setElement(lastRet, t);
			}
			
			@Override
			public void ioAdd(T t) throws IOException{
				var i = cursor;
				addElement(i, t);
				lastRet = -1;
				cursor = i + 1;
			}
		}
		
		@Override
		boolean hasNext();
		@Override
		T ioNext() throws IOException;
		void skipNext();
		
		boolean hasPrevious();
		T ioPrevious() throws IOException;
		void skipPrevious();
		
		long nextIndex();
		long previousIndex();
		
		@Override
		void ioRemove() throws IOException;
		void ioSet(T t) throws IOException;
		void ioAdd(T t) throws IOException;
	}
	
	
	Class<T> elementType();
	
	long size();
	
	default T getUnsafe(long index){
		try{
			return get(index);
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	T get(long index) throws IOException;
	void set(long index, T value) throws IOException;
	
	void add(long index, T value) throws IOException;
	void add(T value) throws IOException;
	
	default void addAll(Collection<T> values) throws IOException{
		requestCapacity(size() + values.size());
		for(T value : values){
			add(value);
		}
	}
	
	void remove(long index) throws IOException;
	
	@Override
	default Spliterator<T> spliterator(){
		return spliterator(0);
	}
	default Spliterator<T> spliterator(long start){
		
		final class RandomAccessIteratorSpliterator implements Spliterator<T>{
			private       long index; // current index, modified on advance/split
			private final long size;
			
			private IOListIterator<T> iterator;
			
			private RandomAccessIteratorSpliterator(long origin){
				this(origin, size());
			}
			private RandomAccessIteratorSpliterator(long origin, long size){
				this.index = origin;
				this.size = size;
			}
			
			@Override
			public Spliterator<T> trySplit(){
				long lo = index, mid = (lo + size) >>> 1;
				return (lo>=mid)? null : // divide range in half unless too small
				       new RandomAccessIteratorSpliterator(lo, index = mid);
			}
			
			@Override
			public boolean tryAdvance(Consumer<? super T> action){
				if(action == null) throw new NullPointerException();
				long i = index;
				if(i<size){
					index = i + 1;
					action.accept(get(getIterator(i), i));
					return true;
				}
				return false;
			}
			
			@Override
			public void forEachRemaining(Consumer<? super T> action){
				Objects.requireNonNull(action);
				long i    = index;
				var  iter = getIterator(i);
				index = size;
				for(; i<size; i++){
					action.accept(get(iter, i));
				}
			}
			
			@Override
			public long estimateSize(){
				return size - index;
			}
			
			@Override
			public int characteristics(){
				return Spliterator.ORDERED|Spliterator.SIZED|Spliterator.SUBSIZED;
			}
			
			private IOListIterator<T> getIterator(long i){
				var iter = iterator;
				if(iter == null) iterator = iter = listIterator(i);
				return iter;
			}
			
			private static <E> E get(IOListIterator<E> list, long i){
				if(i != list.nextIndex()) throw new AssertionError(i + " " + (list.nextIndex()));
				try{
					return list.ioNext();
				}catch(IndexOutOfBoundsException ex){
					throw new ConcurrentModificationException();
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
		}
		
		final class IteratorSpliterator implements Spliterator<T>{
			
			private final IOListIterator<T> it;
			private final long              size = size();
			private       long              index;
			
			public IteratorSpliterator(long start){
				it = listIterator(start);
			}
			
			
			@Override
			public boolean tryAdvance(Consumer<? super T> action){
				if(!it.hasNext()){
					return false;
				}
				T val;
				try{
					val = it.ioNext();
				}catch(IOException e){
					throw new RuntimeException("Failed to provide element: " + (it.nextIndex() - 1), e);
				}
				index++;
				action.accept(val);
				return true;
			}
			@Override
			public Spliterator<T> trySplit(){
				return null;
			}
			@Override
			public long estimateSize(){
				return size - index;
			}
			@Override
			public int characteristics(){
				return Spliterator.ORDERED|Spliterator.SIZED;
			}
		}
		
		if(this instanceof RandomAccess) return new RandomAccessIteratorSpliterator(start);
		else return new IteratorSpliterator(start);
	}
	
	@Override
	default IOIterator.Iter<T> iterator(){
		//NOT GOOD FOR LINKED LISTS
		final class IndexAccessIterator implements IOIterator.Iter<T>{
			long index = 0;
			@Override
			public boolean hasNext(){
				var siz = size();
				return index<siz;
			}
			@Override
			public T ioNext() throws IOException{
				return get(index++);
			}
			@Override
			public void ioRemove() throws IOException{
				IOList.this.remove(--index);
			}
		}
		return new IndexAccessIterator();
	}
	
	default IOListIterator<T> listIterator(){ return listIterator(0); }
	default IOListIterator<T> listIterator(long startIndex){
		//NOT GOOD FOR LINKED LISTS
		final class IndexAccessListIterator extends IOListIterator.AbstractIndex<T>{
			
			public IndexAccessListIterator(long cursorStart){
				super(cursorStart);
			}
			
			@Override
			protected T getElement(long index) throws IOException{
				return IOList.this.get(index);
			}
			@Override
			protected void removeElement(long index) throws IOException{
				IOList.this.remove(index);
			}
			@Override
			protected void setElement(long index, T value) throws IOException{
				IOList.this.set(index, value);
			}
			@Override
			protected void addElement(long index, T value) throws IOException{
				IOList.this.add(index, value);
			}
			@Override
			protected long getSize(){
				return IOList.this.size();
			}
		}
		
		return new IndexAccessListIterator(startIndex);
	}
	
	default void modify(long index, UnsafeFunction<T, T, IOException> modifier) throws IOException{
		T oldObj = get(index);
		T newObj = modifier.apply(oldObj);
		if(oldObj == newObj || !oldObj.equals(newObj)){
			set(index, newObj);
		}
	}
	
	default boolean isEmpty(){
		return size() == 0;
	}
	
	default T addNew() throws IOException{
		return addNew(null);
	}
	
	T addNew(@Nullable UnsafeConsumer<T, IOException> initializer) throws IOException;
	
	default void addMultipleNew(long count) throws IOException{
		addMultipleNew(count, null);
	}
	void addMultipleNew(long count, @Nullable UnsafeConsumer<T, IOException> initializer) throws IOException;
	
	void clear() throws IOException;
	
	/**
	 * The list can allocate space for data that may come later here.
	 * It is not required to do so but is desirable. No exact capacity allocation is required.
	 */
	void requestCapacity(long capacity) throws IOException;
	default void requestRelativeCapacity(long extra) throws IOException{
		requestCapacity(size() + extra);
	}
	void trim() throws IOException;
	long getCapacity() throws IOException;
	
	default boolean contains(T value) throws IOException{
		return indexOf(value) != -1;
	}
	
	default long indexOf(T value) throws IOException{
		var  iter  = iterator();
		long index = 0;
		while(iter.hasNext()){
			var el = iter.ioNext();
			if(Objects.equals(el, value)){
				return index;
			}
			index++;
		}
		return -1;
	}
	
	@Override
	default Optional<T> first(){
		if(isEmpty()) return Optional.empty();
		return Optional.of(getUnsafe(0));
	}
	
	default Optional<T> peekFirst() throws IOException{
		if(isEmpty()) return Optional.empty();
		return Optional.of(get(0));
	}
	default Optional<T> popFirst() throws IOException{
		if(isEmpty()) return Optional.empty();
		var first = get(0);
		remove(0);
		return Optional.of(first);
	}
	
	default void pushFirst(T newFirst) throws IOException{
		if(isEmpty()){
			add(newFirst);
		}else{
			add(0, newFirst);
		}
	}
	
	default Optional<T> peekLast() throws IOException{
		if(isEmpty()) return Optional.empty();
		return Optional.of(get(size() - 1));
	}
	default Optional<T> popLast() throws IOException{
		if(isEmpty()) return Optional.empty();
		var index = size() - 1;
		var val   = get(index);
		remove(index);
		return Optional.of(val);
	}
	default Optional<T> popLastIf(Predicate<T> check) throws IOException{
		if(isEmpty()) return Optional.empty();
		var index = size() - 1;
		var val   = get(index);
		if(!check.test(val)) return Optional.empty();
		remove(index);
		return Optional.of(val);
	}
	
	default void pushLast(T newLast) throws IOException{
		add(newLast);
	}
	
	default IOList<T> subListView(long from, long to){
		return new IOListRangeView<>(this, from, to);
	}
	
	default <To> IOList<To> map(Class<To> mappedType, Function<T, To> map, Function<To, T> unmap){
		Objects.requireNonNull(map);
		Objects.requireNonNull(unmap);
		return new MappedIOList<>(this, mappedType){
			@Override
			protected To map(T v){ return map.apply(v); }
			@Override
			protected T unmap(To v){ return unmap.apply(v); }
		};
	}
	
	
	default Query<T> query(String expression, Object... args){
		return query().filter(expression, args);
	}
	
	default Query<T> query(Set<String> readFields, Predicate<T> filter){
		return query().filter(new QueryCheck.Lambda((Predicate<Object>)filter, readFields));
	}
	
	abstract class ListData<T> implements QuerySupport.Data<T>{
		
		public static <T> QuerySupport.Data<T> of(IOList<T> list, Function<Set<String>, QuerySupport.AccessIterator<T>> elements){
			return new ListData<>(list){
				@Override
				public QuerySupport.AccessIterator<T> elements(Set<String> readFields){
					return elements.apply(readFields);
				}
			};
		}
		
		private final IOList<T> l;
		protected ListData(IOList<T> l){ this.l = l; }
		
		@Override
		public Class<T> elementType(){
			return l.elementType();
		}
		@Override
		public OptionalLong count(){
			return OptionalLong.of(l.size());
		}
	}
	
	default Query<T> query(){
		return QuerySupport.of(ListData.of(this, readFields -> {
			var size = size();
			return new QuerySupport.AccessIterator<T>(){
				long cursor;
				@Override
				public QuerySupport.Accessor<T> next(){
					if(cursor>=size) return null;
					var i = cursor++;
					return full -> IOList.this.get(i);
				}
			};
		}));
	}
	
	void free(long index) throws IOException;
}
