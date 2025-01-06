package com.lapissea.dfs.query;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class QueryFields{
	
	private final List<IOField<?, ?>> fields = new ArrayList<>(4);
	
	private boolean markedUnknown;
	
	public void markUnknown(){
		markedUnknown = true;
		fields.clear();
	}
	
	public void add(Collection<? extends IOField<?, ?>> fields){
		if(markedUnknown) return;
		this.fields.addAll(fields);
	}
	public void add(IOField<?, ?> field){
		if(markedUnknown) return;
		fields.add(field);
	}
	
	public boolean isEmpty(){ return markedUnknown || fields.isEmpty(); }
	
	@SuppressWarnings({"rawtypes", "unchecked"})
	public <T extends IOInstance<T>> FieldSet<T> set(){
		return markedUnknown? FieldSet.of() : FieldSet.of((Collection)fields);
	}
	
	@Override
	public String toString(){
		if(markedUnknown) return "{???}";
		return Iters.from(fields).distinct().map(IOField::getName).joinAsStr(", ", "{", "}");
	}
}
