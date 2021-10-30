package com.lapissea.cfs.objects.collections;

import com.lapissea.cfs.IterablePP;
import com.lapissea.util.Nullable;
import com.lapissea.util.function.UnsafeConsumer;

import java.io.IOException;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.Function;

@SuppressWarnings("unused")
public interface IOList<T> extends IterablePP<T>{
	
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
		}
		
		boolean hasNext();
		T ioNext() throws IOException;
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
	
	void remove(long index) throws IOException;
	
	@Override
	default Spliterator<T> spliterator(){
		final class IteratorSpliterator implements Spliterator<T>{
			
			private final Iterator<T> it  =iterator();
			private final long        size=size();
			
			@Override
			public boolean tryAdvance(Consumer<? super T> action){
				if(!it.hasNext()){
					return false;
				}
				var val=it.next();
				action.accept(val);
				return true;
			}
			@Override
			public Spliterator<T> trySplit(){
				return null;
			}
			@Override
			public long estimateSize(){
				return size;
			}
			@Override
			public int characteristics(){
				return Spliterator.ORDERED|Spliterator.SIZED;
			}
		}
		
		return new IteratorSpliterator();
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
	
	default void modify(long index, Function<T, T> modifier) throws IOException{
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
	
	/**
	 * The list can allocate space for data that may come later here.
	 * It is not required to do so but is desirable. No exact capacity allocation is required.
	 */
	default void requestCapacity(long capacity) throws IOException{}
}
