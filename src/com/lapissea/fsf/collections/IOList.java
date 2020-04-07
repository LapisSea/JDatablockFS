package com.lapissea.fsf.collections;

import com.lapissea.fsf.Header;
import com.lapissea.fsf.chunk.Chunk;
import com.lapissea.fsf.chunk.ChunkLink;
import com.lapissea.fsf.chunk.ChunkPointer;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeFunction;

import java.io.IOException;
import java.util.AbstractList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

public interface IOList<E> extends List<E>{
	
	record Ref<E>(
		IOList<E>owner,
		int index
	){
		public E getFake(){
			return owner.get(index);
		}
		
		public E get() throws IOException{
			return owner.getElement(index);
		}
		
		public E set(E value) throws IOException{
			owner.setElement(index, value);
			return owner.getElement(index);
		}
		
		public void delete() throws IOException{
			owner.removeElement(index);
		}
		
		public E modify(UnsafeFunction<E, E, IOException> modifier) throws IOException{
			var oldE=get();
			var newE=modifier.apply(oldE);
			return set(newE);
		}
		
		@Override
		public String toString(){
			try{
				return "Ref{"+owner.get(index)+'}';
			}catch(Exception e){
				return TextUtil.toString(this);
			}
		}
	}
	
	@SuppressWarnings("deprecation")
	abstract class Abstract<E> extends AbstractList<E> implements IOList<E>{
		
		/**
		 * @deprecated replaced by {@link #getElement(int)}
		 */
		@Deprecated
		@Override
		public E get(int index){
			try{
				return getElement(index);
			}catch(IOException e){
				throw UtilL.uncheckedThrow(e);
			}
		}
		
		/**
		 * @deprecated replaced by {@link #addElement(Object)}
		 */
		@Deprecated
		@Override
		public boolean add(E e){
			try{
				addElement(e);
				return true;
			}catch(IOException e1){
				throw UtilL.uncheckedThrow(e1);
			}
		}
		
		/**
		 * @deprecated replaced by {@link #setElement(int, Object)}
		 */
		@Deprecated
		@Override
		public E set(int index, E element){
			try{
				E old=getElement(index);
				setElement(index, element);
				return old;
			}catch(IOException e1){
				throw UtilL.uncheckedThrow(e1);
			}
		}
		
		/**
		 * @deprecated replaced by {@link #removeElement(int)}
		 */
		@Deprecated
		@Override
		public E remove(int index){
			try{
				E old=getElement(index);
				removeElement(index);
				return old;
			}catch(IOException e1){
				throw UtilL.uncheckedThrow(e1);
			}
		}
		
		/**
		 * @deprecated replaced by {@link #clearElements()}
		 */
		@Deprecated
		@Override
		public void clear(){
			try{
				clearElements();
			}catch(IOException e1){
				throw UtilL.uncheckedThrow(e1);
			}
		}
	}
	
	E getElement(int index) throws IOException;
	
	void addElement(E element) throws IOException;
	
	void setElement(int index, E element) throws IOException;
	
	void removeElement(int index) throws IOException;
	
	void clearElements() throws IOException;
	
	/**
	 * @deprecated replaced by {@link #getElement(int)}
	 */
	@Deprecated
	@Override
	default E get(int index){
		try{
			return getElement(index);
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
	/**
	 * @deprecated replaced by {@link #addElement(Object)}
	 */
	@Deprecated
	@Override
	default boolean add(E e){
		try{
			addElement(e);
			return true;
		}catch(IOException e1){
			throw UtilL.uncheckedThrow(e1);
		}
	}
	
	/**
	 * @deprecated replaced by {@link #setElement(int, Object)}
	 */
	@Deprecated
	@Override
	default E set(int index, E element){
		try{
			E old=getElement(index);
			setElement(index, element);
			return old;
		}catch(IOException e1){
			throw UtilL.uncheckedThrow(e1);
		}
	}
	
	/**
	 * @deprecated replaced by {@link #removeElement(int)}
	 */
	@Deprecated
	@Override
	default E remove(int index){
		try{
			E old=getElement(index);
			removeElement(index);
			return old;
		}catch(IOException e1){
			throw UtilL.uncheckedThrow(e1);
		}
	}
	
	/**
	 * @deprecated replaced by {@link #clearElements()}
	 */
	@Deprecated
	@Override
	default void clear(){
		try{
			clearElements();
		}catch(IOException e1){
			throw UtilL.uncheckedThrow(e1);
		}
	}
	
	default E findSingle(Predicate<E> comparator) throws IOException{
		int index=findIndex(comparator);
		if(index==-1) return null;
		return getElement(index);
	}
	
	default int findIndex(Predicate<E> comparator) throws IOException{
		for(int i=0;i<size();i++){
			E e=getElement(i);
			if(comparator.test(e)) return i;
		}
		return -1;
	}
	
	default void modifyElement(int index, Function<E, E> modifier) throws IOException{
		E ay=getElement(index);
		ay=modifier.apply(ay);
		setElement(index, ay);
	}
	
	interface PointerConverter<V>{
		
		@SuppressWarnings("rawtypes")
		PointerConverter DUMMY=make(v->null, (o, v)->{throw new UnsupportedOperationException();});
		
		@SuppressWarnings("unchecked")
		static <V> PointerConverter<V> getDummy(){
			return DUMMY;
		}
		
		static <V> PointerConverter<V> make(Function<V, ChunkPointer> getter, BiFunction<V, ChunkPointer, V> setter){
			return new PointerConverter<>(){
				@Override
				public <I> ChunkPointer get(Header<I> header, V value){
					return getter.apply(value);
				}
				
				@Override
				public <I> V set(Header<I> header, V oldValue, ChunkPointer newPointer){
					return setter.apply(oldValue, newPointer);
				}
			};
		}
		
		/**
		 * @return chunk that the value points to
		 */
		<I> ChunkPointer get(Header<I> header, V value);
		
		/**
		 * @return value that will point to newPointer
		 */
		<I> V set(Header<I> header, V oldValue, ChunkPointer newPointer);
	}
	
	Stream<ChunkLink> openLinkStream(PointerConverter<E> converter) throws IOException;
	
	Chunk getData();
	
	void checkIntegrity() throws IOException;
	
	default Ref<E> makeReference(int index){
		return new Ref<>(this, index);
	}
}
