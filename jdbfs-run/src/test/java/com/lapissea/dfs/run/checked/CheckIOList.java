package com.lapissea.dfs.run.checked;

import com.lapissea.dfs.objects.collections.IOIterator;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.query.Query;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.ShouldNeverHappenError;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;
import com.lapissea.util.function.UnsafePredicate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Spliterator;

import static org.assertj.core.api.Assertions.assertThat;

public class CheckIOList<T> implements IOList<T>{
	
	private final IOList<T>    testData;
	private final ArrayList<T> reference = new ArrayList<>();
	
	public CheckIOList(IOList<T> testData) throws IOException{
		this.testData = testData;
		copyTestDataTo(reference::add);
	}
	
	private void dataEquality() throws IOException{
		assertThat(testData.size()).as("Reported sizes do not match").isEqualTo(reference.size());
		
		var tdCopy = new ArrayList<T>();
		copyTestDataTo(tdCopy::add);
		assertThat(tdCopy.size()).as("Number of iterated elements does not match the reported size").isEqualTo(reference.size());
		
		if(!tdCopy.equals(reference)){
			assertThat(tdCopy).as("Elements do not match reference")
			                  .containsExactlyElementsOf(reference);
			throw new ShouldNeverHappenError("Element equality should not pass");
		}
	}
	
	private <E extends Exception> void copyTestDataTo(UnsafeConsumer<T, E> dest) throws IOException, E{
		var iter = testData.iterator();
		while(iter.hasNext()){
			dest.accept(iter.ioNext());
		}
	}
	
	@Override
	public Class<T> elementType(){
		return testData.elementType();
	}
	@Override
	public long size(){
		var a = testData.size();
		var b = reference.size();
		assertThat(a).as("Reported sizes do not match").isEqualTo(b);
		return a;
	}
	@Override
	public T get(long index) throws IOException{
		var a = testData.get(index);
		var b = reference.get(Math.toIntExact(index));
		assertThat(a).as(() -> "Element at index " + index + " does not match").isEqualTo(b);
		return a;
	}
	@Override
	public void set(long index, T value) throws IOException{
		testData.set(index, value);
		reference.set(Math.toIntExact(index), value);
		dataEquality();
	}
	@Override
	public void add(long index, T value) throws IOException{
		testData.add(index, value);
		reference.add(Math.toIntExact(index), value);
		dataEquality();
	}
	@Override
	public void add(T value) throws IOException{
		testData.add(value);
		reference.add(value);
		dataEquality();
	}
	@Override
	public void addAll(Collection<T> values) throws IOException{
		testData.addAll(values);
		reference.addAll(values);
		dataEquality();
	}
	@Override
	public void remove(long index) throws IOException{
		testData.remove(index);
		reference.remove(Math.toIntExact(index));
		dataEquality();
	}
	
	@Override
	public Spliterator<T> spliterator(long start){
		throw new NotImplementedException();//TODO
	}
	
	@Override
	public IOIterator.Iter<T> iterator(){
		IOIterator.Iter<T> ia = testData.iterator();
		Iterator<T>        ib = reference.iterator();
		return new IOIterator.Iter<>(){
			@Override
			public boolean hasNext(){
				var a = ia.hasNext();
				var b = ib.hasNext();
				assertThat(a).as("iter.hasNext not matching").isEqualTo(b);
				return a;
			}
			@Override
			public T ioNext() throws IOException{
				var a = ia.ioNext();
				var b = ib.next();
				assertThat(a).as("iterator next element does not match").isEqualTo(b);
				return a;
			}
			@Override
			public void ioRemove() throws IOException{
				ia.ioRemove();
				ib.remove();
				dataEquality();
			}
		};
	}
	@Override
	public IOListIterator<T> listIterator(long startIndex){
		IOListIterator<T> ia = testData.listIterator(startIndex);
		ListIterator<T>   ib = reference.listIterator(Math.toIntExact(startIndex));
		return new IOListIterator<>(){
			@Override
			public boolean hasNext(){
				var a = ia.hasNext();
				var b = ib.hasNext();
				assertThat(a).as("list iter.hasNext not matching").isEqualTo(b);
				return a;
			}
			@Override
			public T ioNext() throws IOException{
				var a = ia.ioNext();
				var b = ib.next();
				assertThat(a).as("list iterator next element does not match").isEqualTo(b);
				return a;
			}
			@Override
			public void skipNext(){
				ia.skipNext();
				ib.next();
			}
			@Override
			public boolean hasPrevious(){
				var a = ia.hasPrevious();
				var b = ib.hasPrevious();
				assertThat(a).as("list iter.hasPrevious not matching").isEqualTo(b);
				return a;
			}
			@Override
			public T ioPrevious() throws IOException{
				var a = ia.ioPrevious();
				var b = ib.previous();
				assertThat(a).as("list iterator previous element does not match").isEqualTo(b);
				return a;
			}
			@Override
			public void skipPrevious(){
				ia.skipPrevious();
				ib.previous();
			}
			@Override
			public long nextIndex(){
				var a = ia.nextIndex();
				var b = ib.nextIndex();
				assertThat(a).as("nextIndex is not matching").isEqualTo(b);
				return a;
			}
			@Override
			public long previousIndex(){
				var a = ia.previousIndex();
				var b = ib.previousIndex();
				assertThat(a).as("previousIndex is not matching").isEqualTo(b);
				return a;
			}
			@Override
			public void ioRemove() throws IOException{
				ia.ioRemove();
				ib.remove();
				dataEquality();
			}
			@Override
			public void ioSet(T t) throws IOException{
				ia.ioSet(t);
				ib.set(t);
				dataEquality();
			}
			@Override
			public void ioAdd(T t) throws IOException{
				ia.ioAdd(t);
				ib.add(t);
				dataEquality();
			}
		};
	}
	
	@Override
	public void modify(long index, UnsafeFunction<T, T, IOException> modifier) throws IOException{
		testData.modify(index, modifier);
		reference.set(Math.toIntExact(index), modifier.apply(reference.get(Math.toIntExact(index))));
		dataEquality();
	}
	@Override
	public boolean isEmpty(){
		var a = testData.isEmpty();
		var b = reference.isEmpty();
		assertThat(a).as("Reported empty status does not match").isEqualTo(b);
		return a;
	}
	@Override
	public T addNew(){
		throw new UnsupportedOperationException();
//		var a = testData.addNew();
//		var b = reference.addNew();
//		assertThat(a).as("Default addNew does not match").isEqualTo(b);
//		dataEquality();
//		return a;
	}
	@Override
	public T addNew(UnsafeConsumer<T, IOException> initializer){
		throw new UnsupportedOperationException();
//		var a = testData.addNew(initializer);
//		var b = reference.addNew(initializer);
//		assertThat(a).as("Custom addNew does not match").isEqualTo(b);
//		dataEquality();
//		return a;
	}
	@Override
	public void addMultipleNew(long count){
		throw new UnsupportedOperationException();
//		testData.addMultipleNew(count);
//		reference.addMultipleNew(count);
//		dataEquality();
	}
	@Override
	public void addMultipleNew(long count, UnsafeConsumer<T, IOException> initializer){
//		testData.addMultipleNew(count, initializer);
//		reference.addMultipleNew(count, initializer);
//		dataEquality();
	}
	@Override
	public void clear() throws IOException{
		testData.clear();
		reference.clear();
		dataEquality();
	}
	@Override
	public void requestCapacity(long capacity) throws IOException{
		testData.requestCapacity(capacity);
		reference.ensureCapacity(Math.toIntExact(capacity));
		dataEquality();
	}
	@Override
	public void trim() throws IOException{
		testData.trim();
		reference.trimToSize();
		dataEquality();
	}
	@Override
	public long getCapacity(){
		throw new UnsupportedOperationException();
//		var a = testData.getCapacity();
//		var b = reference.getCapacity();
//		assertThat(a).as("Reported capacity does not match").isEqualTo(b);
//		return a;
	}
	@Override
	public boolean contains(T value) throws IOException{
		var a = testData.contains(value);
		var b = reference.contains(value);
		assertThat(a).as(() -> "Contains does not match for " + value).isEqualTo(b);
		return a;
	}
	@Override
	public long indexOf(T value) throws IOException{
		var a = testData.indexOf(value);
		var b = reference.indexOf(value);
		if(a != b){
			var aEl = testData.get(a);
			var bEl = reference.get(b);
			assertThat(aEl)
				.as(() -> "indexOf for value \"" + value + "\" returned different indexes (" + a + ", " + b + ") and the values are not the same")
				.isEqualTo(bEl);
		}
		return a;
	}
	@Override
	public T getFirst() throws IOException{
		var a = testData.getFirst();
		var b = reference.getFirst();
		assertThat(a).as("getFirst does not match").isEqualTo(b);
		return a;
	}
	@Override
	public T getLast() throws IOException{
		var a = testData.getLast();
		var b = reference.getLast();
		assertThat(a).as("getLast does not match").isEqualTo(b);
		return a;
	}
	@Override
	public boolean removeLast() throws IOException{
		var a = testData.removeLast();
		var b = reference.removeLast();
		assertThat(a).as("removeLast does not match").isEqualTo(b);
		dataEquality();
		return a;
	}
	@Override
	public boolean popLastIf(UnsafePredicate<T, IOException> check) throws IOException{
		var a = testData.popLastIf(check);
		var b = popLastIf(reference, check);
		assertThat(a).as("popLastIf does not match").isEqualTo(b);
		dataEquality();
		return a;
	}
	private boolean popLastIf(List<T> list, UnsafePredicate<T, IOException> check) throws IOException{
		if(isEmpty()) return false;
		var index = list.size() - 1;
		var val   = list.get(index);
		if(!check.test(val)) return false;
		list.remove(index);
		return true;
	}
	
	@Override
	public void pushLast(T newLast) throws IOException{
		testData.pushLast(newLast);
		reference.add(newLast);
		dataEquality();
	}
	@Override
	public void free(long index) throws IOException{
		testData.free(index);
		dataEquality();
	}
	@Override
	public String toString(){
		return testData.toString();
	}
	@Override
	public Query<T> query(){
		return new CheckQuery<>(testData.query(), reference);
	}
}
