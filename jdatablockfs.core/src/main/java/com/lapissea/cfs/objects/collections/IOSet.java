package com.lapissea.cfs.objects.collections;


import com.lapissea.cfs.type.field.annotations.IOValue;

import java.io.IOException;

@IOValue.OverrideType.DefaultImpl(IOHashSet.class)
public interface IOSet<T>{
	
	boolean add(T value) throws IOException;
	boolean remove(T value) throws IOException;
	boolean contains(T value) throws IOException;
	
	IOIterator<T> iterator();
	
}
