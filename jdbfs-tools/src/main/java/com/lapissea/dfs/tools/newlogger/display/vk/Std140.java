package com.lapissea.dfs.tools.newlogger.display.vk;

import org.lwjgl.system.Struct;

import java.nio.ByteBuffer;

public final class Std140 extends Struct{
	
	public static Member __int()             { return __member(4, 4, true); }
	public static Member __float()           { return __member(4, 4, true); }
	public static Member __bool()            { return __member(4, 4, true); }
	
	public static Member __vec4()            { return __member(4*4, 16, true); }
	public static Member __vec4Arr(int count){ return __member(4*4*count, 16, true); }
	public static Member __u8vec4()          { return __member(4, 16, true); }
	
	public static Member __mat4()            { return __member(4*4*Float.BYTES, 16, true); }
	public static Member __mat2()            { return __member(2*2*Float.BYTES, 16, true); }
	public static Member __mat3x2()          { return __member(4*3*Float.BYTES, 16, true); }
	
	private Std140()                         { super(0, null); }
	@Override
	protected Struct create(long address, ByteBuffer container){ return null; }
	@Override
	public int sizeof(){ return 0; }
}
