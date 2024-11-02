package com.lapissea.dfs.run.checked;

import com.lapissea.dfs.objects.collections.IOIterator;
import com.lapissea.dfs.objects.collections.IOSet;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.StringJoiner;

import static org.assertj.core.api.Assertions.assertThat;

public class CheckSet<T> implements IOSet<T>{
	
	private final IOSet<T> testData;
	private final Set<T>   reference = new HashSet<>();
	
	public CheckSet(IOSet<T> testData) throws IOException{
		this.testData = testData;
		copyTestDataTo(reference);
		dataEquality();
	}
	
	private void dataEquality() throws IOException{
		assertThat(testData.size()).as("Reported sizes do not match").isEqualTo(reference.size());
		var tdCopy = HashSet.<T>newHashSet(Math.toIntExact(testData.size()));
		copyTestDataTo(tdCopy);
		assertThat(tdCopy.size()).as("Number of iterated elements does not match the reported size").isEqualTo(reference.size());
		
		var missing            = reference.stream().filter(ref -> !tdCopy.contains(ref)).toList();
		var shouldNotBePresent = tdCopy.stream().filter(ref -> !reference.contains(ref)).toList();
		
		if(missing.isEmpty() && shouldNotBePresent.isEmpty()){
			return;
		}
		
		StringJoiner sj = new StringJoiner("\n");
		sj.add("Element equality failed:");
		if(!missing.isEmpty()) sj.add("\tMissing: " + missing);
		if(!shouldNotBePresent.isEmpty()) sj.add("\tShould not be present: " + shouldNotBePresent);
		
		throw new AssertionError(sj.toString());
	}
	
	private void copyTestDataTo(Set<T> copy) throws IOException{
		var iter = testData.iterator();
		while(iter.hasNext()){
			if(!copy.add(iter.ioNext())){
				throw new AssertionError("Duplicate element in test data");
			}
		}
	}
	
	@Override
	public boolean add(T value) throws IOException{
		var a = testData.add(value);
		var b = reference.add(value);
		assertThat(a).as("add() failed: previous values are not equal").isEqualTo(b);
		dataEquality();
		return a;
	}
	@Override
	public boolean remove(T value) throws IOException{
		var a = testData.remove(value);
		var b = reference.remove(value);
		assertThat(a).as("remove failed: result differs from reference").isEqualTo(b);
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
		assertThat(a).as("contains failed: result differs from reference").isEqualTo(b);
		return a;
	}
	@Override
	public long size(){
		var a = testData.size();
		var b = reference.size();
		assertThat(a).as("Reported size does not match").isEqualTo(b);
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
				assertThat(a).as("iterator() failed: hasNext results are not equal").isEqualTo(b);
				return a;
			}
			@Override
			public T ioNext() throws IOException{
				var val = iter.ioNext();
				assertThat(copy.remove(val))
					.as("iterator() failed: Next element is not contained within the reference")
					.isTrue();
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
