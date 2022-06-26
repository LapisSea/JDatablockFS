package com.lapissea.cfs.chunk;

import com.lapissea.cfs.type.IOInstance;
import com.lapissea.cfs.type.TypeLink;

public interface TypeManager{
	
	
	<T extends IOInstance<T>> boolean isRegistered(Class<T> type);
	
	<T extends IOInstance<T>> T allocateNew(TypeLink data);
	
}
