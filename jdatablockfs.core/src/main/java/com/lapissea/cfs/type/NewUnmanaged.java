package com.lapissea.cfs.type;

import com.lapissea.cfs.chunk.DataProvider;
import com.lapissea.cfs.objects.Reference;

import java.io.IOException;

public interface NewUnmanaged<T extends IOInstance.Unmanaged<T>>{
	T make(DataProvider provider, Reference reference, IOType type) throws IOException;
}
