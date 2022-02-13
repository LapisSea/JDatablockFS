package com.lapissea.cfs.type;

import com.lapissea.cfs.type.field.IOField;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldPrimitive;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;

public final class FieldSet<T extends IOInstance<T>> extends AbstractList<IOField<T, ?>>{
	
	private static final class FieldSetSpliterator<E extends IOInstance<E>> implements Spliterator<IOField<E, ?>>{
		
		private int index;
		private int fence;
		
		private final FieldSet<E> fields;
		
		private FieldSetSpliterator(FieldSet<E> list){
			this.index=0;
			this.fence=-1;
			
			this.fields=list;
		}
		
		private FieldSetSpliterator(FieldSetSpliterator<E> parent,
		                            int origin, int fence){
			this.index=origin;
			this.fence=fence;
			
			this.fields=parent.fields;
		}
		
		private int getFence(){
			int hi;
			if((hi=fence)<0){
				hi=fence=fields.size();
			}
			return hi;
		}
		
		@Override
		public Spliterator<IOField<E, ?>> trySplit(){
			int hi=getFence(), lo=index, mid=(lo+hi) >>> 1;
			return (lo>=mid)?null:new FieldSetSpliterator<>(this, lo, index=mid);
		}
		
		@Override
		public boolean tryAdvance(Consumer<? super IOField<E, ?>> action){
			if(action==null)
				throw new NullPointerException();
			int hi=getFence(), i=index;
			if(i<hi){
				index=i+1;
				action.accept(get(fields, i));
				return true;
			}
			return false;
		}
		
		@Override
		public void forEachRemaining(Consumer<? super IOField<E, ?>> action){
			Objects.requireNonNull(action);
			int hi=getFence();
			int i =index;
			index=hi;
			for(;i<hi;i++){
				action.accept(get(fields, i));
			}
		}
		
		@Override
		public long estimateSize(){
			return getFence()-index;
		}
		
		@Override
		public int characteristics(){
			return ORDERED|SIZED|SUBSIZED|DISTINCT|IMMUTABLE|NONNULL;
		}
		
		private static <E extends IOInstance<E>> IOField<E, ?> get(FieldSet<E> list, int i){
			try{
				return list.get(i);
			}catch(IndexOutOfBoundsException ex){
				throw new ConcurrentModificationException();
			}
		}
		
	}
	
	private static final FieldSet<?> EMPTY=new FieldSet<>(List.of());
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance<T>> FieldSet<T> of(){
		return (FieldSet<T>)EMPTY;
	}
	public static <T extends IOInstance<T>> FieldSet<T> of(Stream<IOField<T, ?>> stream){
		var list=stream.filter(Objects::nonNull).distinct().toList();
		if(list.isEmpty()) return of();
		return new FieldSet<>(list);
	}
	public static <T extends IOInstance<T>> FieldSet<T> of(Collection<IOField<T, ?>> data){
		if(data==null||data.isEmpty()) return of();
		return switch(data){
			case FieldSet<T> f -> f;
			default -> of(data.stream());
		};
	}
	
	
	private final List<IOField<T, ?>> data;
	
	private FieldSet(List<IOField<T, ?>> data){
		this.data=data;
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
	public Spliterator<IOField<T, ?>> spliterator(){
		return new FieldSetSpliterator<>(this);
	}
	@Override
	public IOField<T, ?> get(int index){
		return data.get(index);
	}
	
	public IOField<T, ?> getLast(){
		return data.get(data.size()-1);
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
	public <E extends IOInstance<E>> IOFieldPrimitive.FLong<E> requireExactLong(String name){
		//noinspection unchecked
		return requireExactFieldType(IOFieldPrimitive.FLong.class, name);
	}
	public <E extends IOInstance<E>> IOFieldPrimitive.FInt<E> requireExactInt(String name){
		//noinspection unchecked
		return requireExactFieldType(IOFieldPrimitive.FInt.class, name);
	}
	public <E extends IOInstance<E>> IOFieldPrimitive.FBoolean<E> requireExactBoolean(String name){
		//noinspection unchecked
		return requireExactFieldType(IOFieldPrimitive.FBoolean.class, name);
	}
	
	public Stream<IOField<T, ?>> unpackedStream(){
		return stream().flatMap(IOField::streamUnpackedFields);
	}
	public FieldSet<T> unpacked(){
		return FieldSet.of(unpackedStream());
	}
	
	public Stream<IOField<T, ?>> streamDependentOn(IOField<T, ?> field){
		return stream().filter(f->f.getDependencies().contains(field));
	}
}
