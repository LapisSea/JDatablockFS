package com.lapissea.cfs.run.world;

import com.lapissea.cfs.type.field.annotations.IOValue;

public class Chunk{
	
	public static final int SIZE=32;
	
	@IOValue
	private byte[] grid=new byte[SIZE*SIZE];
	
}
