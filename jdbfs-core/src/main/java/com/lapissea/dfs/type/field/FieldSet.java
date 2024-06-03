package com.lapissea.dfs.type.field;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldPrimitive;
import com.lapissea.dfs.utils.IterablePP;
import com.lapissea.dfs.utils.OptionalPP;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.StringJoiner;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.stream.Stream;

@SuppressWarnings({"rawtypes", "unchecked"})
public final class FieldSet<T extends IOInstance<T>> extends AbstractList<IOField<T, ?>> implements IterablePP<IOField<T, ?>>{
	
	private static final class FieldSetSpliterator<E extends IOInstance<E>> implements Spliterator<IOField<E, ?>>{
		
		private int index;
		private int fence;
		
		private final FieldSet<E> fields;
		
		private FieldSetSpliterator(FieldSet<E> list){
			this.index = 0;
			this.fence = -1;
			
			this.fields = list;
		}
		
		private FieldSetSpliterator(FieldSetSpliterator<E> parent,
		                            int origin, int fence){
			this.index = origin;
			this.fence = fence;
			
			this.fields = parent.fields;
		}
		
		private int getFence(){
			int hi;
			if((hi = fence)<0){
				hi = fence = fields.size();
			}
			return hi;
		}
		
		@Override
		public Spliterator<IOField<E, ?>> trySplit(){
			int hi = getFence(), lo = index, mid = (lo + hi) >>> 1;
			return (lo>=mid)? null : new FieldSetSpliterator<>(this, lo, index = mid);
		}
		
		@Override
		public boolean tryAdvance(Consumer<? super IOField<E, ?>> action){
			if(action == null)
				throw new NullPointerException();
			int hi = getFence(), i = index;
			if(i<hi){
				index = i + 1;
				action.accept(get(fields, i));
				return true;
			}
			return false;
		}
		
		@Override
		public void forEachRemaining(Consumer<? super IOField<E, ?>> action){
			Objects.requireNonNull(action);
			int hi = getFence();
			int i  = index;
			index = hi;
			for(; i<hi; i++){
				action.accept(get(fields, i));
			}
		}
		
		@Override
		public long estimateSize(){
			return getFence() - index;
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
		
		@Override
		public String toString(){
			StringJoiner res = new StringJoiner(", ", "FieldSet.Iter{", "}");
			if(data.length == 0){
				res.add("EMPTY");
				return res.toString();
			}
			if(hasNext()){
				var remaining = data.length - cursor;
				if(remaining>1) res.add("remaining: " + remaining);
				res.add("next: " + Utils.toShortString(data[cursor]));
			}else{
				res.add("END");
			}
			return res.toString();
		}
	}
	
	private final class FieldSetListIterator implements ListIterator<IOField<T, ?>>{
		
		private int cursor;
		
		public FieldSetListIterator(int cursor){
			this.cursor = cursor;
		}
		
		@Override
		public boolean hasNext(){
			return cursor<data.length;
		}
		
		@Override
		public IOField<T, ?> next(){
			return data[cursor++];
		}
		
		@Override
		public boolean hasPrevious(){
			return cursor != 0;
		}
		
		@Override
		public IOField<T, ?> previous(){
			int i        = cursor - 1;
			var previous = data[i];
			cursor = i;
			return previous;
		}
		
		@Override
		public int nextIndex(){
			return cursor;
		}
		
		@Override
		public int previousIndex(){
			return cursor - 1;
		}
		
		@Override
		public void remove(){ throw new UnsupportedOperationException(); }
		@Override
		public void set(IOField<T, ?> e){ throw new UnsupportedOperationException(); }
		@Override
		public void add(IOField<T, ?> e){ throw new UnsupportedOperationException(); }
		
		@Override
		public String toString(){
			StringJoiner res = new StringJoiner(", ", "FieldSet.ListIter{", "}");
			if(data.length == 0){
				res.add("EMPTY");
				return res.toString();
			}
			if(hasPrevious()){
				var previous = data[cursor - 1];
				res.add("previous: " + Utils.toShortString(previous));
			}
			if(hasNext()){
				var remaining = data.length - cursor;
				if(remaining>1) res.add("remaining: " + remaining);
				res.add("next: " + Utils.toShortString(data[cursor]));
			}else{
				res.add("END");
			}
			return res.toString();
		}
	}
	
	private static final FieldSet<?> EMPTY = new FieldSet<>(new IOField[0]);
	
	public static <T extends IOInstance<T>> FieldSet<T> of(){
		return (FieldSet<T>)EMPTY;
	}
	
	private static final class SetBuilder<T extends IOInstance<T>> implements Consumer<IOField<T, ?>>{
		private IOField<T, ?>[] safeData;
		private int             pos;
		
		private SetBuilder(int size){
			safeData = size<=0? ((FieldSet<T>)EMPTY).data : new IOField[size];
		}
		
		@Override
		public void accept(IOField<T, ?> e){
			Objects.requireNonNull(e);
			var data = safeData;
			var lPos = pos;
			for(int i = 0; i<lPos; i++){
				if(data[i].getName().equals(e.getName())){
					return;
				}
			}
			if(lPos == data.length){
				safeData = data = Arrays.copyOf(data, Math.max(lPos + 1, data.length*2));
			}
			data[lPos] = e;
			pos = lPos + 1;
		}
		
		private FieldSet<T> make(){
			var lPos = pos;
			if(lPos == 0) return of();
			var data = safeData;
			return new FieldSet<>(data.length != lPos? Arrays.copyOf(data, lPos) : data);
		}
	}
	
	public static <T extends IOInstance<T>> FieldSet<T> of(Stream<IOField<T, ?>> stream){
		var s = stream.spliterator();
		var b = new SetBuilder<T>((int)s.getExactSizeIfKnown());
		s.forEachRemaining(b);
		return b.make();
	}
	public static <T extends IOInstance<T>> FieldSet<T> of(Iterable<IOField<T, ?>> iterable){
		var b = new SetBuilder<T>(-1);
		iterable.forEach(b);
		return b.make();
	}
	
	@SafeVarargs
	public static <T extends IOInstance<T>> FieldSet<T> of(IOField<T, ?>... data){
		return of(Arrays.asList(data));
	}
	public static <T extends IOInstance<T>> FieldSet<T> of(Collection<IOField<T, ?>> data){
		if(data == null || data.isEmpty()) return of();
		if(data instanceof FieldSet<T> f) return f;
		
		var size = data.size();
		if(size == 1 && data instanceof List<IOField<T, ?>> l){
			return new FieldSet<>(new IOField[]{l.getFirst()});
		}
		
		var safeData = new SetBuilder<T>(size);
		for(var e : data){
			safeData.accept(e);
		}
		return safeData.make();
	}
	
	
	private IOField<T, ?>[] data;
	private int             hash = -1;
	private byte            age  = Byte.MIN_VALUE;
	
	private Map<String, Integer> nameLookup;
	
	private FieldSet(IOField<T, ?>[] data){
		this.data = data;
	}
	
	@Override
	public String toString(){
		if(isEmpty()) return "[]";
		
		StringJoiner sj   = new StringJoiner(", ", "[", "]");
		Struct<?>    last = null;
		for(IOField<T, ?> e : this){
			Struct<?> now = null;
			if(e.getAccessor() != null){
				now = e.getAccessor().getDeclaringStruct();
			}
			
			if(!Objects.equals(now, last)){
				last = now;
				sj.add(now == null? "<NoParent>" : now.cleanName() + ": " + e.getName());
			}else{
				sj.add(e.getName());
			}
		}
		return sj.toString();
	}
	
	@Override
	public boolean equals(Object o){
		if(this == o) return true;
		if(o instanceof FieldSet<?> that) return equals(that);
		if(o instanceof Collection<?> col) return equals(col);
		return false;
	}
	
	public boolean equals(Collection<?> collection){
		if(collection == null || collection.size() != size()) return false;
		return containsAll(collection);
	}
	
	public boolean equals(FieldSet<?> that){
		if(that == null) return false;
		if(this == that) return true;
		
		var thisData = this.data;
		var thatData = that.data;
		if(thisData == thatData) return true;
		
		var len = thisData.length;
		if(thatData.length != len) return false;
		
		int h1 = this.hash, h2 = that.hash;
		if(h1 != -1 && h2 != -1 && h1 != h2){
			return false;
		}
		
		boolean allSame = true;
		for(int i = 0; i<len; i++){
			var thisEl = thisData[i];
			var thatEl = thatData[i];
			if(thisEl == thatEl) continue;
			allSame = false;
			if(!thisEl.equals(thatEl)){
				return false;
			}
		}
		
		//Deduplicate array + short-circuit further equals
		if(allSame){
			//noinspection unchecked
			var tt = (FieldSet<T>)that;
			
			if(this.age<Byte.MAX_VALUE) this.age++;
			if(tt.age<Byte.MAX_VALUE) tt.age++;
			
			if(this.age<tt.age){
				this.data = tt.data;
				this.nameLookup = tt.nameLookup;
			}else{
				tt.data = this.data;
				tt.nameLookup = this.nameLookup;
			}
			
			if(this.hash != -1) tt.hash = this.hash;
			else if(that.hash != -1) this.hash = that.hash;
			
		}
		
		return true;
	}
	
	@Override
	public int hashCode(){
		if(hash == -1) calcHash();
		return hash;
	}
	
	private void calcHash(){
		int hashCode = 1;
		for(var e : data){
			hashCode = 31*hashCode + e.hashCode();
		}
		hash = hashCode == -1? -2 : hashCode;
	}
	
	@Override
	public int indexOf(Object o){
		if(!(o instanceof IOField<?, ?> f)) return -1;
		if(size() == 1){
			if(data[0].equals(f)){
				return 0;
			}
			return -1;
		}
		var index = getNameLookup().get(f.getName());
		if(index == null) return -1;
		if(data[index].equals(f)){
			return index;
		}
		
		for(int i = 0; i<data.length; i++){
			if(data[i].equals(f)) return i;
		}
		return -1;
	}
	
	@Override
	public int lastIndexOf(Object o){
		return indexOf(o);
	}
	
	@NotNull
	@Override
	public ListIterator<IOField<T, ?>> listIterator(int index){
		return new FieldSetListIterator(index);
	}
	
	@Override
	public void forEach(Consumer<? super IOField<T, ?>> action){
		Objects.requireNonNull(action);
		for(var e : data){
			action.accept(e);
		}
	}
	
	@Override
	public Spliterator<IOField<T, ?>> spliterator(){
		return new FieldSetSpliterator<>(this);
	}
	@NotNull
	@Override
	public Iterator<IOField<T, ?>> iterator(){
		return new FieldSetIterator();
	}
	
	@Override
	public <T> T[] toArray(IntFunction<T[]> generator){
		return super.toArray(generator);
	}
	
	@Override
	public IOField<T, ?> get(int index){
		return data[index];
	}
	
	@Override
	public int size(){
		return data.length;
	}
	
	public OptionalPP<IOField<T, ?>> byName(String name){
		return OptionalPP.ofNullable(getNameLookup().get(name)).map(this::get);
	}
	
	public IOField<T, ?> requireByName(String name){
		return byName(name).orElseThrow();
	}
	
	private Map<String, Integer> getNameLookup(){
		var nl = nameLookup;
		if(nl == null) nameLookup = nl = buildNameLookup();
		return nl;
	}
	private Map<String, Integer> buildNameLookup(){
		var builder = HashMap.<String, Integer>newHashMap(size());
		for(int i = 0; i<size(); i++){
			builder.put(get(i).getName(), i);
		}
		return Map.copyOf(builder);
	}
	
	@Override
	public boolean contains(Object o){
		if(!(o instanceof IOField<?, ?> f)) return false;
		return contains(f);
	}
	public boolean contains(IOField<?, ?> f){
		if(size() == 1){
			return data[0].equals(f);
		}
		
		var index = getNameLookup().get(f.getName());
		if(index == null) return false;
		if(data[index].equals(f)){
			return true;
		}
		return super.contains(f);
	}
	
	@SuppressWarnings("unchecked")
	public <E> IterablePP<IOField<T, E>> byType(Class<E> type){
		return filtered(f -> UtilL.instanceOf(f.getType(), type)).map(f -> (IOField<T, E>)f);
	}
	
	public <E extends IOField<T, ?>> IterablePP<? extends E> byFieldType(Class<E> type){
		return filtered(type::isInstance).map(type::cast);
	}
	
	@SuppressWarnings("unchecked")
	public IterablePP<RefField<T, ?>> onlyRefs(){
		return (IterablePP<RefField<T, ?>>)(Object)filtered(e -> e instanceof RefField);
	}
	
	public <E> IOField<T, E> requireExact(Class<E> type, String name){
		return exact(type, name).orElseThrow();
	}
	
	public <E> OptionalPP<IOField<T, E>> exact(Class<E> type, String name){
		return byType(type).firstMatching(f -> f.getName().equals(name));
	}
	
	public <E extends IOField<T, ?>> OptionalPP<? extends E> exactFieldType(Class<E> type, String name){
		return byName(name).filter(type::isInstance).map(type::cast);
	}
	public <E extends IOField<T, ?>> E requireExactFieldType(Class<E> type, String name){
		var res = exactFieldType(type, name);
		if(res.isEmpty()){
			throw new NoSuchElementException("Field \"" + name + "\" of type " + type.getName() + " is not present");
		}
		return res.get();
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
		return stream().filter(f -> f.isDependency(field));
	}
	
	@Override
	public Stream<IOField<T, ?>> stream(){
		if(isEmpty()){
			return Stream.empty();
		}
		return Arrays.stream(data);
	}
	@Override
	public Stream<IOField<T, ?>> parallelStream(){
		return stream().parallel();
	}
}
