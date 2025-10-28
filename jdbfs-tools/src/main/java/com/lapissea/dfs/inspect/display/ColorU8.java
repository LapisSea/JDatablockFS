package com.lapissea.dfs.inspect.display;

import com.lapissea.dfs.inspect.display.vk.Std430;
import com.lapissea.util.NotImplementedException;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Struct;
import org.lwjgl.system.StructBuffer;

import java.awt.Color;
import java.nio.ByteBuffer;

public class ColorU8 extends Struct<ColorU8>{
	public static final int SIZEOF;
	public static final int R, G, B, A;
	
	static{
		Layout layout = __struct(
			Std430.__uint8_t(),
			Std430.__uint8_t(),
			Std430.__uint8_t(),
			Std430.__uint8_t()
		);
		
		SIZEOF = layout.getSize();
		
		R = layout.offsetof(0);
		G = layout.offsetof(1);
		B = layout.offsetof(2);
		A = layout.offsetof(3);
	}
	
	public static class Buf extends StructBuffer<ColorU8, Buf>{
		private static final ColorU8 FAC = new ColorU8(-1);
		public Buf(ByteBuffer container){ super(container, container.remaining()/SIZEOF); }
		@Override
		protected ColorU8 getElementFactory(){ return FAC; }
		@Override
		protected Buf self(){ return this; }
		@Override
		protected Buf create(long address, ByteBuffer container, int mark, int position, int limit, int capacity){
			throw NotImplementedException.infer();//TODO: implement Buf.create()
		}
		
		private long addressNext(){
			return this.address + Integer.toUnsignedLong(nextGetIndex())*ColorU8.SIZEOF;
		}
		
		public void put(int rgba){
			var address = addressNext();
			MemoryUtil.memPutInt(address, rgba);
		}
		public void put(Color color){
			var address = addressNext();
			MemoryUtil.memPutInt(address, VUtils.toRGBAi4(color));
		}
	}
	
	public void set(int rgba){
		MemoryUtil.memPutInt(address(), rgba);
	}
	public void set(Color color){
		MemoryUtil.memPutInt(address(), VUtils.toRGBAi4(color));
	}
	public void set(int r, int g, int b, int a){
		assert r>0;
		assert g>0;
		assert b>0;
		assert a>0;
		assert r<256;
		assert g<256;
		assert b<256;
		assert a<256;
		MemoryUtil.memPutInt(address(), r|(g<<8)|(b<<16)|(a<<24));
	}
	
	public byte getR()  { return MemoryUtil.memGetByte(address() + R); }
	public byte getG()  { return MemoryUtil.memGetByte(address() + G); }
	public byte getB()  { return MemoryUtil.memGetByte(address() + B); }
	public byte getA()  { return MemoryUtil.memGetByte(address() + A); }
	public int getRGBA(){ return MemoryUtil.memGetInt(address()); }
	public Color getColor(){
		return new Color(Byte.toUnsignedInt(getR()), Byte.toUnsignedInt(getG()), Byte.toUnsignedInt(getB()), Byte.toUnsignedInt(getA()));
	}
	
	public ColorU8(long address){ super(address, null); }
	@Override
	public ColorU8 create(long address, ByteBuffer container){ return new ColorU8(address); }
	@Override
	public int sizeof(){ return SIZEOF; }
	
	@Override
	public String toString(){
		return "#" +
		       Integer.toHexString(Byte.toUnsignedInt(getR())) +
		       Integer.toHexString(Byte.toUnsignedInt(getG())) +
		       Integer.toHexString(Byte.toUnsignedInt(getB())) +
		       Integer.toHexString(Byte.toUnsignedInt(getA()));
	}
}
