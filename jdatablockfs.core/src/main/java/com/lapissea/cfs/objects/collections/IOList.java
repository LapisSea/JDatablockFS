package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.IterablePP;
import com.lapissea.cfs.Utils;
import com.lapissea.cfs.objects.collections.listtools.IOListRangeView;
import com.lapissea.cfs.objects.collections.listtools.MappedIOList;
import com.lapissea.cfs.objects.collections.listtools.MemoryWrappedIOList;
import com.lapissea.cfs.type.field.annotations.IOValue;
import com.lapissea.util.Nullable;
import com.lapissea.util.function.FunctionOL;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

@SuppressWarnings("unused")
@IOValue.OverrideType.DefaultImpl(ContiguousIOList.class)
public interface IOList<T> extends IterablePP<T>{
	
	static <T> void elementSummary(StringJoiner sb, IOList<T> data){
		var iter=data.iterator();
		
		var push=new Consumer<String>(){
			private String repeatBuff=null;
			private int repeatCount=0;
			private long repeatIndexStart;
			private long count=0;
			
			@Override
			public void accept(String str){
				if(repeatBuff==null){
					repeatBuff=str;
					repeatCount=1;
					repeatIndexStart=count;
					return;
				}
				if(repeatBuff.equals(str)){
					repeatCount++;
					return;
				}
				
				if(repeatCount!=0){
					flush();
				}
				
				accept(str);
			}
			private void flush(){
				if(repeatCount>4){
					if(repeatIndexStart==0) sb.add(repeatBuff+" ("+repeatCount+" times)");
					else sb.add(repeatBuff+" ("+repeatCount+" times @"+repeatIndexStart+")");
				}else{
					for(int i=0;i<repeatCount;i++){
						sb.add(repeatBuff);
					}
				}
				repeatBuff=null;
				repeatCount=0;
				repeatIndexStart=0;
			}
		};
		
		while(true){
			if(!iter.hasNext()){
				push.flush();
				return;
			}
			if(sb.length()+(push.repeatCount==0?0:(push.repeatCount*(push.repeatBuff.length()+2)+8))>300){
				push.flush();
				sb.add("... "+(data.size()-push.count)+" more");
				return;
			}
			Object e;
			try{
				e=iter.ioNext();
			}catch(Throwable ex){
				e="CORRUPT: "+ex.getClass().getSimpleName();
			}
			push.count++;
			
			push.accept(Utils.toShortString(e));
		}
	}
	
	static <T> IOList<T> wrap(List<T> data){
		return new MemoryWrappedIOList<>(data, null);
	}
	static <T> IOList<T> wrap(List<T> data, Supplier<T> typeConstructor){
		return new MemoryWrappedIOList<>(data, typeConstructor);
	}
	
	static <T> long findSortedClosest(IOList<T> freeChunks, FunctionOL<T> distanceMapping) throws IOException{
		switch((int)freeChunks.size()){
			case 0 -> {return -1;}
			case 1 -> {return 0;}
		}
		
		long min=0, max=freeChunks.size()-1;
		
		long minDist=distanceMapping.apply(freeChunks.get(min));
		long maxDist=distanceMapping.apply(freeChunks.get(max));
		
		while(max-min>1){
			var mid    =(min+max)/2;
			var midDist=distanceMapping.apply(freeChunks.get(mid));
			
			if(midDist<minDist){
				minDist=midDist;
				min=mid;
				continue;
			}
			if(midDist<maxDist){
				maxDist=midDist;
				max=mid;
			}
		}
		
		return minDist<maxDist?min:max;
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
	long getCapacity() throws IOException;
	
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
	
	default IOList<T> subListView(long from, long to){
		return new IOListRangeView<>(this, from, to);
	}
	
	default <To> IOList<To> map(Function<T, To> map, Function<To, T> unmap){
		Objects.requireNonNull(map);
		Objects.requireNonNull(unmap);
		return new MappedIOList<>(this){
			protected To map(T v){return map.apply(v);}
			protected T unmap(To v){return unmap.apply(v);}
		};
	}
}