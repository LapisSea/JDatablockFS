package com.lapissea.fsf.collections.fixedlist;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

final class Transaction<E>{
	
	public E                   element;
	public int                 index;
	public FixedLenList.Action action;
	
	Transaction(){ }
	
	Transaction(FixedLenList.Action action){
		this(null, action);
	}
	
	Transaction(E element, FixedLenList.Action action){
		this(element, action, -1);
	}
	
	Transaction(FixedLenList.Action action, int index){
		this(null, action, index);
	}
	
	Transaction(E element, FixedLenList.Action action, int index){
		this();
		this.element=element;
		this.action=Objects.requireNonNull(action);
		this.index=index;
	}
	
	void commit(List<E> list) throws IOException{
		Objects.requireNonNull(action);
		
		@SuppressWarnings("unchecked")
		var c=(FixedLenList.Action.Committer<E>)action.committer;
		
		c.commit(list, this);
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
