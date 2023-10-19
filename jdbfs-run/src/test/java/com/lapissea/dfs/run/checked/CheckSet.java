package com.lapissea.dfs.run.checked;

import com.lapissea.dfs.objects.collections.IOIterator;
import com.lapissea.dfs.objects.collections.IOSet;
import org.testng.Assert;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class CheckSet<T> implements IOSet<T>{
	
	private final IOSet<T> data;
	private final Set<T>   base = new HashSet<>();
	
	public CheckSet(IOSet<T> data) throws IOException{
		this.data = data;
		copyData(base);
		dataEquality();
	}
	
	private void dataEquality() throws IOException{
		Assert.assertEquals(data.size(), base.size(), "Sizes do not match");
		var copy = HashSet.<T>newHashSet(Math.toIntExact(data.size()));
		copyData(copy);
		Assert.assertEquals(copy, base);
	}
	
	private void copyData(Set<T> copy) throws IOException{
		var iter = data.iterator();
		while(iter.hasNext()){
			copy.add(iter.ioNext());
		}
	}
	
	@Override
	public boolean add(T value) throws IOException{
		var a = data.add(value);
		var b = base.add(value);
		Assert.assertEquals(a, b);
		dataEquality();
		return a;
	}
	@Override
	public boolean remove(T value) throws IOException{
		var a = data.remove(value);
		var b = base.remove(value);
		Assert.assertEquals(a, b);
		dataEquality();
		return a;
	}
	@Override
	public void clear() throws IOException{
		data.clear();
		base.clear();
		dataEquality();
	}
	@Override
	public boolean contains(T value) throws IOException{
		var a = data.contains(value);
		var b = base.contains(value);
		Assert.assertEquals(a, b);
		return a;
	}
	@Override
	public long size(){
		var a = data.size();
		var b = base.size();
		Assert.assertEquals(a, b);
		return a;
	}
	@Override
	public IOIterator<T> iterator(){
		var copy = new HashSet<>(base);
		var iter = data.iterator();
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
		data.requestCapacity(capacity);
		dataEquality();
	}
	
	@Override
	public boolean equals(Object obj){
		return obj == this ||
		       obj instanceof IOSet<?> set &&
		       set.equals(data);
	}
	
	@Override
	public String toString(){
		return data.toString();
	}
}
