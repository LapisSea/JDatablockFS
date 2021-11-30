package com.lapissea.cfs.chunk;

import com.lapissea.cfs.GlobalConfig;
import com.lapissea.cfs.objects.ChunkPointer;
import com.lapissea.util.NotNull;
import org.roaringbitmap.longlong.Roaring64Bitmap;

import java.lang.reflect.Array;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

@SuppressWarnings({"SimplifyStreamApiCallChains", "unused"})
public class ChunkSet implements Set<ChunkPointer>{
	
	private final Roaring64Bitmap index=new Roaring64Bitmap();
	private       long            end  =-1;
	private       long            start=-1;
	private       long            size;
	
	public ChunkSet(){
	}
	
	public ChunkSet(Stream<ChunkPointer> data){
		data.forEach(this::add);
	}
	public ChunkSet(Collection<ChunkPointer> data){
		addAll(data);
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
		
		Roaring64Bitmap toAdd=new Roaring64Bitmap();
		c.stream().mapToLong(ChunkPointer::getValue).forEach(toAdd::add);
		
		boolean change=calcStart(toAdd)<start||calcEnd(toAdd)>end;
		
		if(!change){
			change=toAdd.stream().anyMatch(i->!index.contains(i));
		}
		if(!change) return false;
		
		index.or(toAdd);
		return true;
	}
	
	private long calcEnd(Roaring64Bitmap toAdd){
		return toAdd.getReverseLongIterator().next()+1;
	}
	private long calcStart(Roaring64Bitmap toAdd){
		return toAdd.getLongIterator().next();
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
		
		Roaring64Bitmap toRetain=new Roaring64Bitmap();
		ptrStream(c).mapToLong(ChunkPointer::getValue).forEach(toRetain::add);
		
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
		
		Roaring64Bitmap toRemove=new Roaring64Bitmap();
		ptrStream(c).mapToLong(ChunkPointer::getValue).forEach(toRemove::add);
		
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
		index.add(ptr);
		
		checkData();
		return true;
	}
	
	@SuppressWarnings({"ReplaceInefficientStreamCount", "UnnecessaryToStringCall"})
	private void checkData(){
		if(!GlobalConfig.DEBUG_VALIDATION) return;
		assert size()==longStream().count():
			size()+" "+stream().count();
		if(!isEmpty()){
			assert index.contains(start):
				start+" "+index.toString();
			assert index.contains(lastIndex()):
				lastIndex()+" "+index;
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
			assert index.contains(ptr);
			size--;
			index.removeLong(ptr);
			
			if(ptr==start) calcStart();
			if(ptr==lastIndex()) calcEnd();
		}
		checkData();
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
			assert index.contains(start);
			index.removeLong(start);
			size--;
			calcStart();
		}
		checkData();
	}
	
	public void removeLast(){
		checkEmpty();
		if(rageSize()==1){
			clear();
		}else{
			assert index.contains(lastIndex());
			index.removeLong(lastIndex());
			size--;
			calcEnd();
		}
		checkData();
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
		return index.contains(ptr);
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
