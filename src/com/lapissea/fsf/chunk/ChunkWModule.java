package com.lapissea.fsf.chunk;

import com.lapissea.fsf.headermodule.HeaderModule;

public class ChunkWModule{
	private final Chunk        chunk;
	private final HeaderModule module;
	
	public ChunkWModule(Chunk chunk, HeaderModule module){
		this.chunk=chunk;
		this.module=module;
	}
	
	public boolean check(HeaderModule module){
		return this.module==module;
	}
	
	public Chunk getChunk(){
		return chunk;
	}
	
	public HeaderModule getModule(){
		return module;
	}
}
