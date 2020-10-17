package com.lapissea.cfs.objects;

import com.lapissea.util.NotNull;
import com.lapissea.util.TextUtil;
import com.lapissea.util.UtilL;
import com.lapissea.util.function.UnsafeFunction;
import com.lapissea.util.function.UnsafeIntConsumer;
import com.lapissea.util.function.UnsafePredicate;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public interface IOList<T> extends Iterable<T>{
	
	interface IBoxed<From, To> extends IOList<To>{
		@Override
		default void addElements(IOList<To> toAdd) throws IOException{
			getData().addElements(box(toAdd, getBoxer(), getUnboxer()));
		}
		
		@Override
		default int size(){
			return getData().size();
		}
		
		@Override
		default To getElement(int index) throws IOException{
			return getUnboxer().apply(getData().getElement(index));
		}
		
		@Override
		default void setElement(int index, To value) throws IOException{
			getData().setElement(index, getBoxer().apply(value));
		}
		
		@Override
		default void ensureCapacity(int elementCapacity) throws IOException{
			getData().ensureCapacity(elementCapacity);
		}
		
		@Override
		default void removeElement(int index) throws IOException{
			getData().removeElement(index);
		}
		
		@Override
		default void addElement(int index, To value) throws IOException{
			getData().addElement(index, getBoxer().apply(value));
		}
		
		@Override
		default void validate() throws IOException{
			getData().validate();
		}
		
		@Override
		default void free() throws IOException{
			getData().free();
		}
		
		@Override
		default void addElement(To value) throws IOException{
			getData().addElement(getBoxer().apply(value));
		}
		
		@Override
		default To pop() throws IOException{
			return getUnboxer().apply(getData().pop());
		}
		
		@Override
		default boolean isEmpty(){
			return getData().isEmpty();
		}
		
		@Override
		default void modifyElement(int index, UnsafeFunction<To, To, IOException> modifier) throws IOException{
			getData().modifyElement(index, e->getBoxer().apply(modifier.apply(getUnboxer().apply(e))));
		}
		
		@Override
		default void clear() throws IOException{
			getData().clear();
		}
		
		@Override
		default boolean contains(To ptr) throws IOException{
			return getData().contains(getBoxer().apply(ptr));
		}
		
		@Override
		default int indexOf(To value) throws IOException{
			return getData().indexOf(getBoxer().apply(value));
		}
		
		@Override
		default int find(UnsafePredicate<To, IOException> matcher) throws IOException{
			return getData().find(e->matcher.test(getUnboxer().apply(e)));
		}
		
		@Override
		default int indexOfLast(To value) throws IOException{
			return getData().indexOfLast(getBoxer().apply(value));
		}
		
		@Override
		default int findLast(UnsafePredicate<To, IOException> matcher) throws IOException{
			return getData().findLast(e->matcher.test(getUnboxer().apply(e)));
		}
		
		@Override
		default int count(UnsafePredicate<To, IOException> matcher) throws IOException{
			return getData().count(e->matcher.test(getUnboxer().apply(e)));
		}
		
		@Override
		default Stream<To> stream(){
			return getData().stream().map(getUnboxer());
		}
		
		@NotNull
		@Override
		default Iterator<To> iterator(){
			Iterator<From> source=getData().iterator();
			return new Iterator<>(){
				@Override
				public boolean hasNext(){
					return source.hasNext();
				}
				
				@Override
				public To next(){
					return getUnboxer().apply(source.next());
				}
				
				@Override
				public void remove(){
					source.remove();
				}
			};
		}
		
		
		@Override
		default Spliterator<To> spliterator(){
			class BoxedSpliterator implements Spliterator<To>{
				final Spliterator<From> source;
				
				BoxedSpliterator(Spliterator<From> source){this.source=source;}
				
				@Override
				public boolean tryAdvance(Consumer<? super To> action){
					return source.tryAdvance(e->action.accept(getUnboxer().apply(e)));
				}
				
				@Override
				public Spliterator<To> trySplit(){
					Spliterator<From> split=source.trySplit();
					if(split==null) return null;
					return new BoxedSpliterator(split);
				}
				
				@Override
				public long estimateSize(){
					return source.estimateSize();
				}
				
				@Override
				public int characteristics(){
					return source.characteristics();
				}
			}
			
			return new BoxedSpliterator(getData().spliterator());
		}
		
		IOList<From> getData();
		
		Function<From, To> getUnboxer();
		
		Function<To, From> getBoxer();
	}
	
	class Boxed<From, To> implements IBoxed<From, To>{
		private final IOList<From> data;
		
		private final Function<From, To> unboxer;
		private final Function<To, From> boxer;
		
		public Boxed(IOList<From> data, Function<From, To> unboxer, Function<To, From> boxer){
			this.data=data;
			this.unboxer=unboxer;
			this.boxer=boxer;
		}
		
		@Override
		public String toString(){
			return stream().map(TextUtil::toShortString).collect(Collectors.joining(", ", "[", "]"));
		}
		
		@Override
		public int hashCode(){
			return getData().hashCode();
		}
		
		@Override
		public IOList<From> getData(){
			return data;
		}
		
		@Override
		public Function<From, To> getUnboxer(){
			return unboxer;
		}
		
		@Override
		public Function<To, From> getBoxer(){
			return boxer;
		}
	}
	
	class MergedView<T> implements IOList<T>{
		
		private interface LocalAction<L, R>{
			R apply(IOList<L> list, int localIndex) throws IOException;
		}
		
		private final IOList<T>[] data;
		
		public MergedView(IOList<T>[] data){
			this.data=data;
		}
		
		@Override
		public Stream<T> stream(){
			return Arrays.stream(data)
			             .flatMap(IOList::stream);
		}
		
		@Override
		public String toString(){
			return Arrays.stream(data)
			             .filter(l->!l.isEmpty())
			             .map(l->l.stream()
			                      .map(TextUtil::toShortString)
			                      .collect(Collectors.joining(", ")))
			             .collect(Collectors.joining(" + ", "[", "]"));
		}
		
		@Override
		public int size(){
			int sum=0;
			for(var list : data){
				sum+=list.size();
			}
			return sum;
		}
		
		private <R> R localIndex(int index, LocalAction<T, R> action) throws IOException{
			if(index<0) throw new IndexOutOfBoundsException(index);
			int localIndex=index;
			for(var list : data){
				int siz=list.size();
				if(localIndex<siz){
					return action.apply(list, localIndex);
				}
				localIndex-=siz;
			}
			throw new IndexOutOfBoundsException(index);
		}
		
		@Override
		public T getElement(int index) throws IOException{
			return localIndex(index, IOList::getElement);
		}
		
		@Override
		public void setElement(int index, T value) throws IOException{
			localIndex(index, (list, i)->{
				list.setElement(i, value);
				return null;
			});
		}
		
		@Override
		public void ensureCapacity(int elementCapacity) throws IOException{
			int size  =size();
			int toGrow=elementCapacity-size;
			if(toGrow>0){
				var last=data[data.length-1];
				last.ensureCapacity(last.size()+toGrow);
			}
		}
		
		@Override
		public void removeElement(int index) throws IOException{
			localIndex(index, (list, i)->{
				list.removeElement(i);
				return null;
			});
		}
		
		@Override
		public void addElement(int index, T value) throws IOException{
			localIndex(index, (list, i)->{
				list.addElement(i, value);
				return null;
			});
		}
		
		@Override
		public void validate() throws IOException{
			for(var list : data){
				list.validate();
			}
		}
		
		@Override
		public void free() throws IOException{
			for(var list : data){
				list.free();
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	static <From, To> IOList<From> unbox(IOList<To> data){
		if(data==null) return null;
		if(data instanceof IBoxed<?, ?> boxed){
			return (IOList<From>)boxed.getData();
		}else{
			throw new ClassCastException(data+" is not a boxed list");
		}
	}
	
	static <From, To> IBoxed<From, To> box(IOList<From> data, Function<From, To> unboxer, Function<To, From> boxer){
		return new IOList.Boxed<>(data, unboxer, boxer);
	}
	
	static <T> IOList<T> wrap(T[] data){
		return new IOList<>(){
			
			@Override
			public String toString(){
				return stream().map(TextUtil::toShortString).collect(Collectors.joining(", ", "[", "]"));
			}
			
			@Override
			public int size(){
				return data.length;
			}
			
			@Override
			public T getElement(int index){
				return data[index];
			}
			
			@Override
			public void setElement(int index, T value){
				data[index]=value;
			}
			
			@Override
			public void ensureCapacity(int elementCapacity){
				if(elementCapacity>size()){
					throw new UnsupportedOperationException();
				}
			}
			
			@Override
			public void removeElement(int index){
				throw new UnsupportedOperationException();
			}
			
			@Override
			public void addElement(int index, T value){
				throw new UnsupportedOperationException();
			}
			
			@Override
			public void validate(){ }
			
			@Override
			public void free(){
				throw new UnsupportedOperationException();
			}
		};
	}
	
	static <T> IOList<T> wrap(List<T> data){
		return new IOList<>(){
			
			@Override
			public String toString(){
				return stream().map(TextUtil::toShortString).collect(Collectors.joining(", ", "[", "]"));
			}
			
			@Override
			public int size(){
				return data.size();
			}
			
			@Override
			public T getElement(int index){
				return data.get(index);
			}
			
			@Override
			public void setElement(int index, T value){
				data.set(index, value);
			}
			
			@Override
			public void ensureCapacity(int elementCapacity){
				if(data instanceof ArrayList<?> l){
					l.ensureCapacity(elementCapacity);
				}
			}
			
			@Override
			public void removeElement(int index){
				data.remove(index);
			}
			
			@Override
			public void addElement(int index, T value){
				data.add(index, value);
			}
			
			@Override
			public void validate(){ }
			
			@Override
			public void free(){
				data.clear();
			}
		};
	}
	
	@SuppressWarnings("unchecked")
	static <T> IOList<T> mergeView(List<IOList<T>> lists){
		return switch(lists.size()){
			case 0 -> throw new IllegalArgumentException("Nothing to merge");
			case 1 -> lists.get(0);
			default -> new MergedView<>(
				lists.stream()
				     .flatMap(s->s instanceof MergedView<T> merged?
				                 Arrays.stream(merged.data):
				                 Stream.of(s))
				     .toArray(IOList[]::new)
			);
		};
	}
	
	int size();
	
	default boolean isEmpty(){
		return size()==0;
	}
	
	T getElement(int index) throws IOException;
	
	void setElement(int index, T value) throws IOException;
	
	default void modifyElement(int index, UnsafeFunction<T, T, IOException> modifier) throws IOException{
		T oldValue=getElement(index);
		T newValue=modifier.apply(oldValue);
		if(!Objects.equals(oldValue, newValue)){
			setElement(index, newValue);
		}
	}
	
	void ensureCapacity(int elementCapacity) throws IOException;
	
	default boolean removeElement(T toRemove) throws IOException{
		int index=indexOf(toRemove);
		if(index==-1) return false;
		removeElement(index);
		return true;
	}
	
	void removeElement(int index) throws IOException;
	
	void addElement(int index, T value) throws IOException;
	
	default void addElement(T value) throws IOException{ addElement(size(), value); }
	
	
	default T pop() throws IOException{
		var end=size()-1;
		if(end<0) return null;
		
		T val=getElement(end);
		removeElement(end);
		return val;
	}
	
	default void clear() throws IOException{
		while(!isEmpty()){
			removeElement(size()-1);
		}
	}
	
	default boolean contains(T ptr) throws IOException{
		return indexOf(ptr)!=-1;
	}
	
	default int indexOf(T value) throws IOException{
		return find(v->Objects.equals(v, value));
	}
	
	default boolean noneMatches(UnsafePredicate<T, IOException> matcher) throws IOException{
		return find(matcher)==-1;
	}
	
	default boolean anyMatches(UnsafePredicate<T, IOException> matcher) throws IOException{
		return find(matcher)!=-1;
	}
	
	default void find(UnsafePredicate<T, IOException> matcher, UnsafeIntConsumer<IOException> onFound) throws IOException{
		int index=find(matcher);
		if(index!=-1){
			onFound.accept(index);
		}
	}
	
	default int find(UnsafePredicate<T, IOException> matcher) throws IOException{
		for(int i=0;i<size();i++){
			var el=getElement(i);
			if(matcher.test(el)){
				return i;
			}
		}
		return -1;
	}
	
	default int indexOfLast(T value) throws IOException{
		return findLast(v->Objects.equals(v, value));
	}
	
	default int findLast(UnsafePredicate<T, IOException> matcher) throws IOException{
		for(int i=size()-1;i>=0;i--){
			var el=getElement(i);
			if(matcher.test(el)){
				return i;
			}
		}
		return -1;
	}
	
	default int count(UnsafePredicate<T, IOException> matcher) throws IOException{
		int count=0;
		for(T el : this){
			if(matcher.test(el)){
				count++;
			}
		}
		return count;
	}
	
	default Stream<T> stream(){
		return StreamSupport.stream(spliterator(), false);
	}
	
	@NotNull
	@Override
	default Iterator<T> iterator(){
		return new Iterator<>(){
			int cursor;
			
			@Override
			public boolean hasNext(){
				return cursor<size();
			}
			
			@Override
			public T next(){
				T val;
				try{
					val=getElement(cursor++);
				}catch(IOException e){
					throw UtilL.uncheckedThrow(e);
				}
				return val;
			}
			
			@Override
			public void remove(){
				try{
					removeElement(--cursor);
				}catch(IOException e){
					throw new RuntimeException(e);
				}
			}
			
		};
	}
	
	@Override
	default Spliterator<T> spliterator(){
		
		class IOListSpliterator implements Spliterator<T>{
			private final AtomicInteger current;
			private final int           end;
			
			public IOListSpliterator(int current, int end){
				this.current=new AtomicInteger(current);
				this.end=end;
			}
			
			@Override
			public boolean tryAdvance(Consumer<? super T> action){
				T element;
				try{
					element=getElement(current.getAndIncrement());
				}catch(IOException e){
					throw new RuntimeException(e);
				}
				action.accept(element);
				return current.get()<size();
			}
			
			@Override
			public Spliterator<T> trySplit(){
				int lo=current.get(), mid=(lo+end) >>> 1;
				if(lo>=mid) return null;
				current.set(mid);
				return new IOListSpliterator(lo, mid);
			}
			
			@Override
			public long estimateSize(){
				return end-current.get();
			}
			
			@Override
			public int characteristics(){
				return Spliterator.ORDERED|Spliterator.SIZED|Spliterator.SUBSIZED;
			}
		}
		
		return new IOListSpliterator(0, size());
	}
	
	@SuppressWarnings("unchecked")
	default void addElements(T... toAdd) throws IOException{
		addElements(wrap(toAdd));
	}
	
	default void addElements(List<T> toAdd) throws IOException{
		addElements(wrap(toAdd));
	}
	
	default void addElements(IOList<T> toAdd) throws IOException{
		for(T t : toAdd){
			addElement(t);
		}
	}
	
	void validate() throws IOException;
	
	void free() throws IOException;
}
