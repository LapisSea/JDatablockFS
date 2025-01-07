package com.lapissea.dfs.type.field;

import com.lapissea.dfs.Utils;
import com.lapissea.dfs.type.IOInstance;
import com.lapissea.dfs.type.Struct;
import com.lapissea.dfs.type.field.fields.RefField;
import com.lapissea.dfs.type.field.fields.reflection.IOFieldPrimitive;
import com.lapissea.dfs.utils.OptionalPP;
import com.lapissea.dfs.utils.iterableplus.IterablePP;
import com.lapissea.dfs.utils.iterableplus.IterablePPSource;
import com.lapissea.dfs.utils.iterableplus.Iters;
import com.lapissea.util.NotNull;
import com.lapissea.util.UtilL;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.ListIterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.SequencedCollection;
import java.util.Spliterator;
import java.util.StringJoiner;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.ToIntFunction;
import java.util.stream.Stream;

@SuppressWarnings({"rawtypes", "unchecked"})
public final class FieldSet<T extends IOInstance<T>> extends AbstractList<IOField<T, ?>> implements IterablePPSource<IOField<T, ?>>{
	
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
			return iterStr("FieldSet.Iter", false, hasNext(), cursor);
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
			return iterStr("FieldSet.ListIter", hasPrevious(), hasNext(), cursor);
		}
	}
	
	private String iterStr(String name, boolean prev, boolean next, int cursor){
		var res = new StringJoiner(", ", name + "{", "}");
		if(data.length == 0){
			res.add("EMPTY");
			return res.toString();
		}
		if(prev){
			var previous = data[cursor - 1];
			res.add("previous: " + Utils.toShortString(previous));
		}
		if(next){
			var remaining = data.length - cursor;
			if(remaining>1) res.add("remaining: " + remaining);
			res.add("next: " + Utils.toShortString(data[cursor]));
		}else{
			res.add("END");
		}
		return res.toString();
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
			var data  = safeData;
			var lPos  = pos;
			var fName = e.getName();
			for(int i = 0; i<lPos; i++){
				if(data[i].getName().equals(fName)){
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
	
	public static <T extends IOInstance<T>> FieldSet<T> of(Iterable<IOField<T, ?>> iterable){
		var b = new SetBuilder<T>(IterablePP.SizedPP.tryGetUnknown(iterable).orElse(-1));
		iterable.forEach(b);
		return b.make();
	}
	
	public static <T extends IOInstance<T>> FieldSet<T> of(IOField<T, ?> field){
		Objects.requireNonNull(field);
		return new FieldSet<>(new IOField[]{field});
	}
	@SafeVarargs
	public static <T extends IOInstance<T>> FieldSet<T> of(IOField<T, ?>... data){
		return of(Arrays.asList(data));
	}
	public static <T extends IOInstance<T>> FieldSet<T> of(Collection<IOField<T, ?>> data){
		if(data == null || data.isEmpty()) return of();
		if(data instanceof FieldSet<T> f) return f;
		
		var size = data.size();
		if(size == 1 && data instanceof SequencedCollection<IOField<T, ?>> l){
			return of(l.getFirst());
		}
		
		var safeData = new SetBuilder<T>(size);
		for(var e : data){
			safeData.accept(e);
		}
		return safeData.make();
	}
	
	
	private IOField<T, ?>[] data;
	private int             hash = -1;
	private boolean         shared;
	
	private ToIntFunction<String> nameLookup;
	
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
		
		var iter = collection.iterator();
		int i    = 0;
		while(iter.hasNext()){
			var o = iter.next();
			if(o == null || data.length == i){
				return false;
			}
			var el = data[i++];
			if(!el.equals(o)){
				return false;
			}
		}
		return true;
	}
	
	public boolean equals(FieldSet<?> that){
		if(that == null) return false;
		if(this == that) return true;
		
		IOField<?, ?>[] thisData = this.data, thatData = that.data;
		if(thisData == thatData) return true;
		
		var len = thisData.length;
		if(thatData.length != len) return false;
		
		int h1 = this.hash, h2 = that.hash;
		if(h1 != -1 && h2 != -1 && h1 != h2){
			return false;
		}
		
		boolean allSame = true;
		for(int i = 0; i<len; i++){
			IOField<?, ?> thisEl = thisData[i], thatEl = thatData[i];
			if(thisEl == thatEl) continue;
			allSame = false;
			if(!thisEl.equals(thatEl)){
				return false;
			}
		}
		
		//Deduplicate array + short-circuit further equals
		if(allSame){
			share((FieldSet<T>)that);
		}
		
		return true;
	}
	private void share(FieldSet<T> that){
		boolean thisSh = this.shared, thatSh = that.shared;
		if(thisSh && !thatSh){
			that.shared = true;
			that.data = this.data;
		}else if(!thisSh && thatSh){
			this.shared = true;
			this.data = that.data;
		}else{
			that.shared = this.shared = true;
			that.data = this.data;
		}
		
		if(this.hash != -1) that.hash = this.hash;
		else if(that.hash != -1) this.hash = that.hash;
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
		return indexOf(f);
	}
	public int indexOf(IOField<?, ?> f){
		if(f == null) return -1;
		var index = indexByName(f.getName());
		if(index == -1) return -1;
		if(data[index].equals(f)){
			return index;
		}
		return oddNameFind(f);
	}
	private int oddNameFind(IOField<?, ?> f){
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
	@Override
	public IOField<T, ?> getFirst(){
		return data[0];
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
		var idx = indexByName(name);
		return idx == -1? OptionalPP.empty() : OptionalPP.of(data[idx]);
	}
	
	public IOField<T, ?> requireByName(String name){
		var idx = indexByName(name);
		if(idx == -1) failName(name);
		return data[idx];
	}
	private static void failName(String name){
		throw new NoSuchElementException("Field with name " + name + " is not present");
	}
	
	private int indexByName(String name){
		return switch(data.length){
			case 0 -> -1;
			case 1 -> data[0].getName().equals(name)? 0 : -1;
			default -> {
				var nl = nameLookup;
				if(nl == null) nl = buildNameLookup();
				yield nl.applyAsInt(name);
			}
		};
		
	}
	private ToIntFunction<String> buildNameLookup(){
		var data = iter().map(IOField::getName).enumerate().toMap(IterablePP.Idx::val, IterablePP.Idx::index);
		
		//Only makes sense to try to make a perfect map when there are no hash collisions
		if(!Iters.keys(data).mapToInt(String::hashCode).hasDuplicates()){
			var perfect = buildNameLookupPerfect(data);
			if(perfect != null) return nameLookup = perfect;
		}
		return nameLookup = name -> data.getOrDefault(name, -1);
	}
	
	private final class PerfectLookup implements ToIntFunction<String>{
		private final int   off;
		private final int   modulo;
		private final int[] values;
		
		private PerfectLookup(int off, int modulo, int[] values){
			this.off = off;
			this.modulo = modulo;
			this.values = values;
		}
		
		@Override
		public int applyAsInt(String str){
			if(str == null) return -1;
			
			var idx = perfectIdx(str, modulo, off);
			if(idx<0 || idx>=values.length) return -1;
			
			var id = values[idx];
			if(id == -1) return -1;
			
			var name = data[id].getName();
			if(!name.equals(str)) return -1;
			
			return id;
		}
	}
	
	private ToIntFunction<String> buildNameLookupPerfect(Map<String, Integer> builder){
		int perfectSize  = builder.size(), limit = Math.min(perfectSize*3, perfectSize + 64);
		var perfectMarks = new BitSet(limit);
		wh:
		while(perfectSize<=limit){
			for(var e : builder.entrySet()){
				var name = e.getKey();
				var idx  = perfectIdx(name, perfectSize);
				if(perfectMarks.get(idx)){
					perfectMarks.clear();
					perfectSize++;
					continue wh;
				}
				perfectMarks.set(idx);
			}
			
			var bounds = Iters.ofInts(perfectMarks.stream().toArray()).bounds().orElseThrow();
			
			var ids = new int[bounds.max() - bounds.min() + 1];
			Arrays.fill(ids, -1);
			
			var off = bounds.min();
			for(var e : builder.entrySet()){
				ids[perfectIdx(e.getKey(), perfectSize, off)] = e.getValue();
			}
			return new PerfectLookup(off, perfectSize, ids);
		}
		return null;
	}
	
	private static int perfectIdx(String name, int size, int off){ return perfectIdx(name, size) - off; }
	private static int perfectIdx(String name, int size)         { return Math.floorMod(name.hashCode(), size); }
	
	@Override
	public boolean contains(Object o){
		if(!(o instanceof IOField<?, ?> f)) return false;
		return contains(f);
	}
	public boolean contains(IOField<?, ?> f){
		if(f == null) return false;
		return switch(data.length){
			case 0 -> false;
			case 1 -> data[0].equals(f);
			default -> {
				var index = indexByName(f.getName());
				if(index == -1) yield false;
				if(data[index].equals(f)){
					yield true;
				}
				yield super.contains(f);
			}
		};
		
	}
	
	@SuppressWarnings("unchecked")
	public <E> IterablePP<IOField<T, E>> byType(Class<E> type){
		return filtered(f -> UtilL.instanceOf(f.getType(), type)).map(f -> (IOField<T, E>)f);
	}
	
	@SuppressWarnings("unchecked")
	public IterablePP<RefField<T, ?>> onlyRefs(){
		return (IterablePP<RefField<T, ?>>)(Object)filtered(e -> e instanceof RefField);
	}
	
	public <E> IOField<T, E> requireExact(Class<E> type, String name){
		return exact(type, name).orElseThrow();
	}
	
	public <E> Optional<IOField<T, E>> exact(Class<E> type, String name){
		return byType(type).firstMatching(f -> f.getName().equals(name));
	}
	
	public <E extends IOField<T, ?>> OptionalPP<? extends E> exactFieldType(Class<E> type, String name){
		return byName(name).tryCast(type);
	}
	public <E extends IOField<T, ?>> E requireExactFieldType(Class<E> type, String name){
		var res = exactFieldType(type, name);
		if(res.isEmpty()){
			throw new NoSuchElementException("Field \"" + name + "\" of type " + type.getName() + " is not present");
		}
		return res.get();
	}
	public IOFieldPrimitive.FLong<T> requireExactLong(String name){
		//noinspection unchecked
		return requireExactFieldType(IOFieldPrimitive.FLong.class, name);
	}
	public IOFieldPrimitive.FInt<T> requireExactInt(String name){
		//noinspection unchecked
		return requireExactFieldType(IOFieldPrimitive.FInt.class, name);
	}
	public IOFieldPrimitive.FBoolean<T> requireExactBoolean(String name){
		//noinspection unchecked
		return requireExactFieldType(IOFieldPrimitive.FBoolean.class, name);
	}
	
	public IterablePP<IOField<T, ?>> unpackedStream(){
		return flatMapped(IOField::iterUnpackedFields);
	}
	
	public IterablePP<IOField<T, ?>> iterDependentOn(IOField<T, ?> field){
		return filtered(f -> f.isDependency(field));
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
	
	@Override
	public OptionalInt tryGetSize(){
		return OptionalInt.of(size());
	}
	
	@Override
	public IterablePP.SizedPP<IOField<T, ?>> iter(){
		return Iters.from(data);
	}
}
