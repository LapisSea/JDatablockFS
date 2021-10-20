package com.lapissea.cfs.type;

import com.lapissea.cfs.type.field.IOField;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.util.*;
import java.util.stream.Stream;

public final class FieldSet<T extends IOInstance<T>> extends AbstractList<IOField<T, ?>>{
	
	private final List<IOField<T, ?>> data;
	
	public FieldSet(Stream<IOField<T, ?>> stream){
		this.data=stream.distinct().toList();
	}
	
	public FieldSet(Collection<IOField<T, ?>> data){
		this.data=switch(data){
			case null -> throw new NullPointerException("data can not be null");
			case FieldSet<T> f -> f.data;
			default -> List.copyOf(data);
		};
	}
	
	@Override
	public String toString(){
		if(isEmpty()) return "[]";
		
		var it=iterator();
		if(!it.hasNext())
			return "[]";
		
		StringBuilder sb=new StringBuilder();
		sb.append('[');
		Struct<?> last=null;
		while(true){
			var e=it.next();
			
			Struct<?> now=null;
			if(e.getAccessor()!=null){
				now=e.getAccessor().getDeclaringStruct();
			}
			
			if(!Objects.equals(now, last)){
				last=now;
				sb.append(TextUtil.toShortString(now)).append(": ");
			}
			
			sb.append(e.toShortString());
			if(!it.hasNext()){
				return sb.append(']').toString();
			}
			sb.append(',').append(' ');
		}
	}
	@Override
	public IOField<T, ?> get(int index){
		return data.get(index);
	}
	@Override
	public int size(){
		return data.size();
	}
	
	public Optional<IOField<T, ?>> byName(String name){
		return stream().filter(f->f.getName().equals(name)).findAny();
	}
	
	@SuppressWarnings("unchecked")
	public <E> Stream<IOField<T, E>> byType(Class<E> type){
		return stream().filter(f->UtilL.instanceOf(f.getAccessor().getType(), type)).map(f->(IOField<T, E>)f);
	}
	
	public <E extends IOField<T, ?>> Stream<? extends E> byFieldType(Class<E> type){
		return stream().filter(type::isInstance).map(type::cast);
	}
	
	public <E extends IOField<T, ?>> Iterable<? extends E> byFieldTypeIter(Class<E> type){
		return ()->stream().filter(type::isInstance).map(type::cast).iterator();
	}
	
	public <E> IOField<T, E> requireExact(Class<E> type, String name){
		return exact(type, name).orElseThrow();
	}
	
	public <E> Optional<IOField<T, E>> exact(Class<E> type, String name){
		return byType(type).filter(f->f.getName().equals(name)).findAny();
	}
	
	public <E extends IOField<T, ?>> Optional<? extends E> exactFieldType(Class<E> type, String name){
		return byFieldType(type).filter(f->f.getName().equals(name)).findAny();
	}
	public <E extends IOField<T, ?>> E requireExactFieldType(Class<E> type, String name){
		return exactFieldType(type, name).orElseThrow();
	}
	
	public Stream<IOField<T, ?>> unpackedStream(){
		return stream().flatMap(IOField::streamUnpackedFields);
	}
	public FieldSet<T> unpacked(){
		return new FieldSet<>(unpackedStream());
	}
}
