package com.lapissea.cfs.tools;

import com.lapissea.util.LogUtil;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL11;
import org.lwjgl.stb.STBTTAlignedQuad;
import org.lwjgl.stb.STBTTBakedChar;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTruetype;
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
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

public class TTFont{
	
	private static Boolean ANOSOTROPIC_SUPPORTED;
	
	
	private final	int replacer;
	private final STBTTFontinfo info;
	private final ByteBuffer    ttfBB;
	
	private final int min;
	private       int max;
	
	private class Bitmap{
		final STBTTBakedChar.Buffer charInfo;
		int texture       =-1;
		int outlineTexture=-1;
		final float pixelHeight;
		final int   bitmapWidth;
		final int   bitmapHeight;
		
		long lastUsed=System.currentTimeMillis();
		
		private Bitmap(float pixelHeight){
			this.pixelHeight=pixelHeight;
			
			var bitmapWidth =32;
			var bitmapHeight=32;
			
			charInfo=STBTTBakedChar.malloc(max-min);
			
			
			ByteBuffer bitmap;
			
			while(true){
				try{
					bitmap=BufferUtils.createByteBuffer(bitmapWidth*bitmapHeight);
					if(STBTruetype.stbtt_BakeFontBitmap(ttfBB, pixelHeight, bitmap, bitmapWidth, bitmapHeight, min, charInfo)<=0){
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
			
			LogUtil.println("Font map compiled:", pixelHeight, min, "->", max, "Size:", bitmapWidth+"x"+bitmapHeight);
			
			ByteBuffer fillBitmap   =bitmap;
			ByteBuffer outlineBitmap=BufferUtils.createByteBuffer(fillBitmap.capacity());
			
			interface PixelGetter{
				int get(int x, int y);
			}
			
			PixelGetter get=(x, y)->{
				int index=x*this.bitmapWidth+y;
				return fillBitmap.get(index)&0xFF;
			};
			
			for(int x=0;x<bitmapWidth;x++){
				for(int y=0;y<bitmapHeight;y++){
					int b   =get.get(x, y);
					int diff=0;
					
					if(x>0)diff=Math.max(diff, Math.abs(get.get(x-1, y)-b));
					if(y>0)diff=Math.max(diff, Math.abs(get.get(x, y-1)-b));
					if(x<bitmapWidth-1)diff=Math.max(diff, Math.abs(get.get(x+1, y)-b));
					if(y<bitmapHeight-1)diff=Math.max(diff, Math.abs(get.get(x, y+1)-b));
					
					
					int index=x*bitmapWidth+y;
					outlineBitmap.put(index, (byte)diff);
				}
			}
			
			openglTask.accept(()->{
				texture=GL11.glGenTextures();
				Function<ByteBuffer, Integer> uploadTexture=bb->{
					var texture=GL11.glGenTextures();
					GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
					GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_ALPHA, this.bitmapWidth, this.bitmapHeight, 0, GL11.GL_ALPHA, GL11.GL_UNSIGNED_BYTE, bb);
					GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
					GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
					
					if(ANOSOTROPIC_SUPPORTED==null){
						String str=GL11.glGetString(GL11.GL_EXTENSIONS);
						ANOSOTROPIC_SUPPORTED=str!=null&&str.contains("GL_EXT_texture_filter_anisotropic");
					}
					
					if(ANOSOTROPIC_SUPPORTED){
						float[] aniso={0};
						GL11.glGetFloatv(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, aniso);
						GL11.glTexParameterf(GL11.GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, aniso[0]);
					}
					return texture;
				};
				
				texture=uploadTexture.apply(fillBitmap);
				outlineTexture=uploadTexture.apply(outlineBitmap);
				
			});
		}
		
		void free(){
			openglTask.accept(()->{
				charInfo.free();
				GL11.glDeleteTextures(texture);
				GL11.glDeleteTextures(outlineTexture);
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
		if(!STBTruetype.stbtt_InitFont(info, ttfBB)){
			throw new IllegalStateException("Failed to initialize font information.");
		}
		
		try(MemoryStack stack=MemoryStack.stackPush()){
			IntBuffer pAscent =stack.mallocInt(1);
			IntBuffer pDescent=stack.mallocInt(1);
			IntBuffer pLineGap=stack.mallocInt(1);
			
			STBTruetype.stbtt_GetFontVMetrics(info, pAscent, pDescent, pLineGap);
			
		}
		replacer=STBTruetype.stbtt_FindGlyphIndex(info, 11111);
		int min=255;
		for(int i=0;i<256;i++){
			int g       =STBTruetype.stbtt_FindGlyphIndex(info, i);
			if(g!=replacer){
				min=i;
				break;
			}
		}
		this.min=min;
		this.max=min+10;
		
	}
	
	private boolean generating;
	
	private Bitmap getBitmap(float pixelHeight){
		
		Bitmap best;
		synchronized(bitmapCache){
			if(bitmapCache.isEmpty()) bitmapCache.add(new Bitmap(pixelHeight));
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
						if(bitmapCache.size()>7){
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
		
		try(MemoryStack stack=MemoryStack.stackPush()){
			IntBuffer pAdvancedWidth  =stack.mallocInt(1);
			IntBuffer pLeftSideBearing=stack.mallocInt(1);
			
			
			for(int i=0;i<text.length();i++){
				STBTruetype.stbtt_GetCodepointHMetrics(info, text.charAt(i), pAdvancedWidth, pLeftSideBearing);
				width+=pAdvancedWidth.get(0);
			}
		}
		
		return width*STBTruetype.stbtt_ScaleForPixelHeight(info, fontHeight);
	}
	
	public float[] getStringBounds(String string, float pixelHeight){
		pushMax(string);
		
		float minX=0;
		float minY=Float.MAX_VALUE;
		float maxX=Float.MIN_VALUE;
		float maxY=Float.MIN_VALUE;
		
		Bitmap bitmap=getBitmap(pixelHeight);
		
		float scale=pixelHeight/bitmap.pixelHeight;
		
		try(MemoryStack stack=MemoryStack.stackPush()){
			
			FloatBuffer x=stack.floats(0.0f);
			FloatBuffer y=stack.floats(0.0f);
			
			STBTTAlignedQuad q=STBTTAlignedQuad.mallocStack(stack);
			
			for(int i=0;i<string.length();i++){
				char cp=string.charAt(i);
				
				STBTruetype.stbtt_GetBakedQuad(bitmap.charInfo, bitmap.bitmapWidth, bitmap.bitmapHeight, cp-min, x, y, q, true);
				
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
	
	private void pushMax(String string){
		int maxRequested=0;
		for(int i=0;i<string.length();i++){
			int cp=string.charAt(i);
			if(cp>255) continue;
			maxRequested=Math.max(maxRequested, cp+1);
		}
		if(max<maxRequested){
			max=maxRequested;
			bitmapCache.forEach(Bitmap::free);
			bitmapCache.clear();
		}
	}
	
	public void outlineString(String string, float pixelHeight, float x, float y){
		drawString(string, pixelHeight, x,y,bitmap->bitmap.outlineTexture);
	}
	
	public void fillString(String string, float pixelHeight, float x, float y){
		drawString(string, pixelHeight,x,y, bitmap->bitmap.texture);
	}
	
	private void drawString(String string, float pixelHeight, float xOff, float yOff, Function<Bitmap, Integer> getTex){
		pushMax(string);
		
		Bitmap bitmap=getBitmap(pixelHeight);
		float  scale =pixelHeight/bitmap.pixelHeight;
		
		try(MemoryStack stack=MemoryStack.stackPush()){
			
			FloatBuffer x=stack.floats(0.0f);
			FloatBuffer y=stack.floats(0.0f);
			
			STBTTAlignedQuad q=STBTTAlignedQuad.mallocStack(stack);
			
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, getTex.apply(bitmap));
			GL11.glEnable(GL11.GL_TEXTURE_2D);
			
			try(var apply=bulkHook.apply(GL11.GL_QUADS)){
				for(int i=0;i<string.length();i++){
					char cp=string.charAt(i);
					
					
					STBTruetype.stbtt_GetBakedQuad(bitmap.charInfo, bitmap.bitmapWidth, bitmap.bitmapHeight, cp-min, x, y, q, true);
					
					
					float
						x0=q.x0()*scale+xOff,
						x1=q.x1()*scale+xOff,
						y0=q.y0()*scale+yOff,
						y1=q.y1()*scale+yOff;
					
					GL11.glTexCoord2f(q.s0(), q.t0());
					GL11.glVertex2f(x0, y0);
					
					GL11.glTexCoord2f(q.s1(), q.t0());
					GL11.glVertex2f(x1, y0);
					
					GL11.glTexCoord2f(q.s1(), q.t1());
					GL11.glVertex2f(x1, y1);
					
					GL11.glTexCoord2f(q.s0(), q.t1());
					GL11.glVertex2f(x0, y1);
					
				}
			}catch(Exception e){
				e.printStackTrace();
			}
			
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
			GL11.glDisable(GL11.GL_TEXTURE);
		}
	}
	
	public boolean canFontDisplay(int c){
		if(c<min) return false;
		if(c>max) return false;
		int g=STBTruetype.stbtt_FindGlyphIndex(info, c);
		return g!=replacer;
	}
}
