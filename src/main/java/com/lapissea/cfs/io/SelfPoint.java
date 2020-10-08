package com.lapissea.cfs.io;

import com.lapissea.cfs.objects.chunk.ObjectPointer;
import com.lapissea.util.NotNull;

public interface SelfPoint<T>{
	
	@NotNull
	ObjectPointer<T> getSelfPtr();
	
}
