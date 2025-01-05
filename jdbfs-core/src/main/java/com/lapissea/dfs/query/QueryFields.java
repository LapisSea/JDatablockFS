package com.lapissea.dfs.query;

import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.field.FieldSet;
import com.lapissea.dfs.type.field.IOField;
import com.lapissea.dfs.utils.iterableplus.Iters;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class QueryFields{
	
	private final List<IOField<?, ?>> names = new ArrayList<>(4);
	
	public <T extends IOInstance<T>> void add(Collection<IOField<T, ?>> fields){
		for(var field : fields){
			add(field);
		}
	}
	public <T extends IOInstance<T>> void add(IOField<T, ?> field){
		names.add(field);
	}
	
	public boolean isEmpty(){ return names.isEmpty(); }
	
	@SuppressWarnings({"rawtypes", "unchecked"})
	public <T extends IOInstance<T>> FieldSet<T> set(){ return FieldSet.of((Collection)names); }
	
	@Override
	public String toString(){
		return Iters.from(names).distinct().map(IOField::getName).joinAsStr(", ", "{", "}");
	}
}
