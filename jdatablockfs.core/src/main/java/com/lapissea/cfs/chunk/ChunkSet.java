package com.lapissea.cfs.chunk;

import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.cfs.objects.collections.IOList;
import com.lapissea.util.NotNull;
import org.roaringbitmap.RoaringBitmap;
import org.roaringbitmap.longlong.Roaring64Bitmap;

import java.io.IOException;
import java.lang.reflect.Array;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static com.lapissea.cfs.GlobalConfig.DEBUG_VALIDATION;

@SuppressWarnings({"SimplifyStreamApiCallChains", "unused"})
public final class ChunkSet implements Set<ChunkPointer>{
	
	
	private sealed interface Index{
		final class Bitmap32 implements Index{
			private final RoaringBitmap data=new RoaringBitmap();
			
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
				if(index>Integer.MAX_VALUE||index<0) return false;
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
				var d=((Bitmap32)other).data;
				data.or(d);
			}
			@Override
			public void and(Index other){
				var d=((Bitmap32)other).data;
				data.and(d);
			}
			@Override
			public void andNot(Index other){
				var d=((Bitmap32)other).data;
				data.andNot(d);
			}
			@Override
			public LongStream stream(){
				return data.stream().mapToLong(i->i);
			}
			@Override
			public long calcEnd(){
				return data.getReverseIntIterator().next()+1;
			}
			@Override
			public long calcStart(){
				return data.getIntIterator().next();
			}
		}
		
		final class Bitmap64 implements Index{
			private final Roaring64Bitmap data=new Roaring64Bitmap();
			
			@Override
			public boolean isEmpty(){
				return data.isEmpty();
			}
			@Override
			public void add(long index){
				data.add(index);
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
				var d=((Bitmap64)other).data;
				data.or(d);
			}
			@Override
			public void and(Index other){
				var d=((Bitmap64)other).data;
				data.and(d);
			}
			@Override
			public void andNot(Index other){
				var d=((Bitmap64)other).data;
				data.andNot(d);
			}
			@Override
			public LongStream stream(){
				return data.stream();
			}
			@Override
			public long calcEnd(){
				return data.getReverseLongIterator().next()+1;
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
		
		LongStream stream();
		
		
		long calcEnd();
		long calcStart();
	}
	
	private static final Supplier<Index> NEW_INDEX=Index.Bitmap32::new;
	
	private final Index index=NEW_INDEX.get();
	
	private long end  =-1;
	private long start=-1;
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
		var iter=data.iterator();
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
		return end-1;
	}
	@Override
	public boolean isEmpty(){
		return size==0;
	}
	public long rageSize(){
		return end-start;
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
	
	public void add(Chunk chunk){add(chunk.getPtr());}
	@Override
	public boolean add(ChunkPointer ptr){
		return add(ptr.getValue());
	}
	@Override
	public boolean containsAll(@NotNull Collection<?> c){
		return ptrStream(c).allMatch(this::contains);
	}
	private Stream<ChunkPointer> ptrStream(Collection<?> c){
		return c.stream().map(o->o instanceof ChunkPointer ptr?ptr:o instanceof Chunk ch?ch.getPtr():null).filter(Objects::nonNull);
	}
	@Override
	public boolean addAll(Collection<? extends ChunkPointer> c){
		if(c.isEmpty()) return false;
		
		var toAdd=ptrsToIndex(c.stream());
		
		boolean change=calcStart(toAdd)<start||calcEnd(toAdd)>end;
		
		if(!change){
			change=toAdd.stream().anyMatch(i->!index.contains(i));
		}
		if(!change) return false;
		
		index.or(toAdd);
		return true;
	}
	
	private Index ptrsToIndex(Stream<? extends ChunkPointer> c){
		var bitmap=NEW_INDEX.get();
		c.mapToInt(ChunkPointer::getValueInt).forEach(bitmap::add);
		return bitmap;
	}
	
	private long calcEnd(Index toAdd){
		return toAdd.calcEnd();
	}
	private long calcStart(Index toAdd){
		return toAdd.calcStart();
	}
	private void recalcInfo(){
		calcStart();
		calcEnd();
		start=index.stream().count();
	}
	
	@Override
	public boolean retainAll(Collection<?> c){
		if(c.isEmpty()){
			boolean hadData=!isEmpty();
			clear();
			return hadData;
		}
		
		var toRetain=ptrsToIndex(ptrStream(c));
		
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
	public boolean removeAll(Collection<?> c){
		if(c.isEmpty()) return false;
		
		var toRemove=ptrsToIndex(ptrStream(c));
		
		if(toRemove.isEmpty()) return false;
		
		index.andNot(toRemove);
		if(index.isEmpty()){
			start=-1;
			end=-1;
			size=0;
			return true;
		}
		recalcInfo();
		return true;
	}
	
	public boolean add(long ptr){
		if(contains(ptr)) return false;
		
		if(isEmpty()){
			start=ptr;
			end=ptr+1;
		}else{
			end=Math.max(end, ptr+1);
			start=Math.min(start, ptr);
		}
		size++;
		index.add(Math.toIntExact(ptr));
		
		if(DEBUG_VALIDATION) checkData();
		return true;
	}
	
	@SuppressWarnings({"ReplaceInefficientStreamCount", "UnnecessaryToStringCall"})
	private void checkData(){
		if(size()!=longStream().count()){
			throw new IllegalStateException(size()+" "+stream().count());
		}
		if(!isEmpty()){
			var start=calcStart(index);
			var end  =calcEnd(index);
			if(start!=this.start) throw new IllegalStateException("corrupt start: "+this.start+" "+index.toString());
			if(end!=this.end) throw new IllegalStateException("corrupt end: "+lastIndex()+" "+index);
		}
	}
	
	public boolean remove(Chunk chunk)     {return remove(chunk.getPtr());}
	public boolean remove(ChunkPointer ptr){return remove(ptr.getValue());}
	@Override
	public boolean remove(Object o){return o instanceof ChunkPointer ptr&&remove(ptr.getValue());}
	public boolean remove(long ptr){
		checkEmpty();
		if(!contains(ptr)) return false;
		
		if(rageSize()==1){
			clear();
		}else{
			if(!index.contains(ptr)) throw new IllegalStateException("contains() invalid");
			size--;
			index.remove(ptr);
			
			if(ptr==start) calcStart();
			if(ptr==lastIndex()) calcEnd();
		}
		if(DEBUG_VALIDATION) checkData();
		return true;
	}
	
	private void calcEnd(){
		end=calcEnd(index);
	}
	private void calcStart(){
		start=calcStart(index);
	}
	
	public void removeFirst(){
		checkEmpty();
		if(rageSize()==1){
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
		if(rageSize()==1){
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
		start=-1;
		end=-1;
		size=0;
		index.clear();
	}
	
	public boolean contains(Chunk chunk)     {return contains(chunk.getPtr());}
	public boolean contains(ChunkPointer ptr){return contains(ptr.getValue());}
	@Override
	public boolean contains(Object o){return o instanceof ChunkPointer ptr&&contains(ptr.getValue());}
	public boolean contains(long ptr){
		var size=rageSize();
		if(size==0) return false;
		if(size==1) return ptr==start;
		
		var last=lastIndex();
		if(ptr<start||last<ptr) return false;
		if(ptr==start||ptr==last) return true;
		return index.contains(Math.toIntExact(ptr));
	}
	
	public LongStream longStream(){
		return index.stream();
	}
	@Override
	public Stream<ChunkPointer> stream(){
		return longStream().mapToObj(ChunkPointer::of);
	}
	
	public OptionalLong optionalMin(){
		return isEmpty()?OptionalLong.empty():OptionalLong.of(min());
	}
	public OptionalLong optionalMax(){
		return isEmpty()?OptionalLong.empty():OptionalLong.of(max());
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
		int size=(int)this.size;
		T[] r   =a.length>=size?a:(T[])Array.newInstance(a.getClass().getComponentType(), size);
		int i   =0;
		for(ChunkPointer ptr : this){
			r[i++]=(T)ptr;
		}
		return r;
	}
	
	private void checkEmpty(){
		if(isEmpty()) throw new IllegalStateException();
	}
	
	@Override
	public String toString(){
		return longStream().limit(50).mapToObj(Long::toString).collect(Collectors.joining(", ", "*[", size()>50?"...]":"]"));
	}
}
