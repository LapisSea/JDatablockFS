package com.lapissea.dfs.run.world;

import com.lapissea.dfs.type.IOInstance;

public interface Chunk extends IOInstance.Def<Chunk>{
	int SIZE = 32;
	
	static Chunk newAt(int x, int y){
		return IOInstance.Def.of(Chunk.class, x, y, new byte[SIZE*SIZE]);
	}
	
	int x();
	int y();
	byte[] grid();
	
}
