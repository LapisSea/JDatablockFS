package test;

import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBTTAlignedQuad;
import org.lwjgl.stb.STBTTBakedChar;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.system.MemoryStack;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.stb.STBTruetype.*;
import static org.lwjgl.system.MemoryStack.*;

public class TTFont{
	
	private static final int FIRST_CHAR=6;
	
	private final STBTTFontinfo info;
	private final ByteBuffer    ttfBB;
	
	private final int ascent;
	private final int descent;
	private final int lineGap;
	
	private class Bitmap{
		final STBTTBakedChar.Buffer charInfo;
		final int                   texture;
		final float                 pixelHeight;
		final int                   bitmapWidth;
		final int                   bitmapHeight;
		
		private Bitmap(float pixelHeight){
			this.pixelHeight=pixelHeight;
			
			var bitmapWidth =64;
			var bitmapHeight=32;
			
			texture=glGenTextures();
			charInfo=STBTTBakedChar.malloc(255-FIRST_CHAR);
			
			ByteBuffer bitmap;
			
			while(true){
				try{
					bitmap=BufferUtils.createByteBuffer(bitmapWidth*bitmapHeight);
					if(stbtt_BakeFontBitmap(ttfBB, pixelHeight, bitmap, bitmapWidth, bitmapHeight, FIRST_CHAR, charInfo)<=0){
						throw new IllegalArgumentException();
					}
				}catch(IllegalArgumentException e){
					if(bitmapWidth<bitmapHeight){
						bitmapWidth*=2;
					}else{
						bitmapHeight*=2;
					}
					continue;
				}
				
				break;
				
			}
			
			glBindTexture(GL_TEXTURE_2D, texture);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, bitmapWidth, bitmapHeight, 0, GL_ALPHA, GL_UNSIGNED_BYTE, bitmap);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
//			glBindTexture(GL_TEXTURE_2D, 0);
			
			this.bitmapWidth=bitmapWidth;
			this.bitmapHeight=bitmapHeight;
		}
		
		void free(){
			charInfo.free();
			glDeleteTextures(texture);
		}
	}
	
	private final List<Bitmap> bitmapCache=new ArrayList<>();
	
	public TTFont(String ttfPath){
		
		byte[] ttfData;
		try{
			ttfData=getClass().getResourceAsStream(ttfPath).readAllBytes();
		}catch(IOException e){
			throw new RuntimeException(e);
		}
		
		
		ttfBB=ByteBuffer.allocateDirect(ttfData.length).order(ByteOrder.nativeOrder());
		ttfBB.put(ttfData);
		ttfBB.flip();
		
		info=STBTTFontinfo.create();
		if(!stbtt_InitFont(info, ttfBB)){
			throw new IllegalStateException("Failed to initialize font information.");
		}
		
		try(MemoryStack stack=stackPush()){
			IntBuffer pAscent =stack.mallocInt(1);
			IntBuffer pDescent=stack.mallocInt(1);
			IntBuffer pLineGap=stack.mallocInt(1);
			
			stbtt_GetFontVMetrics(info, pAscent, pDescent, pLineGap);
			
			ascent=pAscent.get(0);
			descent=pDescent.get(0);
			lineGap=pLineGap.get(0);
		}
		
	}
	
	
	private Bitmap getBitmap(float pixelHeight){
		for(Bitmap bitmap : bitmapCache){
			if(Math.abs(bitmap.pixelHeight-pixelHeight)<0.5) return bitmap;
		}
		if(bitmapCache.size()>6){
			bitmapCache.remove(0).free();
		}
		
		Bitmap bitmap=new Bitmap(pixelHeight);
		bitmapCache.add(bitmap);
		return bitmap;
	}
	
	private float getStringWidth(STBTTFontinfo info, String text, float fontHeight){
		int width=0;
		
		try(MemoryStack stack=stackPush()){
			IntBuffer pAdvancedWidth  =stack.mallocInt(1);
			IntBuffer pLeftSideBearing=stack.mallocInt(1);
			
			
			for(int i=0;i<text.length();i++){
				stbtt_GetCodepointHMetrics(info, text.charAt(i), pAdvancedWidth, pLeftSideBearing);
				width+=pAdvancedWidth.get(0);
			}
		}
		
		return width*stbtt_ScaleForPixelHeight(info, fontHeight);
	}
	
	public float[] getStringBounds(String string, float pixelHeight){
		float minX=Float.MAX_VALUE;
		float minY=Float.MAX_VALUE;
		float maxX=Float.MIN_VALUE;
		float maxY=Float.MIN_VALUE;
		
		Bitmap bitmap=getBitmap(pixelHeight);
		
		try(MemoryStack stack=stackPush()){
			
			FloatBuffer x=stack.floats(0.0f);
			FloatBuffer y=stack.floats(0.0f);
			
			STBTTAlignedQuad q=STBTTAlignedQuad.mallocStack(stack);
			
			for(int i=0;i<string.length();i++){
				char cp=string.charAt(i);
				
				stbtt_GetBakedQuad(bitmap.charInfo, bitmap.bitmapWidth, bitmap.bitmapHeight, cp-FIRST_CHAR, x, y, q, true);
				
				minX=Math.min(q.x0(), minX);
				minX=Math.min(q.x1(), minX);
				minY=Math.min(q.y0(), minY);
				minY=Math.min(q.y1(), minY);
				
				maxX=Math.max(q.x0(), maxX);
				maxX=Math.max(q.x1(), maxX);
				maxY=Math.max(q.y0(), maxY);
				maxY=Math.max(q.y1(), maxY);
				
			}
		}
		return new float[]{maxX-minX, maxY-minY};
	}
	
	
	public void outlineString(String string, float pixelHeight){
		//TODO
	}
	
	public void fillString(String string, float pixelHeight, IntFunction<AutoCloseable> bulkHook){
		Bitmap bitmap=getBitmap(pixelHeight);
		
		try(MemoryStack stack=stackPush()){
			
			FloatBuffer x=stack.floats(0.0f);
			FloatBuffer y=stack.floats(0.0f);
			
			STBTTAlignedQuad q=STBTTAlignedQuad.mallocStack(stack);

//			glEnable(GL_TEXTURE);
			glBindTexture(GL_TEXTURE_2D, bitmap.texture);
			glEnable(GL_TEXTURE_2D);
			glEnable(GL_BLEND);
			glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
			
			try(var apply=bulkHook.apply(GL_QUADS)){
				for(int i=0;i<string.length();i++){
					char cp=string.charAt(i);
					
					
					stbtt_GetBakedQuad(bitmap.charInfo, bitmap.bitmapWidth, bitmap.bitmapHeight, cp-FIRST_CHAR, x, y, q, true);
					
					
					float
						x0=q.x0(),
						x1=q.x1(),
						y0=q.y0(),
						y1=q.y1();
					
					glTexCoord2f(q.s0(), q.t0());
					glVertex2f(x0, y0);
					
					glTexCoord2f(q.s1(), q.t0());
					glVertex2f(x1, y0);
					
					glTexCoord2f(q.s1(), q.t1());
					glVertex2f(x1, y1);
					
					glTexCoord2f(q.s0(), q.t1());
					glVertex2f(x0, y1);
					
				}
			}catch(Exception e){
				e.printStackTrace();
			}
			
			glBindTexture(GL_TEXTURE_2D, 0);
			glDisable(GL_TEXTURE);
		}
	}
	
	public boolean canFontDisplay(int c){
		if(c<FIRST_CHAR) return false;
		return c<=255;
	}
}
