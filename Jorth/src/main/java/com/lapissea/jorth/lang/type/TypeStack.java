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
		return stack.removeLast();
	}
	
	public boolean isEmpty(){
		if(!stack.isEmpty()) return false;
		if(parent == null) return true;
		return parent.isEmpty();
	}
	
	public int size(){
		if(parent == null) return stack.size();
		return parent.size() + stack.size();
	}
	
	public void requireElements(int count) throws MalformedJorth{
		if(stack.size()>=count) return;
		throw new MalformedJorth("Required at least " + count + " " + TextUtil.plural("element", count) + " on the stack");
	}
	public GenericType peekLast(){
		return stack.getLast();
	}
	public GenericType peek(int pos){
		if(parent == null){
			return stack.get(pos);
		}
		int localPos = pos - parent.size();
		if(localPos<0) return parent.peek(pos);
		return stack.get(localPos);
	}
}
