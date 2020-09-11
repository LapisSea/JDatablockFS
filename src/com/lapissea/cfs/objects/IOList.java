package com.lapissea.cfs.objects;

import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeFunction;
import com.lapissea.util.function.UnsafePredicate;

import java.io.IOException;
import java.util.Iterator;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public interface IOList<T> extends Iterable<T>{
	
	int size();
	
	default boolean isEmpty(){
		return size()==0;
	}
	
	T getElement(int index) throws IOException;
	void setElement(int index, T value) throws IOException;
	
	default void modifyElement(int index, UnsafeFunction<T, T, IOException> value) throws IOException{
		T oldValue=getElement(index);
		T newValue=value.apply(oldValue);
		if(!Objects.equals(oldValue, newValue)){
			setElement(index, newValue);
		}
	}
	
	void ensureCapacity(int elementCapacity) throws IOException;
	
	void removeElement(int index) throws IOException;
	
	void addElement(int index, T value) throws IOException;
	default void addElement(T value) throws IOException{ addElement(size(), value); }
	
	
	default T pop() throws IOException{
		var end=size()-1;
		if(end<0) return null;
		
		T val=getElement(end);
		removeElement(end);
		return val;
	}
	
	default void clear() throws IOException{
		while(!isEmpty()){
			removeElement(size()-1);
		}
	}
	
	default int indexOf(T value) throws IOException{
		return indexOf(value::equals);
	}
	
	default int indexOf(UnsafePredicate<T, IOException> matcher) throws IOException{
		for(int i=0;i<size();i++){
			var el=getElement(i);
			if(matcher.test(el)){
				return i;
			}
		}
		return -1;
	}
	
	default Stream<T> stream(){
		return StreamSupport.stream(spliterator(), false);
	}
	
	@NotNull
	@Override
	default Iterator<T> iterator(){
		return new Iterator<>(){
			int cursor;
			
			@Override
			public boolean hasNext(){
				return cursor<size();
			}
			@Override
			public T next(){
				T val;
				try{
					val=getElement(cursor++);
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
				return val;
			}
			@Override
			public void remove(){
				try{
					removeElement(--cursor);
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
			
		};
	}
	
	void validate() throws IOException;
}
