package com.lapissea.dfs.core.chunk;

import com.lapissea.dfs.objects.ChunkPointer;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.utils.IterableLongPP;
import com.lapissea.dfs.utils.IterablePP;
import com.lapissea.dfs.utils.Iters;
import com.lapissea.dfs.utils.LongIterator;
import com.lapissea.util.NotNull;
import org.roaringbitmap.IntConsumer;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.longlong.Roaring64NavigableMap;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Stream;

import static com.lapissea.dfs.config.GlobalConfig.DEBUG_VALIDATION;

@SuppressWarnings({"SimplifyStreamApiCallChains", "unused"})
public final class ChunkSet implements Set<ChunkPointer>{
	
	private sealed interface Index{
		final class Bitmap32 implements Index{
			private final RoaringBitmap data = new RoaringBitmap();
			
			@Override
			public boolean isEmpty(){
				return data.isEmpty();
			}
			@Override
			public void add(long index){
				data.add(Math.toIntExact(index));
			}
			@Override
			public boolean contains(long index){
				if(index>Integer.MAX_VALUE || index<0) return false;
				return data.contains((int)index);
			}
			
			@Override
			public void remove(long index){
				if(index>Integer.MAX_VALUE) return;
				data.remove((int)index);
			}
			@Override
			public void clear(){
				data.clear();
			}
			@Override
			public void or(Index other){
				var d = ((Bitmap32)other).data;
				data.or(d);
			}
			@Override
			public void and(Index other){
				var d = ((Bitmap32)other).data;
				data.and(d);
			}
			@Override
			public void andNot(Index other){
				var d = ((Bitmap32)other).data;
				data.andNot(d);
			}
			
			private static IterableLongPP iter(RoaringBitmap data){
				return () -> {
					var iter = data.getIntIterator();
					return new LongIterator(){
						@Override
						public boolean hasNext(){
							return iter.hasNext();
						}
						@Override
						public long nextLong(){
							return iter.next();
						}
					};
				};
			}
			
			@Override
			public IterableLongPP iter(){
				return iter(data);
			}
			
			@Override
			public long calcEnd(){
				return data.getReverseIntIterator().next() + 1;
			}
			@Override
			public long calcStart(){
				return data.getIntIterator().next();
			}
		}
		
		final class Bitmap64 implements Index{
			private final Roaring64NavigableMap data = new Roaring64NavigableMap();
			
			@Override
			public boolean isEmpty(){
				return data.isEmpty();
			}
			@Override
			public void add(long index){
				data.addLong(index);
			}
			@Override
			public boolean contains(long index){
				return data.contains(index);
			}
			
			@Override
			public void remove(long index){
				data.removeLong(index);
			}
			@Override
			public void clear(){
				data.clear();
			}
			@Override
			public void or(Index other){
				var d = ((Bitmap64)other).data;
				data.or(d);
			}
			@Override
			public void and(Index other){
				var d = ((Bitmap64)other).data;
				data.and(d);
			}
			@Override
			public void andNot(Index other){
				var d = ((Bitmap64)other).data;
				data.andNot(d);
			}
			
			private static IterableLongPP iter(Roaring64NavigableMap data){
				return () -> {
					var iter = data.getLongIterator();
					return new LongIterator(){
						@Override
						public boolean hasNext(){
							return iter.hasNext();
						}
						@Override
						public long nextLong(){
							return iter.next();
						}
					};
				};
			}
			
			@Override
			public IterableLongPP iter(){
				return iter(data);
			}
			
			@Override
			public long calcEnd(){
				return data.getReverseLongIterator().next() + 1;
			}
			@Override
			public long calcStart(){
				return data.getLongIterator().next();
			}
		}
		
		boolean isEmpty();
		
		void add(long index);
		boolean contains(long index);
		void remove(long index);
		void clear();
		
		void or(Index other);
		void and(Index other);
		void andNot(Index other);
		
		IterableLongPP iter();
		
		long calcEnd();
		long calcStart();
	}
	
	private Index index;
	
	private long end   = -1;
	private long start = -1;
	private long size;
	
	public ChunkSet(){
	}
	
	public ChunkSet(Stream<ChunkPointer> data){
		data.forEach(this::add);
	}
	public ChunkSet(Collection<ChunkPointer> data){
		addAll(data);
	}
	public ChunkSet(IOList<ChunkPointer> data) throws IOException{
		var iter = data.iterator();
		while(iter.hasNext()){
			add(iter.ioNext());
		}
	}
	public ChunkSet(Iterable<ChunkPointer> data){
		data.forEach(this::add);
	}
	public ChunkSet(ChunkPointer... data){
		this(Arrays.asList(data));
	}
	public ChunkSet(long... data){
		this(Arrays.stream(data).mapToObj(ChunkPointer::of));
	}
	public ChunkSet(int... data){
		this(Arrays.stream(data).mapToObj(ChunkPointer::of));
	}
	
	
	public ChunkPointer first(){
		return ChunkPointer.of(start);
	}
	public ChunkPointer last(){
		return ChunkPointer.of(lastIndex());
	}
	
	public long lastIndex(){
		return end - 1;
	}
	@Override
	public boolean isEmpty(){
		return size == 0;
	}
	public long rageSize(){
		return end - start;
	}
	
	/**
	 * @deprecated Replace with {@link ChunkSet#trueSize()}
	 */
	@Override
	@Deprecated
	public int size(){
		return Math.toIntExact(size);
	}
	public long trueSize(){
		return size;
	}
	
	public void add(Chunk chunk){ add(chunk.getPtr()); }
	@Override
	public boolean add(ChunkPointer ptr){
		return add(ptr.getValue());
	}
	@Override
	public boolean containsAll(@NotNull Collection<?> c){
		return ptrs(c).allMatch(this::contains);
	}
	private IterablePP<ChunkPointer> ptrs(Collection<?> c){
		return Iters.from(c).map(o -> switch(o){
			case ChunkPointer p -> p;
			case Chunk ch -> ch.getPtr();
			default -> null;
		}).filtered(Objects::nonNull);
	}
	@Override
	public boolean addAll(Collection<? extends ChunkPointer> c){
		if(c.isEmpty()) return false;
		
		var toAdd = ptrsToIndex(c);
		
		boolean change = calcStart(toAdd)<start || calcEnd(toAdd)>end;
		
		if(!change){
			change = index == null || toAdd.iter().anyMatch(i -> !index.contains(i));
		}
		if(!change) return false;
		if(index == null) index = toAdd;
		else index.or(toAdd);
		
		return true;
	}
	
	private Index to64(Index index){
		return switch(index){
			case Index.Bitmap32 b32 -> {
				var b64 = new Index.Bitmap64();
				b32.data.forEach((IntConsumer)b64.data::addLong);
				yield b64;
			}
			case Index.Bitmap64 b64 -> b64;
		};
	}
	
	private Index ptrsToIndex(Iterable<? extends ChunkPointer> c){
		return switch(index){
			case Index.Bitmap32 ignored -> {
				Index bitmap = new Index.Bitmap32();
				for(var ptr : c){
					var v = ptr.getValue();
					if(v>Integer.MAX_VALUE){
						bitmap = to64(bitmap);
					}
					bitmap.add(v);
				}
				yield bitmap;
			}
			case Index.Bitmap64 ignored -> {
				var  bitmap = new Index.Bitmap64();
				long count  = 0;
				for(var ptr : c){
					bitmap.add(ptr.getValue());
					count++;
				}
				yield bitmap;
			}
		};
	}
	
	private long calcEnd(Index toAdd){
		return toAdd == null? 0 : toAdd.calcEnd();
	}
	private long calcStart(Index toAdd){
		return toAdd == null? 0 : toAdd.calcStart();
	}
	private void recalcInfo(){
		calcStart();
		calcEnd();
		start = index == null? 0 : index.iter().count();
	}
	
	@Override
	public boolean retainAll(Collection<?> c){
		if(index == null) return false;
		if(c.isEmpty()){
			boolean hadData = !isEmpty();
			clear();
			return hadData;
		}
		
		var toRetain = ptrsToIndex(ptrs(c));
		
		if(toRetain.isEmpty()){
			return retainAll(List.of());
		}
		
		if(toRetain.equals(index)){
			return false;
		}
		
		index.and(toRetain);
		recalcInfo();
		return true;
	}
	
	@Override
	public boolean removeAll(@NotNull Collection<?> c){
		if(index == null || c.isEmpty()) return false;
		
		var toRemove = ptrsToIndex(ptrs(c));
		
		if(toRemove.isEmpty()) return false;
		
		index.andNot(toRemove);
		if(index.isEmpty()){
			start = -1;
			end = -1;
			size = 0;
			return true;
		}
		recalcInfo();
		return true;
	}
	
	public boolean add(long ptr){
		if(contains(ptr)) return false;
		
		if(isEmpty()){
			start = ptr;
			end = ptr + 1;
		}else{
			end = Math.max(end, ptr + 1);
			start = Math.min(start, ptr);
		}
		if(index == null){
			if(ptr>Integer.MAX_VALUE) index = new Index.Bitmap64();
			else index = new Index.Bitmap32();
		}
		size++;
		if(ptr>Integer.MAX_VALUE && index instanceof Index.Bitmap32){
			index = to64(index);
		}
		index.add(ptr);
		
		if(DEBUG_VALIDATION) checkData();
		return true;
	}
	
	@SuppressWarnings({"ReplaceInefficientStreamCount"})
	private void checkData(){
		if(size() != longIter().count()){
			throw new IllegalStateException(size() + " " + stream().count());
		}
		if(!isEmpty()){
			var start = calcStart(index);
			var end   = calcEnd(index);
			if(start != this.start) throw new IllegalStateException("corrupt start: " + this.start + " " + index.toString());
			if(end != this.end) throw new IllegalStateException("corrupt end: " + lastIndex() + " " + index);
		}
	}
	
	public boolean remove(Chunk chunk)     { return remove(chunk.getPtr()); }
	public boolean remove(ChunkPointer ptr){ return remove(ptr.getValue()); }
	@Override
	public boolean remove(Object o){ return o instanceof ChunkPointer ptr && remove(ptr.getValue()); }
	public boolean remove(long ptr){
		checkEmpty();
		if(!contains(ptr)) return false;
		
		if(rageSize() == 1){
			clear();
		}else{
			if(!index.contains(ptr)) throw new IllegalStateException("contains() invalid");
			size--;
			index.remove(ptr);
			
			if(ptr == start) calcStart();
			if(ptr == lastIndex()) calcEnd();
		}
		if(DEBUG_VALIDATION) checkData();
		return true;
	}
	
	private void calcEnd(){
		end = calcEnd(index);
	}
	private void calcStart(){
		start = calcStart(index);
	}
	
	public void removeFirst(){
		checkEmpty();
		if(rageSize() == 1){
			clear();
		}else{
			if(!index.contains(Math.toIntExact(start))) throw new IllegalStateException("First element must exist");
			index.remove(Math.toIntExact(start));
			size--;
			calcStart();
		}
		if(DEBUG_VALIDATION) checkData();
	}
	
	public void removeLast(){
		checkEmpty();
		if(rageSize() == 1){
			clear();
		}else{
			if(!index.contains(Math.toIntExact(lastIndex()))) throw new IllegalStateException("Last element must exist");
			index.remove((int)lastIndex());
			size--;
			calcEnd();
		}
		if(DEBUG_VALIDATION) checkData();
	}
	
	@Override
	public void clear(){
		start = -1;
		end = -1;
		size = 0;
		index = null;
	}
	
	public boolean contains(Chunk chunk)     { return contains(chunk.getPtr()); }
	public boolean contains(ChunkPointer ptr){ return contains(ptr.getValue()); }
	@Override
	public boolean contains(Object o){ return o instanceof ChunkPointer ptr && contains(ptr.getValue()); }
	public boolean contains(long ptr){
		if(index == null) return false;
		var size = rageSize();
		if(size == 0) return false;
		if(size == 1) return ptr == start;
		
		var last = lastIndex();
		if(ptr<start || last<ptr) return false;
		if(ptr == start || ptr == last) return true;
		return index.contains(ptr);
	}
	
	public IterableLongPP longIter(){
		return index == null? IterableLongPP.empty() : index.iter();
	}
	@Override
	public Stream<ChunkPointer> stream(){
		return longIter().mapToObj(ChunkPointer::of).stream();
	}
	
	public OptionalLong optionalMin(){
		return isEmpty()? OptionalLong.empty() : OptionalLong.of(min());
	}
	public OptionalLong optionalMax(){
		return isEmpty()? OptionalLong.empty() : OptionalLong.of(max());
	}
	public long min(){
		return start;
	}
	
	public long max(){
		return lastIndex();
	}
	
	@NotNull
	@Override
	public Iterator<ChunkPointer> iterator(){
		return stream().iterator();
	}
	@Override
	public Object[] toArray(){
		return stream().toArray(ChunkPointer[]::new);
	}
	
	@SuppressWarnings("unchecked")
	@Override
	public <T> T[] toArray(@NotNull T[] a){
		if(trueSize()>Integer.MAX_VALUE) throw new OutOfMemoryError();
		int size = (int)this.size;
		T[] r    = a.length>=size? a : (T[])Array.newInstance(a.getClass().getComponentType(), size);
		int i    = 0;
		for(ChunkPointer ptr : this){
			r[i++] = (T)ptr;
		}
		return r;
	}
	
	private void checkEmpty(){
		if(isEmpty()) throw new IllegalStateException();
	}
	
	@Override
	public String toString(){
		return longIter().limit(50).joinAsStrings(", ", "*[", size()>50? "...]" : "]");
	}
}
