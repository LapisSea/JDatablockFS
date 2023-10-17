package com.lapissea.dfs.run.checked;

import com.lapissea.dfs.objects.collections.IOIterator;
import com.lapissea.dfs.objects.collections.IOList;
import com.lapissea.dfs.utils.OptionalPP;
import com.lapissea.util.NotImplementedException;
import com.lapissea.util.function.UnsafeConsumer;
import com.lapissea.util.function.UnsafeFunction;
import com.lapissea.util.function.UnsafePredicate;
import org.testng.Assert;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Spliterator;

import static org.testng.Assert.assertEquals;

public class CheckIOList<T> implements IOList<T>{
	
	private final IOList<T> data;
	private final IOList<T> base = IOList.wrap(new ArrayList<>());
	
	public CheckIOList(IOList<T> data) throws IOException{
		this.data = data;
		copyData(base);
	}
	
	private void dataEquality() throws IOException{
		Assert.assertEquals(data.size(), base.size(), "Sizes do not match");
		var copy = IOList.wrap(new ArrayList<T>());
		copyData(copy);
		Assert.assertEquals(copy, base);
	}
	
	private void copyData(IOList<T> dest) throws IOException{
		var iter = data.iterator();
		while(iter.hasNext()){
			dest.add(iter.ioNext());
		}
	}
	
	@Override
	public Class<T> elementType(){
		return data.elementType();
	}
	@Override
	public long size(){
		var a = data.size();
		var b = base.size();
		assertEquals(a, b);
		return a;
	}
	@Override
	public T get(long index) throws IOException{
		var a = data.get(index);
		var b = base.get(index);
		assertEquals(a, b);
		return a;
	}
	@Override
	public void set(long index, T value) throws IOException{
		data.set(index, value);
		base.set(index, value);
		dataEquality();
	}
	@Override
	public void add(long index, T value) throws IOException{
		data.add(index, value);
		base.add(index, value);
		dataEquality();
	}
	@Override
	public void add(T value) throws IOException{
		data.add(value);
		base.add(value);
		dataEquality();
	}
	@Override
	public void addAll(Collection<T> values) throws IOException{
		data.addAll(values);
		base.addAll(values);
		dataEquality();
	}
	@Override
	public void remove(long index) throws IOException{
		data.remove(index);
		base.remove(index);
		dataEquality();
	}
	
	@Override
	public Spliterator<T> spliterator(long start){
		throw new NotImplementedException();//TODO
	}
	
	@Override
	public IOIterator.Iter<T> iterator(){
		IOIterator.Iter<T> ia = data.iterator();
		IOIterator.Iter<T> ib = base.iterator();
		return new IOIterator.Iter<>(){
			@Override
			public boolean hasNext(){
				var a = ia.hasNext();
				var b = ib.hasNext();
				assertEquals(a, b);
				return a;
			}
			@Override
			public T ioNext() throws IOException{
				var a = ia.ioNext();
				var b = ib.ioNext();
				assertEquals(a, b);
				return a;
			}
			@Override
			public void ioRemove() throws IOException{
				ia.ioRemove();
				ib.ioRemove();
				dataEquality();
			}
		};
	}
	@Override
	public IOListIterator<T> listIterator(long startIndex){
		IOListIterator<T> ia = data.listIterator(startIndex);
		IOListIterator<T> ib = base.listIterator(startIndex);
		return new IOListIterator<>(){
			@Override
			public boolean hasNext(){
				var a = ia.hasNext();
				var b = ib.hasNext();
				assertEquals(a, b);
				return a;
			}
			@Override
			public T ioNext() throws IOException{
				var a = ia.ioNext();
				var b = ib.ioNext();
				assertEquals(a, b);
				return a;
			}
			@Override
			public void skipNext(){
				ia.skipNext();
				ib.skipNext();
			}
			@Override
			public boolean hasPrevious(){
				var a = ia.hasPrevious();
				var b = ib.hasPrevious();
				assertEquals(a, b);
				return a;
			}
			@Override
			public T ioPrevious() throws IOException{
				var a = ia.ioPrevious();
				var b = ib.ioPrevious();
				assertEquals(a, b);
				return a;
			}
			@Override
			public void skipPrevious(){
				ia.skipPrevious();
				ib.skipPrevious();
			}
			@Override
			public long nextIndex(){
				var a = ia.nextIndex();
				var b = ib.nextIndex();
				assertEquals(a, b);
				return a;
			}
			@Override
			public long previousIndex(){
				var a = ia.previousIndex();
				var b = ib.previousIndex();
				assertEquals(a, b);
				return a;
			}
			@Override
			public void ioRemove() throws IOException{
				ia.ioRemove();
				ib.ioRemove();
				dataEquality();
			}
			@Override
			public void ioSet(T t) throws IOException{
				ia.ioSet(t);
				ib.ioSet(t);
				dataEquality();
			}
			@Override
			public void ioAdd(T t) throws IOException{
				ia.ioAdd(t);
				ib.ioAdd(t);
				dataEquality();
			}
		};
	}
	
	@Override
	public void modify(long index, UnsafeFunction<T, T, IOException> modifier) throws IOException{
		data.modify(index, modifier);
		base.modify(index, modifier);
		dataEquality();
	}
	@Override
	public boolean isEmpty(){
		var a = data.isEmpty();
		var b = base.isEmpty();
		assertEquals(a, b);
		return a;
	}
	@Override
	public T addNew() throws IOException{
		var a = data.addNew();
		var b = base.addNew();
		assertEquals(a, b);
		dataEquality();
		return a;
	}
	@Override
	public T addNew(UnsafeConsumer<T, IOException> initializer) throws IOException{
		var a = data.addNew(initializer);
		var b = base.addNew(initializer);
		assertEquals(a, b);
		dataEquality();
		return a;
	}
	@Override
	public void addMultipleNew(long count) throws IOException{
		data.addMultipleNew(count);
		base.addMultipleNew(count);
		dataEquality();
	}
	@Override
	public void addMultipleNew(long count, UnsafeConsumer<T, IOException> initializer) throws IOException{
		data.addMultipleNew(count, initializer);
		base.addMultipleNew(count, initializer);
		dataEquality();
	}
	@Override
	public void clear() throws IOException{
		data.clear();
		base.clear();
		dataEquality();
	}
	@Override
	public void requestCapacity(long capacity) throws IOException{
		data.requestCapacity(capacity);
		base.requestCapacity(capacity);
		dataEquality();
	}
	@Override
	public void trim() throws IOException{
		data.trim();
		base.trim();
		dataEquality();
	}
	@Override
	public long getCapacity() throws IOException{
		var a = data.getCapacity();
		var b = base.getCapacity();
		assertEquals(a, b);
		return a;
	}
	@Override
	public boolean contains(T value) throws IOException{
		var a = data.contains(value);
		var b = base.contains(value);
		assertEquals(a, b);
		return a;
	}
	@Override
	public long indexOf(T value) throws IOException{
		var a = data.indexOf(value);
		var b = base.indexOf(value);
		if(a != b){
			var aEl = data.get(a);
			var bEl = base.get(b);
			assertEquals(aEl, bEl, "indexOf returned different idx (" + a + ", " + b + ") and the values are not the same");
		}
		return a;
	}
	@Override
	public OptionalPP<T> first(){
		var a = data.first();
		var b = base.first();
		assertEquals(a, b);
		return a;
	}
	@Override
	public OptionalPP<T> peekFirst() throws IOException{
		var a = data.peekFirst();
		var b = base.peekFirst();
		assertEquals(a, b);
		return a;
	}
	@Override
	public OptionalPP<T> popFirst() throws IOException{
		var a = data.popFirst();
		var b = base.popFirst();
		assertEquals(a, b);
		dataEquality();
		return a;
	}
	@Override
	public void pushFirst(T newFirst) throws IOException{
		data.pushFirst(newFirst);
		base.pushFirst(newFirst);
		dataEquality();
	}
	@Override
	public OptionalPP<T> peekLast() throws IOException{
		var a = data.peekLast();
		var b = base.peekLast();
		assertEquals(a, b);
		return a;
	}
	@Override
	public OptionalPP<T> popLast() throws IOException{
		var a = data.popLast();
		var b = base.popLast();
		assertEquals(a, b);
		dataEquality();
		return a;
	}
	@Override
	public OptionalPP<T> popLastIf(UnsafePredicate<T, IOException> check) throws IOException{
		var a = data.popLastIf(check);
		var b = base.popLastIf(check);
		assertEquals(a, b);
		dataEquality();
		return a;
	}
	@Override
	public void pushLast(T newLast) throws IOException{
		data.pushLast(newLast);
		base.pushLast(newLast);
		dataEquality();
	}
	@Override
	public void free(long index) throws IOException{
		data.free(index);
		base.free(index);
		dataEquality();
	}
}
