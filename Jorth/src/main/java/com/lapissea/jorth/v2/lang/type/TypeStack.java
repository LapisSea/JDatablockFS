package com.lapissea.jorth.v2.lang.type;

import com.lapissea.jorth.MalformedJorthException;
import com.lapissea.util.TextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TypeStack{
	
	private final List<GenericType> stack=new ArrayList<>();
	private final TypeStack         parent;
	
	public TypeStack(TypeStack parent){
		this.parent=parent;
	}
	
	@Override
	public String toString(){
		return totalStack().map(GenericType::toString).collect(Collectors.joining(", ", "[", "]"));
	}
	
	public Stream<GenericType> totalStack(){
		if(parent==null) return stack.stream();
		return Stream.concat(parent.totalStack(), stack.stream());
	}
	
	public void push(GenericType type){
		stack.add(type);
	}
	public GenericType pop() throws MalformedJorthException{
		requireElements(1);
		return stack.remove(stack.size()-1);
	}
	
	public boolean isEmpty(){
		if(!stack.isEmpty()) return false;
		if(parent==null) return true;
		return parent.isEmpty();
	}
	
	public void requireElements(int count) throws MalformedJorthException{
		if(stack.size()>=count) return;
		throw new MalformedJorthException("Required at least "+count+" "+TextUtil.plural("element", count)+" on the stack");
	}
}
