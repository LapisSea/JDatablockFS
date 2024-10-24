package com.lapissea.dfs.exceptions;

import com.lapissea.dfs.objects.ChunkPointer;

import java.io.Serial;

public class PointerOutsideFile extends MalformedPointer{
	
	@Serial
	private static final long serialVersionUID = 6669766626830188682L;
	
	public PointerOutsideFile(String message, ChunkPointer ptr){
		super(message, ptr);
	}
}
