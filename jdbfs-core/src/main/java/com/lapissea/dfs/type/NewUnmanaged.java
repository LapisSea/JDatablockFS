package com.lapissea.dfs.type;

import com.lapissea.dfs.core.DataProvider;
import com.lapissea.dfs.core.chunk.Chunk;

import java.io.IOException;

public interface NewUnmanaged<T extends IOInstance.Unmanaged<T>>{
	T make(DataProvider provider, Chunk identity, IOType type) throws IOException;
}
