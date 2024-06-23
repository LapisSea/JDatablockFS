package com.lapissea.dfs.io.content;

import com.lapissea.dfs.config.ConfigDefs;
import com.lapissea.dfs.internal.MyUnsafe;
import com.lapissea.dfs.type.compilation.FieldCompiler;

import java.lang.invoke.VarHandle;
import java.util.Objects;

import static java.lang.invoke.MethodHandles.byteArrayViewVarHandle;
import static java.nio.ByteOrder.LITTLE_ENDIAN;

public final class BBView{
	
	private static final class UnsafeView{
		
		private static final int ARRAY_BYTE_BASE_OFFSET = MyUnsafe.UNSAFE.arrayBaseOffset(byte[].class);
		
		private static long mem(byte[] ba, int index, int size){
			return Objects.checkIndex(index, ba.length - (size - 1)) + ARRAY_BYTE_BASE_OFFSET;
		}
		
		private static void putByte_(byte[] byteBuffer, long memOff, int localOff, long v){
			MyUnsafe.UNSAFE.putByte(byteBuffer, memOff + localOff, (byte)(v >>> (localOff*8)));
		}
		private static int getByte_(byte[] byteBuffer, long memOff){
			return MyUnsafe.UNSAFE.getByte(byteBuffer, memOff)&0xFF;
		}
		private static long getByte_(byte[] byteBuffer, long memOff, int localOff){
			return ((long)getByte_(byteBuffer, memOff + localOff))<<(localOff*8);
		}
		
		private static void putShort(byte[] byteBuffer, long memOff, int localOff, long v){
			MyUnsafe.UNSAFE.putChar(byteBuffer, memOff + localOff, (char)(v >>> (localOff*8)));
		}
		private static char getShort(byte[] byteBuffer, long memOff){
			return MyUnsafe.UNSAFE.getChar(byteBuffer, memOff);
		}
		private static long getShort(byte[] byteBuffer, long memOff, int localOff){
			return ((long)MyUnsafe.UNSAFE.getChar(byteBuffer, memOff + localOff))<<(localOff*8);
		}
		
		private static void putInt__(byte[] byteBuffer, long memOff, int localOff, long v){
			MyUnsafe.UNSAFE.putInt(byteBuffer, memOff + localOff, (int)(v >>> (localOff*8)));
		}
		private static long getInt__(byte[] byteBuffer, long memOff, int localOff){
			return Integer.toUnsignedLong(MyUnsafe.UNSAFE.getInt(byteBuffer, memOff + localOff))<<(localOff*8);
		}
		
		private static void writeInt3(byte[] byteBuffer, int off, int v){
			var memOff = mem(byteBuffer, off, 3);
			putShort(byteBuffer, memOff, 0, v);
			putByte_(byteBuffer, memOff, 2, v);
		}
		private static void writeInt5(byte[] byteBuffer, int off, long v){
			var memOff = mem(byteBuffer, off, 5);
			putInt__(byteBuffer, memOff, 0, v);
			putByte_(byteBuffer, memOff, 4, v);
		}
		private static void writeInt6(byte[] byteBuffer, int off, long v){
			var memOff = mem(byteBuffer, off, 6);
			putInt__(byteBuffer, memOff, 0, v);
			putShort(byteBuffer, memOff, 4, v);
		}
		private static void writeInt7(byte[] byteBuffer, int off, long v){
			var memOff = mem(byteBuffer, off, 7);
			putInt__(byteBuffer, memOff, 0, v);
			putShort(byteBuffer, memOff, 4, v);
			putByte_(byteBuffer, memOff, 6, v);
		}
		
		
		private static int readUnsignedInt3(byte[] byteBuffer, int offset){
			var memOff = mem(byteBuffer, offset, 3);
			return getShort(byteBuffer, memOff)|
			       getByte_(byteBuffer, memOff + 2)<<16;
		}
		private static long readUnsignedInt5(byte[] byteBuffer, int offset){
			var memOff = mem(byteBuffer, offset, 5);
			return getInt__(byteBuffer, memOff, 0)|
			       getByte_(byteBuffer, memOff, 4);
		}
		private static long readUnsignedInt6(byte[] byteBuffer, int offset){
			var memOff = mem(byteBuffer, offset, 6);
			return getInt__(byteBuffer, memOff, 0)|
			       getShort(byteBuffer, memOff, 4);
		}
		private static long readUnsignedInt7(byte[] byteBuffer, int offset){
			var memOff = mem(byteBuffer, offset, 7);
			return getInt__(byteBuffer, memOff, 0)|
			       getShort(byteBuffer, memOff, 4)|
			       getByte_(byteBuffer, memOff, 6);
		}
	}
	
	private static final class VarHandleView{
		private static void writeInt3(byte[] byteBuffer, int off, int v){
			writeInt2(byteBuffer, off, (short)v);
			byteBuffer[off + 2] = (byte)((v >>> 16)&0xFF);
		}
		private static void writeInt5(byte[] byteBuffer, int off, long v){
			writeInt4(byteBuffer, off, (int)v);
			byteBuffer[off + 4] = (byte)(v >>> 32);
		}
		private static void writeInt6(byte[] byteBuffer, int off, long v){
			writeInt4(byteBuffer, off, (int)v);
			writeInt2(byteBuffer, off + 4, (short)(v >>> 32));
		}
		private static void writeInt7(byte[] byteBuffer, int off, long v){
			writeInt4(byteBuffer, off, (int)(v));
			writeInt2(byteBuffer, off + 4, (short)(v >>> 32));
			byteBuffer[off + 6] = (byte)(v >>> 48);
		}
		
		private static int readUnsignedInt3(byte[] byteBuffer, int offset){
			return readUnsignedInt2(byteBuffer, offset)|
			       ((byteBuffer[offset + 2]&0xFF)<<16);
		}
		private static long readUnsignedInt5(byte[] byteBuffer, int offset){
			return Integer.toUnsignedLong(readInt4(byteBuffer, offset))|
			       ((long)(byteBuffer[offset + 4]&0xFF)<<32);
		}
		private static long readUnsignedInt6(byte[] byteBuffer, int offset){
			return Integer.toUnsignedLong(readInt4(byteBuffer, offset))|
			       (((long)readUnsignedInt2(byteBuffer, offset + 4))<<32);
		}
		private static long readUnsignedInt7(byte[] byteBuffer, int offset){
			return Integer.toUnsignedLong(readInt4(byteBuffer, offset))|
			       (((long)readUnsignedInt2(byteBuffer, offset + 4))<<32)|
			       ((long)(byteBuffer[offset + 6]&0xFF)<<48);
		}
	}
	
	private static final boolean UNS = ConfigDefs.FIELD_ACCESS_TYPE.resolve() == FieldCompiler.AccessType.UNSAFE;
	
	private static final VarHandle SHORT_VIEW  = var(short.class);
	private static final VarHandle CHAR_VIEW   = var(char.class);
	private static final VarHandle INT_VIEW    = var(int.class);
	private static final VarHandle LONG_VIEW   = var(long.class);
	private static final VarHandle FLOAT_VIEW  = var(float.class);
	private static final VarHandle DOUBLE_VIEW = var(double.class);
	
	private static VarHandle var(Class<?> c){ return byteArrayViewVarHandle(c.arrayType(), LITTLE_ENDIAN).withInvokeExactBehavior(); }
	
	//////////WRITE//////////
	
	public static void writeInt1(byte[] out, int off, byte value)    { out[off] = value; }
	public static void writeChar2(byte[] byteBuffer, int off, char v){ CHAR_VIEW.set(byteBuffer, off, v); }
	public static void writeInt2(byte[] byteBuffer, int off, short v){ SHORT_VIEW.set(byteBuffer, off, v); }
	public static void writeInt3(byte[] byteBuffer, int off, int v){
		if(UNS) UnsafeView.writeInt3(byteBuffer, off, v);
		else VarHandleView.writeInt3(byteBuffer, off, v);
	}
	public static void writeInt4(byte[] byteBuffer, int off, int v){ INT_VIEW.set(byteBuffer, off, v); }
	public static void writeInt5(byte[] byteBuffer, int off, long v){
		if(UNS) UnsafeView.writeInt5(byteBuffer, off, v);
		else VarHandleView.writeInt5(byteBuffer, off, v);
	}
	public static void writeInt6(byte[] byteBuffer, int off, long v){
		if(UNS) UnsafeView.writeInt6(byteBuffer, off, v);
		else VarHandleView.writeInt6(byteBuffer, off, v);
	}
	public static void writeInt7(byte[] byteBuffer, int off, long v){
		if(UNS) UnsafeView.writeInt7(byteBuffer, off, v);
		else VarHandleView.writeInt7(byteBuffer, off, v);
	}
	public static void writeInt8(byte[] byteBuffer, int off, long v)    { LONG_VIEW.set(byteBuffer, off, v); }
	public static void writeFloat4(byte[] byteBuffer, int off, float v) { FLOAT_VIEW.set(byteBuffer, off, v); }
	public static void writeFloat8(byte[] byteBuffer, int off, double v){ DOUBLE_VIEW.set(byteBuffer, off, v); }
	
	//////////READ//////////
	
	public static char readChar2(byte[] byteBuffer, int offset)      { return (char)CHAR_VIEW.get(byteBuffer, offset); }
	public static short readInt2(byte[] byteBuffer, int offset)      { return (short)SHORT_VIEW.get(byteBuffer, offset); }
	public static int readUnsignedInt2(byte[] byteBuffer, int offset){ return readChar2(byteBuffer, offset); }
	public static int readUnsignedInt3(byte[] byteBuffer, int offset){
		if(UNS) return UnsafeView.readUnsignedInt3(byteBuffer, offset);
		else return VarHandleView.readUnsignedInt3(byteBuffer, offset);
	}
	public static int readInt4(byte[] byteBuffer, int offset){ return (int)INT_VIEW.get(byteBuffer, offset); }
	public static long readUnsignedInt5(byte[] byteBuffer, int offset){
		if(UNS) return UnsafeView.readUnsignedInt5(byteBuffer, offset);
		else return VarHandleView.readUnsignedInt5(byteBuffer, offset);
	}
	public static long readUnsignedInt6(byte[] byteBuffer, int offset){
		if(UNS) return UnsafeView.readUnsignedInt6(byteBuffer, offset);
		else return VarHandleView.readUnsignedInt6(byteBuffer, offset);
	}
	public static long readUnsignedInt7(byte[] byteBuffer, int offset){
		if(UNS) return UnsafeView.readUnsignedInt7(byteBuffer, offset);
		else return VarHandleView.readUnsignedInt7(byteBuffer, offset);
	}
	public static long readInt8(byte[] byteBuffer, int offset) { return (long)LONG_VIEW.get(byteBuffer, offset); }
	public static float readFloat4(byte[] byteBuffer, int off) { return (float)FLOAT_VIEW.get(byteBuffer, off); }
	public static double readFloat8(byte[] byteBuffer, int off){ return (double)DOUBLE_VIEW.get(byteBuffer, off); }
}
