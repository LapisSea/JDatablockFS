package com.lapissea.dfs.type;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.objects.Reference;

import java.io.IOException;

public interface NewUnmanaged<T extends IOInstance.Unmanaged<T>>{
	T make(DataProvider provider, Reference reference, IOType type) throws IOException;
}
