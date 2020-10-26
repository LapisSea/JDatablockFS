package com.lapissea.cfs.io;

import com.lapissea.cfs.io.struct.IOInstance;
import com.lapissea.cfs.objects.chunk.ObjectPointer;
import com.lapissea.util.NotNull;

public interface SelfPoint<T extends IOInstance>{
	
	@NotNull
	ObjectPointer<T> getSelfPtr();
	
}
