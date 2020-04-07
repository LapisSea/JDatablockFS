package com.lapissea.fsf.chunk;

import com.lapissea.fsf.headermodule.HeaderModule;

public class ChunkLinkWModule<Identifier>{
	private final ChunkLink                   link;
	private final HeaderModule<?, Identifier> module;
	
	public ChunkLinkWModule(ChunkLink link, HeaderModule<?, Identifier> module){
		this.link=link;
		this.module=module;
	}
	
	public boolean check(HeaderModule<?, Identifier> module){
		return this.module==module;
	}
	
	public ChunkLink getLink(){
		return link;
	}
	
	public HeaderModule<?, Identifier> getModule(){
		return module;
	}
}
