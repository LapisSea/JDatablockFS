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
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

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
		int texture=-1;
		final float pixelHeight;
		final int   bitmapWidth;
		final int   bitmapHeight;
		
		long lastUsed=System.currentTimeMillis();
		
		private Bitmap(float pixelHeight){
			this.pixelHeight=pixelHeight;
			
			var bitmapWidth =32;
			var bitmapHeight=32;
			
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
			this.bitmapWidth=bitmapWidth;
			this.bitmapHeight=bitmapHeight;
			
			ByteBuffer finalBitmap=bitmap;
			openglTask.accept(()->{
				texture=glGenTextures();
				glBindTexture(GL_TEXTURE_2D, texture);
				glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, this.bitmapWidth, this.bitmapHeight, 0, GL_ALPHA, GL_UNSIGNED_BYTE, finalBitmap);
				glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
				glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
			});
		}
		
		void free(){
			openglTask.accept(()->{
				charInfo.free();
				glDeleteTextures(texture);
			});
		}
	}
	
	private final List<Bitmap>               bitmapCache=new ArrayList<>();
	private final IntFunction<AutoCloseable> bulkHook;
	private final Runnable                   renderRequest;
	private final Consumer<Runnable>         openglTask;
	
	public TTFont(String ttfPath, IntFunction<AutoCloseable> bulkHook, Runnable renderRequest, Consumer<Runnable> openglTask){
		this.bulkHook=bulkHook;
		this.renderRequest=renderRequest;
		this.openglTask=openglTask;
		
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
	
	private boolean generating;
	
	private Bitmap getBitmap(float pixelHeight){
		
		Bitmap best;
		synchronized(bitmapCache){
			if(bitmapCache.isEmpty()) bitmapCache.add(new Bitmap(16));
			best=bitmapCache.get(0);
			for(Bitmap bitmap : bitmapCache){
				if(bitmap.texture==-1) continue;
				
				var bestDist=Math.abs(best.pixelHeight-pixelHeight);
				var thisDist=Math.abs(bitmap.pixelHeight-pixelHeight);
				if(thisDist<0.5){
					bitmap.lastUsed=System.currentTimeMillis();
					return bitmap;
				}
				if(thisDist<bestDist) best=bitmap;
				
			}
		}
		
		var bestDist=Math.abs(best.pixelHeight-pixelHeight);
		
		gen:
		if(!generating){
			synchronized(bitmapCache){
				for(Bitmap bitmap : bitmapCache){
					var thisDist=Math.abs(bitmap.pixelHeight-pixelHeight);
					if(thisDist<0.5) break gen;
				}
			}
			
			generating=true;
			new Thread(()->{
				try{
					Bitmap bitmap=new Bitmap(pixelHeight);
					
					synchronized(bitmapCache){
						if(bitmapCache.size()>6){
							IntStream.range(0, bitmapCache.size())
							         .boxed()
							         .min(Comparator.comparingLong(i->bitmapCache.get(i).lastUsed))
							         .ifPresent(i->bitmapCache.remove((int)i).free());
						}
						bitmap.lastUsed=System.currentTimeMillis();
						bitmapCache.add(bitmap);
					}
					renderRequest.run();
				}finally{
					generating=false;
				}
			}).start();
		}
		
		best.lastUsed=System.currentTimeMillis();
		return best;
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
		float minX=0;
		float minY=0;
		float maxX=Float.MIN_VALUE;
		float maxY=Float.MIN_VALUE;
		
		Bitmap bitmap=getBitmap(pixelHeight);
		
		float scale=pixelHeight/bitmap.pixelHeight;
		
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
		return new float[]{(maxX-minX)*scale, (maxY-minY)*scale};
	}
	
	
	public void outlineString(String string, float pixelHeight){
		//TODO
	}
	
	public void fillString(String string, float pixelHeight){
		Bitmap bitmap=getBitmap(pixelHeight);
		float  scale =pixelHeight/bitmap.pixelHeight;
		
		try(MemoryStack stack=stackPush()){
			
			FloatBuffer x=stack.floats(0.0f);
			FloatBuffer y=stack.floats(0.0f);
			
			STBTTAlignedQuad q=STBTTAlignedQuad.mallocStack(stack);
			
			glBindTexture(GL_TEXTURE_2D, bitmap.texture);
			glEnable(GL_TEXTURE_2D);
			
			try(var apply=bulkHook.apply(GL_QUADS)){
				for(int i=0;i<string.length();i++){
					char cp=string.charAt(i);
					
					
					stbtt_GetBakedQuad(bitmap.charInfo, bitmap.bitmapWidth, bitmap.bitmapHeight, cp-FIRST_CHAR, x, y, q, true);
					
					
					float
						x0=q.x0()*scale,
						x1=q.x1()*scale,
						y0=q.y0()*scale,
						y1=q.y1()*scale;
					
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
