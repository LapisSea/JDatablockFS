package com.lapissea.cfs.io;

import com.lapissea.cfs.objects.chunk.ObjectPointer;

public interface SelfPoint<T>{
	
	ObjectPointer<T> getSelfPtr();
	
}
