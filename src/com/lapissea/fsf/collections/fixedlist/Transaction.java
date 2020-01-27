package com.lapissea.fsf.collections.fixedlist;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static com.lapissea.util.UtilL.*;

final class Transaction<E>{
	
	public E      element;
	public int    index;
	public Action action;
	
	Transaction(){ }
	
	Transaction(Action action){
		this(null, action);
	}
	
	Transaction(E element, Action action){
		this(element, action, -1);
	}
	
	Transaction(Action action, int index){
		this(null, action, index);
	}
	
	Transaction(E element, Action action, int index){
		this();
		this.element=element;
		this.action=Objects.requireNonNull(action);
		this.index=index;
	}
	
	void commit(FixedLenList<?, E> list) throws IOException{
		switch(action){
		case ADD -> list.applyAdd(element);
		case REMOVE -> list.applyRemove(index);
		case SET -> list.applySet(index, element);
		case CLEAR -> list.applyClear();
		}
	}
	
	void commit(List<E> list){
		Objects.requireNonNull(action);
		Assert(!(list instanceof FixedLenList));
		
		switch(action){
		case ADD -> list.add(element);
		case REMOVE -> list.remove(index);
		case SET -> list.set(index, element);
		case CLEAR -> list.clear();
		}
	}
	
	@Override
	public String toString(){
		return action.toString.apply(this);
	}
	
	@Override
	public boolean equals(Object o){
		if(this==o) return true;
		if(!(o instanceof Transaction)) return false;
		Transaction<?> that=(Transaction<?>)o;
		return index==that.index&&
		       Objects.equals(element, that.element)&&
		       action==that.action;
	}
	
	@Override
	public int hashCode(){
		int result=1;
		
		result=31*result+(element==null?0:element.hashCode());
		result=31*result+Integer.hashCode(index);
		result=31*result+action.hashCode();
		
		return result;
	}
}
