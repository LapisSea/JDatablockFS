package com.lapissea.dfs.tools.newlogger.display.vk.enums;

import com.lapissea.dfs.objects.NumberSize;
import com.lapissea.dfs.tools.newlogger.display.VUtils;

import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static java.lang.invoke.MethodHandles.byteArrayViewVarHandle;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT16;
import static org.lwjgl.vulkan.VK10.VK_INDEX_TYPE_UINT32;
import static org.lwjgl.vulkan.VK14.VK_INDEX_TYPE_UINT8;

public enum VkIndexType implements VUtils.IDValue{
	UINT16(VK_INDEX_TYPE_UINT16, 2),
	UINT32(VK_INDEX_TYPE_UINT32, 4),
	UINT8(VK_INDEX_TYPE_UINT8, 1),
	;
	
	public final int id;
	public final int byteSize;
	
	VkIndexType(int id, int byteSize){
		this.id = id;
		this.byteSize = byteSize;
	}
	
	@Override
	public int id(){ return id; }
	
	private static final VarHandle CHAR_VIEW = var(char.class);
	private static final VarHandle INT_VIEW  = var(int.class);
	
	private static VarHandle var(Class<?> c){ return byteArrayViewVarHandle(c.arrayType(), ByteOrder.nativeOrder()).withInvokeExactBehavior(); }
	
	public void write(ByteBuffer indecies, int index){
		switch(this){
			case UINT16 -> indecies.putChar((char)index);
			case UINT32 -> indecies.putInt(index);
			case UINT8 -> indecies.put((byte)index);
		}
	}
	public void write(byte[] indecies, int offset, int index){
		switch(this){
			case UINT16 -> CHAR_VIEW.set(indecies, offset, (char)index);
			case UINT32 -> INT_VIEW.set(indecies, offset, index);
			case UINT8 -> indecies[offset] = (byte)index;
		}
	}
	public int read(byte[] indecies, int offset){
		return switch(this){
			case UINT16 -> (char)CHAR_VIEW.get(indecies, offset);
			case UINT32 -> (int)INT_VIEW.get(indecies, offset);
			case UINT8 -> Byte.toUnsignedInt(indecies[offset]);
		};
	}
	
	public int getMaxSize(){
		return (switch(this){
			case UINT8 -> NumberSize.BYTE;
			case UINT16 -> NumberSize.SHORT;
			case UINT32 -> NumberSize.INT;
		}).maxSizeI;
	}
	
	public static VkIndexType from(int id){ return VUtils.fromID(VkIndexType.class, id); }
	
	public static VkIndexType max(VkIndexType a, VkIndexType b){
		return a.byteSize>b.byteSize? a : b;
	}
}
