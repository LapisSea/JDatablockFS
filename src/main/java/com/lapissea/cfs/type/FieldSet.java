package com.lapissea.cfs.type;

import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.UtilL;

import java.util.AbstractList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public final class FieldSet<T extends IOInstance<T>, ValueType> extends AbstractList<IOField<T, ? extends ValueType>>{
	
	private final List<IOField<T, ? extends ValueType>> data;
	
	public FieldSet(Stream<IOField<T, ? extends ValueType>> stream){
		this.data=stream.distinct().toList();
	}
	
	public FieldSet(Collection<IOField<T, ? extends ValueType>> data){
		this.data=List.copyOf(data);
	}
	
	@Override
	public IOField<T, ? extends ValueType> get(int index){
		return data.get(index);
	}
	@Override
	public int size(){
		return data.size();
	}
	
	public Optional<IOField<T, ? extends ValueType>> byName(String name){
		return stream().filter(f->f.getName().equals(name)).findAny();
	}
	
	@SuppressWarnings("unchecked")
	public <E> Stream<IOField<T, E>> byType(Class<E> type){
		return stream().filter(f->UtilL.instanceOf(f.getAccessor().getType(), type)).map(f->(IOField<T, E>)f);
	}
	
	public <E extends IOField<T, ?>> Stream<? extends E> byFieldType(Class<E> type){
		return stream().filter(type::isInstance).map(type::cast);
	}
	
	public <E> Optional<IOField<T, E>> exact(Class<E> type, String name){
		return byType(type).filter(f->f.getName().equals(name)).findAny();
	}
}
