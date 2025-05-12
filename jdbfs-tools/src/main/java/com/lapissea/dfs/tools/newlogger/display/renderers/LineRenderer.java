package com.lapissea.dfs.tools.newlogger.display.renderers;

import com.lapissea.dfs.tools.newlogger.display.VUtils;
import com.lapissea.dfs.tools.newlogger.display.vk.Std430;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Struct;

import java.awt.Color;
import java.nio.ByteBuffer;

public class LineRenderer{
	
	private static class Point extends Struct<Point>{
		private static final int SIZEOF;
		private static final int XY;
		private static final int RAD;
		private static final int COLOR;
		
		static{
			var layout = __struct(
				Std430.__vec2(),
				Std430.__float(),
				Std430.__u8vec4()
			);
			
			SIZEOF = layout.getSize();
			XY = layout.offsetof(0);
			RAD = layout.offsetof(1);
			COLOR = layout.offsetof(2);
		}
		
		void set(float x, float y, float rad, Color color){
			var address = this.address;
			MemoryUtil.memPutFloat(address + XY, x);
			MemoryUtil.memPutFloat(address + XY + Float.BYTES, y);
			MemoryUtil.memPutFloat(address + RAD, rad);
			MemoryUtil.memPutInt(address + COLOR, VUtils.toRGBAi4(color));
		}
		
		protected Point(ByteBuffer buff){ super(MemoryUtil.memAddress(buff), buff); }
		protected Point(long address)   { super(address, null); }
		@Override
		protected Point create(long address, ByteBuffer container){ return new Point(address); }
		@Override
		public int sizeof(){ return SIZEOF; }
	}
	
	
}
