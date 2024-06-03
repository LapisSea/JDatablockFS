package com.lapissea.dfs.run.checked;

import com.lapissea.dfs.objects.collections.IOIterator;
import com.lapissea.dfs.objects.collections.IOSet;
import org.testng.Assert;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class CheckSet<T> implements IOSet<T>{
	
	private final IOSet<T> testData;
	private final Set<T>   reference = new HashSet<>();
	
	public CheckSet(IOSet<T> testData) throws IOException{
		this.testData = testData;
		copyData(reference);
		dataEquality();
	}
	
	private void dataEquality() throws IOException{
		Assert.assertEquals(testData.size(), reference.size(), "Sizes do not match");
		var copy = HashSet.<T>newHashSet(Math.toIntExact(testData.size()));
		copyData(copy);
		Assert.assertEquals(copy.size(), testData.size(), "Number of elements does not match the reported size");
		Assert.assertEquals(copy, reference);
	}
	
	private void copyData(Set<T> copy) throws IOException{
		var iter = testData.iterator();
		while(iter.hasNext()){
			copy.add(iter.ioNext());
		}
	}
	
	@Override
	public boolean add(T value) throws IOException{
		var a = testData.add(value);
		var b = reference.add(value);
		Assert.assertEquals(a, b, "add() failed: previous values are not equal");
		dataEquality();
		return a;
	}
	@Override
	public boolean remove(T value) throws IOException{
		var a = testData.remove(value);
		var b = reference.remove(value);
		Assert.assertEquals(a, b);
		dataEquality();
		return a;
	}
	@Override
	public void clear() throws IOException{
		testData.clear();
		reference.clear();
		dataEquality();
	}
	@Override
	public boolean contains(T value) throws IOException{
		var a = testData.contains(value);
		var b = reference.contains(value);
		Assert.assertEquals(a, b, "contains failed: result differs from reference");
		return a;
	}
	@Override
	public long size(){
		var a = testData.size();
		var b = reference.size();
		Assert.assertEquals(a, b);
		return a;
	}
	@Override
	public IOIterator<T> iterator(){
		var copy = new HashSet<>(reference);
		var iter = testData.iterator();
		return new IOIterator<>(){
			
			@Override
			public boolean hasNext() throws IOException{
				var a = iter.hasNext();
				var b = !copy.isEmpty();
				Assert.assertEquals(a, b);
				return a;
			}
			@Override
			public T ioNext() throws IOException{
				var val = iter.ioNext();
				Assert.assertTrue(copy.remove(val));
				return val;
			}
		};
	}
	
	@Override
	public void requestCapacity(long capacity) throws IOException{
		testData.requestCapacity(capacity);
		dataEquality();
	}
	
	@Override
	public boolean equals(Object obj){
		return obj == this ||
		       obj instanceof IOSet<?> set &&
		       set.equals(testData);
	}
	
	@Override
	public String toString(){
		return testData.toString();
	}
}
