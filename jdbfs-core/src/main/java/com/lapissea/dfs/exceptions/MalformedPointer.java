package com.lapissea.dfs.exceptions;

import com.lapissea.dfs.objects.ChunkPointer;

import java.io.IOException;
import java.io.Serial;

public class MalformedPointer extends IOException{
	
	@Serial
	private static final long serialVersionUID = 6669766626830188682L;
	
	public final ChunkPointer ptr;
	
	public MalformedPointer(String message, ChunkPointer ptr){
		super(message);
		this.ptr = ptr;
	}
	
	public MalformedPointer(String message, ChunkPointer ptr, Throwable cause){
		super(message, cause);
		this.ptr = ptr;
	}
	
	@Override
	public String getMessage(){
		return super.getMessage() + ptr;
	}
}
