package com.lapissea.cfs.type.field;

import com.lapissea.cfs.Utils;
import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.Struct;
import com.lapissea.cfs.type.field.fields.reflection.IOFieldPrimitive;
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
	
	private final class FieldSetIterator implements Iterator<IOField<T, ?>>{
		
		private int cursor;
		
		@Override
		public boolean hasNext(){
			return cursor<data.length;
		}
		
		@Override
		public IOField<T, ?> next(){
			return data[cursor++];
		}
	}
	
	private static final FieldSet<?> EMPTY=new FieldSet<>();
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance<T>> FieldSet<T> of(){
		return (FieldSet<T>)EMPTY;
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends IOInstance<T>> FieldSet<T> of(Stream<IOField<T, ?>> stream){
		var data=stream.map(Objects::requireNonNull).distinct().toArray(IOField[]::new);
		if(data.length==0) return of();
		return new FieldSet<>((IOField<T, ?>[])data);
	}
	
	public static <T extends IOInstance<T>> FieldSet<T> of(Collection<IOField<T, ?>> data){
		if(data==null||data.isEmpty()) return of();
		return switch(data){
			case FieldSet<T> f -> f;
			default -> of(data.stream());
		};
	}
	
	
	private final IOField<T, ?>[] data;
	private       int             hash=-1;
	
	@SuppressWarnings("unchecked")
	private FieldSet(){
		this.data=(IOField<T, ?>[])new IOField[0];
	}
	
	private FieldSet(IOField<T, ?>[] data){
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
				sb.append(Utils.toShortString(now)).append(": ");
			}
			
			sb.append(e.toShortString());
			if(!it.hasNext()){
				return sb.append(']').toString();
			}
			sb.append(',').append(' ');
		}
	}
	
	@Override
	public boolean equals(Object o){
		return this==o||
		       o instanceof FieldSet<?> that&&
		       equals(that);
	}
	public boolean equals(FieldSet<?> that){
		if(that==null) return false;
		if(this==that) return true;
		
		var len=this.data.length;
		if(that.data.length!=len) return false;
		for(int i=0;i<len;i++){
			var thisEl=this.data[i];
			var thatEl=that.data[i];
			if(!thisEl.equals(thatEl)){
				return false;
			}
		}
		return true;
	}
	
	@Override
	public int hashCode(){
		if(hash==-1){
			int hashCode=1;
			for(var e : data){
				hashCode=31*hashCode+e.hashCode();
			}
			hash=hashCode==-1?-2:hashCode;
		}
		return hash;
	}
	
	@Override
	public Spliterator<IOField<T, ?>> spliterator(){
		return new FieldSetSpliterator<>(this);
	}
	@Override
	public Iterator<IOField<T, ?>> iterator(){
		return new FieldSetIterator();
	}
	
	@Override
	public IOField<T, ?> get(int index){
		return data[index];
	}
	
	@Override
	public int size(){
		return data.length;
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
		return stream().filter(f->f.isDependency(field));
	}
	
	@Override
	public Stream<IOField<T, ?>> stream(){
		if(isEmpty()){
			return Stream.of();
		}
		return Stream.of(data);
	}
	@Override
	public Stream<IOField<T, ?>> parallelStream(){
		return stream().parallel();
	}
}
