package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.Utils;
import com.lapissea.util.Nullable;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@SuppressWarnings("unused")
public interface IOList<T> extends IterablePP<T>{
	
	class MemoryWrappedIOList<T> implements IOList<T>{
		
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
			T t=typeConstructor.get();
			if(initializer!=null){
				initializer.accept(t);
			}
			add(t);
			return t;
		}
		
		@Override
		public void addMultipleNew(long count, UnsafeConsumer<T, IOException> initializer) throws IOException{
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
		public String toString(){
			return stream().map(Utils::toShortString).collect(Collectors.joining(", ", "Ram[", "]"));
		}
	}
	
	abstract class MappedIOList<From, To> implements IOList<To>{
		private final IOList<From> data;
		
		protected MappedIOList(IOList<From> data){
			this.data=data;
		}
		
		
		protected abstract To map(From v);
		protected abstract From unmap(To v);
		
		
		@Override
		public long size(){
			return data.size();
		}
		@Override
		public To get(long index) throws IOException{
			var v=data.get(index);
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
			return map(data.addNew(from->initializer.accept(map(from))));
		}
		@Override
		public void addMultipleNew(long count, UnsafeConsumer<To, IOException> initializer) throws IOException{
			data.addMultipleNew(count, from->initializer.accept(map(from)));
		}
		
		@Override
		public void clear() throws IOException{
			data.clear();
		}
		
		@Override
		public String toString(){
			return stream().map(Utils::toShortString).collect(Collectors.joining(", ", "[", "]"));
		}
		
		@Override
		public Stream<To> stream(){
			return data.stream().map(this::map);
		}
		
		@Override
		public Optional<To> first(){
			return data.first().map(this::map);
		}
		
		@Override
		public Optional<To> peekFirst() throws IOException{
			return data.peekFirst().map(this::map);
		}
		
		@Override
		public Optional<To> popFirst() throws IOException{
			return data.popFirst().map(this::map);
		}
		
		@Override
		public Optional<To> peekLast() throws IOException{
			return data.peekLast().map(this::map);
		}
		
		@Override
		public Optional<To> popLast() throws IOException{
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
			
			if(this==o){
				return true;
			}
			if(!(o instanceof IOList<?> that)){
				return false;
			}
			
			var siz=size();
			if(siz!=that.size()){
				return false;
			}
			
			var iThis=iterator();
			var iThat=that.iterator();
			
			for(long i=0;i<siz;i++){
				var vThis=iThis.next();
				var vThat=iThat.next();
				
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
			List<From> mapped=new ArrayList<>(values.size());
			for(To v : values){
				mapped.add(unmap(v));
			}
			data.addAll(mapped);
		}
		
		@Override
		public IOIterator.Iter<To> iterator(){
			return new IOIterator.Iter<>(){
				private final IOIterator.Iter<From> src=data.iterator();
				@Override
				public boolean hasNext(){
					return src.hasNext();
				}
				@Override
				public To ioNext() throws IOException{
					return map(src.ioNext());
				}
				@Override
				public To next(){
					return map(src.next());
				}
				@Override
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
			return new IOListIterator<>(){
				private final IOListIterator<From> src=data.listIterator(startIndex);
				@Override
				public boolean hasNext(){
					return src.hasNext();
				}
				@Override
				public To ioNext() throws IOException{
					return map(src.ioNext());
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
			data.modify(index, obj->unmap(modifier.apply(map(obj))));
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
		public boolean contains(To value) throws IOException{
			return data.contains(unmap(value));
		}
		@Override
		public long indexOf(To value) throws IOException{
			return data.indexOf(unmap(value));
		}
	}
	
	static <T> IOList<T> wrap(List<T> data, Supplier<T> typeConstructor){
		return new MemoryWrappedIOList<>(data, typeConstructor);
	}
	
	static <From, To> IOList<To> map(IOList<From> data, Function<From, To> map, Function<To, From> unmap){
		Objects.requireNonNull(data);
		Objects.requireNonNull(map);
		Objects.requireNonNull(unmap);
		return new MappedIOList<>(data){
			protected To map(From v){return map.apply(v);}
			protected From unmap(To v){return unmap.apply(v);}
		};
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
			var index=list.size();
			list.add(value);
			return index;
		}
		
		long lo=0;
		long hi=list.size()-1;
		
		while(lo<=hi){
			long mid=(hi+lo)/2;
			
			int comp=value.compareTo(list.get(mid));
			if(comp<0){
				hi=mid-1;
			}else if(comp>0){
				lo=mid+1;
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
		for(long cycleStart=0;cycleStart<list.size()-1;cycleStart++){
			T item=list.get(cycleStart);
			
			// Find where to put the item.
			long pos=cycleStart;
			for(long i=cycleStart+1;i<list.size();i++){
				if(list.get(i).compareTo(item)<0) pos++;
			}
			
			// If the item is already there, this is not a cycle.
			if(pos==cycleStart) continue;
			
			// Otherwise, put the item there or right after any duplicates.
			while(item.equals(list.get(pos))){
				pos++;
			}
			{
				T temp=list.get(pos);
				list.set(pos, item);
				
				list.set(cycleStart, temp);
				cycleStart--;
			}
		}
	}
	
	interface IOIterator<T>{
		interface Iter<T> extends IOIterator<T>, Iterator<T>{
			@Override
			default T next(){
				try{
					return ioNext();
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
			@Override
			default void remove(){
				try{
					ioRemove();
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
		}
		
		boolean hasNext();
		T ioNext() throws IOException;
		default void ioRemove() throws IOException{
			throw new UnsupportedOperationException(getClass().toString());
		}
	}
	
	
	interface IOListIterator<T> extends IOIterator<T>{
		
		abstract class AbstractIndex<T> implements IOListIterator<T>{
			
			private long cursor;
			private long lastRet=-1;
			
			public AbstractIndex(long cursorStart){
				this.cursor=cursorStart;
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
				var i   =cursor;
				var next=getElement(i);
				lastRet=i;
				cursor=i+1;
				return next;
			}
			@Override
			public boolean hasPrevious(){
				return cursor!=0;
			}
			
			@Override
			public T ioPrevious() throws IOException{
				var i       =cursor-1;
				var previous=getElement(i);
				lastRet=cursor=i;
				return previous;
			}
			
			@Override
			public long nextIndex(){return cursor;}
			@Override
			public long previousIndex(){return cursor-1;}
			
			@Override
			public void ioRemove() throws IOException{
				if(lastRet<0) throw new IllegalStateException();
				
				removeElement(lastRet);
				
				if(lastRet<cursor) cursor--;
				lastRet=-1;
			}
			
			@Override
			public void ioSet(T t) throws IOException{
				if(lastRet<0) throw new IllegalStateException();
				setElement(lastRet, t);
			}
			
			@Override
			public void ioAdd(T t) throws IOException{
				var i=cursor;
				addElement(i, t);
				lastRet=-1;
				cursor=i+1;
			}
		}
		
		@Override
		boolean hasNext();
		@Override
		T ioNext() throws IOException;
		
		boolean hasPrevious();
		T ioPrevious() throws IOException;
		
		long nextIndex();
		long previousIndex();
		
		@Override
		void ioRemove() throws IOException;
		void ioSet(T t) throws IOException;
		void ioAdd(T t) throws IOException;
	}
	
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
		requestCapacity(size()+values.size());
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
				this.index=origin;
				this.size=size;
			}
			
			public Spliterator<T> trySplit(){
				long lo=index, mid=(lo+size) >>> 1;
				return (lo>=mid)?null: // divide range in half unless too small
				       new RandomAccessIteratorSpliterator(lo, index=mid);
			}
			
			public boolean tryAdvance(Consumer<? super T> action){
				if(action==null) throw new NullPointerException();
				long i=index;
				if(i<size){
					index=i+1;
					action.accept(get(getIterator(i), i));
					return true;
				}
				return false;
			}
			
			public void forEachRemaining(Consumer<? super T> action){
				Objects.requireNonNull(action);
				long i   =index;
				var  iter=getIterator(i);
				index=size;
				for(;i<size;i++){
					action.accept(get(iter, i));
				}
			}
			
			public long estimateSize(){
				return size-index;
			}
			
			public int characteristics(){
				return Spliterator.ORDERED|Spliterator.SIZED|Spliterator.SUBSIZED;
			}
			
			private IOListIterator<T> getIterator(long i){
				var iter=iterator;
				if(iter==null) iterator=iter=listIterator(i);
				return iter;
			}
			
			private static <E> E get(IOListIterator<E> list, long i){
				if(i!=list.nextIndex()) throw new AssertionError(i+" "+(list.nextIndex()));
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
			private final long              size=size();
			private       long              index;
			
			public IteratorSpliterator(long start){
				it=listIterator(start);
			}
			
			
			@Override
			public boolean tryAdvance(Consumer<? super T> action){
				if(!it.hasNext()){
					return false;
				}
				T val;
				try{
					val=it.ioNext();
				}catch(IOException e){
					throw new RuntimeException("Failed to provide element: "+(it.nextIndex()-1), e);
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
				return size-index;
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
			long index=0;
			@Override
			public boolean hasNext(){
				var siz=size();
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
	
	default IOListIterator<T> listIterator(){return listIterator(0);}
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
		T oldObj=get(index);
		T newObj=modifier.apply(oldObj);
		if(oldObj==newObj||!oldObj.equals(newObj)){
			set(index, newObj);
		}
	}
	
	default boolean isEmpty(){
		return size()==0;
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
	default void requestCapacity(long capacity) throws IOException{}
	
	default boolean contains(T value) throws IOException{
		return indexOf(value)!=-1;
	}
	
	default long indexOf(T value) throws IOException{
		var  iter =iterator();
		long index=0;
		while(iter.hasNext()){
			var el=iter.ioNext();
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
		var first=get(0);
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
		return Optional.of(get(0));
	}
	default Optional<T> popLast() throws IOException{
		if(isEmpty()) return Optional.empty();
		var index=size()-1;
		var first=get(index);
		remove(index);
		return Optional.of(first);
	}
	
	default void pushLast(T newLast) throws IOException{
		add(newLast);
	}
	
	class IOListView<T> implements IOList<T>{
		
		private final IOList<T> data;
		private final long      from;
		private final long      to;
		private final long      subSize;
		
		public IOListView(IOList<T> data, long from, long to){
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
			return new IOListView<>(data, this.from+from, this.from+to);
		}
	}
	
	default IOList<T> subListView(long from, long to){
		return new IOListView<>(this, from, to);
		
	}
}
