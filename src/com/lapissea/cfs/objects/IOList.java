package com.lapissea.cfs.objects;

import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeFunction;
import com.lapissea.util.function.UnsafeIntConsumer;
import com.lapissea.util.function.UnsafePredicate;

import java.io.IOException;
import java.util.Iterator;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public interface IOList<T> extends Iterable<T>{
	
	class Boxed<From, To> implements IOList<To>{
		private final IOList<From> data;
		
		private final Function<From, To> unboxer;
		private final Function<To, From> boxer;
		public Boxed(IOList<From> data, Function<From, To> unboxer, Function<To, From> boxer){
			this.data=data;
			this.unboxer=unboxer;
			this.boxer=boxer;
		}
		@Override
		public String toString(){
			return stream().map(TextUtil.SHORT_TO_STRINGS::toString).collect(Collectors.joining(", ", "[", "]"));
		}
		
		@Override
		public int hashCode(){
			return data.hashCode();
		}
		
		@Override
		public int size(){
			return data.size();
		}
		
		@Override
		public To getElement(int index) throws IOException{
			return unboxer.apply(data.getElement(index));
		}
		@Override
		public void setElement(int index, To value) throws IOException{
			data.setElement(index, boxer.apply(value));
		}
		@Override
		public void ensureCapacity(int elementCapacity) throws IOException{
			data.ensureCapacity(elementCapacity);
		}
		@Override
		public void removeElement(int index) throws IOException{
			data.removeElement(index);
		}
		@Override
		public void addElement(int index, To value) throws IOException{
			data.addElement(index, boxer.apply(value));
		}
		@Override
		public void validate() throws IOException{
			data.validate();
		}
		@Override
		public void free() throws IOException{
			data.free();
		}
		
		@Override
		public void addElement(To value) throws IOException{
			data.addElement(boxer.apply(value));
		}
		@Override
		public To pop() throws IOException{
			return unboxer.apply(data.pop());
		}
		@Override
		public boolean isEmpty(){
			return data.isEmpty();
		}
		@Override
		public void modifyElement(int index, UnsafeFunction<To, To, IOException> modifier) throws IOException{
			data.modifyElement(index, e->boxer.apply(modifier.apply(unboxer.apply(e))));
		}
		@Override
		public void clear() throws IOException{
			data.clear();
		}
		
		@Override
		public boolean contains(To ptr) throws IOException{
			return data.contains(boxer.apply(ptr));
		}
		@Override
		public int indexOf(To value) throws IOException{
			return data.indexOf(boxer.apply(value));
		}
		@Override
		public int find(UnsafePredicate<To, IOException> matcher) throws IOException{
			return data.find(e->matcher.test(unboxer.apply(e)));
		}
		@Override
		public int indexOfLast(To value) throws IOException{
			return data.indexOfLast(boxer.apply(value));
		}
		@Override
		public int findLast(UnsafePredicate<To, IOException> matcher) throws IOException{
			return data.findLast(e->matcher.test(unboxer.apply(e)));
		}
		@Override
		public int count(UnsafePredicate<To, IOException> matcher) throws IOException{
			return data.count(e->matcher.test(unboxer.apply(e)));
		}
		
		@Override
		public Stream<To> stream(){
			return data.stream().map(unboxer);
		}
		
		@NotNull
		@Override
		public Iterator<To> iterator(){
			Iterator<From> source=data.iterator();
			return new Iterator<>(){
				@Override
				public boolean hasNext(){
					return source.hasNext();
				}
				@Override
				public To next(){
					return unboxer.apply(source.next());
				}
				@Override
				public void remove(){
					source.remove();
				}
			};
		}
		
		public IOList<From> getUnboxed(){
			return data;
		}
	}
	
	static <From, To> IOList<To> box(IOList<From> data, Function<From, To> unboxer, Function<To, From> boxer){
		return new Boxed<>(data, unboxer, boxer);
	}
	
	int size();
	
	default boolean isEmpty(){
		return size()==0;
	}
	
	T getElement(int index) throws IOException;
	void setElement(int index, T value) throws IOException;
	
	default void modifyElement(int index, UnsafeFunction<T, T, IOException> modifier) throws IOException{
		T oldValue=getElement(index);
		T newValue=modifier.apply(oldValue);
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
	
	default boolean contains(T ptr) throws IOException{
		return indexOf(ptr)!=-1;
	}
	
	default int indexOf(T value) throws IOException{
		return find(v->Objects.equals(v, value));
	}
	
	default void find(UnsafePredicate<T, IOException> matcher, UnsafeIntConsumer<IOException> onFound) throws IOException{
		int index=find(matcher);
		if(index!=-1){
			onFound.accept(index);
		}
	}
	
	default int find(UnsafePredicate<T, IOException> matcher) throws IOException{
		for(int i=0;i<size();i++){
			var el=getElement(i);
			if(matcher.test(el)){
				return i;
			}
		}
		return -1;
	}
	
	default int indexOfLast(T value) throws IOException{
		return findLast(v->Objects.equals(v, value));
	}
	
	default int findLast(UnsafePredicate<T, IOException> matcher) throws IOException{
		for(int i=size()-1;i>=0;i--){
			var el=getElement(i);
			if(matcher.test(el)){
				return i;
			}
		}
		return -1;
	}
	
	default int count(UnsafePredicate<T, IOException> matcher) throws IOException{
		int count=0;
		for(T el : this){
			if(matcher.test(el)){
				count++;
			}
		}
		return count;
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
	
	void free() throws IOException;
}
