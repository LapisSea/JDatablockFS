package com.lapissea.fsf.collections;

import com.lapissea.fsf.ShadowChunks;
import com.lapissea.util.UtilL;

import java.io.IOException;
import java.util.AbstractList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

public interface IOList<E> extends List<E>, ShadowChunks{
	
	abstract class Abstract<E> extends AbstractList<E> implements IOList<E>{
		@Override
		public E get(int index){
			try{
				return getElement(index);
			}catch(IOException e){
				throw UtilL.uncheckedThrow(e);
			}
		}
	}
	
	E getElement(int index) throws IOException;
	
	void addElement(E element) throws IOException;
	
	void setElement(int index, E element) throws IOException;
	
	void removeElement(int index) throws IOException;
	
	void clearElements() throws IOException;
	
	@Deprecated
	@Override
	default E get(int index){
		try{
			return getElement(index);
		}catch(IOException e){
			throw UtilL.uncheckedThrow(e);
		}
	}
	
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
}
