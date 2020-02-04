package com.lapissea.fsf.chunk;

import com.lapissea.fsf.headermodule.HeaderModule;

public class ChunkLinkWModule{
	private final ChunkLink    link;
	private final HeaderModule module;
	
	public ChunkLinkWModule(ChunkLink link, HeaderModule module){
		this.link=link;
		this.module=module;
	}
	
	public boolean check(HeaderModule module){
		return this.module==module;
	}
	
	public ChunkLink getLink(){
		return link;
	}
	
	public HeaderModule getModule(){
		return module;
	}
}
