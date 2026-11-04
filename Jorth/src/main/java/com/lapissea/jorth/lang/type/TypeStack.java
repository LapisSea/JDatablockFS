package com.lapissea.jorth.lang.type;

import com.lapissea.jorth.exceptions.MalformedJorth;
import com.lapissea.util.TextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TypeStack{
	
	private final List<GenericType> stack = new ArrayList<>();
	private final TypeStack         parent;
	
	public TypeStack(TypeStack parent){
		this.parent = parent;
	}
	
	public TypeStack getParent(){
		return parent;
	}
	
	@Override
	public String toString(){
		return totalStack().map(GenericType::toString).collect(Collectors.joining(", ", "[", "]"));
	}
	
	public Stream<GenericType> totalStack(){
		if(parent == null) return stack.stream();
		return Stream.concat(parent.totalStack(), stack.stream());
	}
	
	public void push(GenericType type){
		stack.add(type);
	}
	public GenericType pop() throws MalformedJorth{
		requireElements(1);
		if(stack.isEmpty()){
			throw new MalformedJorth("can not pop values outside the code path");
		}
		return stack.removeLast();
	}
	
	public boolean isEmpty(){
		if(!stack.isEmpty()) return false;
		if(parent == null) return true;
		return parent.isEmpty();
	}
	
	public int size(){
		if(parent == null) return stack.size();
		return stack.size() + parent.size();
	}
	
	public void requireElements(int count) throws MalformedJorth{
		if(stack.size()>=count) return;
		if(totalStack().count()>=count) return;
		throw new MalformedJorth("Required at least " + count + " " + TextUtil.plural("element", count) + " on the stack");
	}
	public GenericType peekLast() throws MalformedJorth{
		requireElements(1);
		if(stack.isEmpty()){
			return parent.peekLast();
		}
		return stack.getLast();
	}
	public GenericType peek(int pos) throws MalformedJorth{
		if(pos<0 || pos>=size()){
			throw new MalformedJorth("peek position " + pos + " is out of bounds for the stack of size " + size());
		}
		if(parent == null){
			return stack.get(pos);
		}
		int localPos = pos - parent.size();
		if(localPos<0) return parent.peek(pos);
		return stack.get(localPos);
	}
	
	public List<GenericType> getLocalPortion(){
		return stack;
	}
	
	/**
	 * Copies all inherited and local entries into an independent stack with no parent.
	 */
	public TypeStack copyFlat(){
		var copy = new TypeStack(null);
		totalStack().forEach(copy::push);
		return copy;
	}
	
	@Override
	public TypeStack clone(){
		var stack = new TypeStack(parent);
		stack.stack.addAll(this.stack);
		return stack;
	}
	
	@Override
	public boolean equals(Object obj){
		return obj instanceof TypeStack other &&
		       totalStack().toList().equals(other.totalStack().toList());
	}
}
