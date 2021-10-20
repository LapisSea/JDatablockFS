package com.lapissea.cfs.tools;

import com.lapissea.util.LogUtil;
import org.lwjgl.BufferUtils;
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
import java.util.Objects;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.IntStream;

import static com.lapissea.util.PoolOwnThread.*;
import static org.lwjgl.system.MemoryStack.*;

public class TTFont extends GLFont{
	
	
	private final int           replacer;
	private final STBTTFontinfo info;
	private final ByteBuffer    ttfBB;
	
	private final int min;
	private       int max;
	
	private class Bitmap{
		final STBTTBakedChar.Buffer charInfo;
		Texture texture;
		Texture outlineTexture;
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
			
			//LogUtil.println("Font map compiled:", pixelHeight, min, "->", max, "Size:", bitmapWidth+"x"+bitmapHeight);
			
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
					
					if(x>0) diff=Math.max(diff, Math.abs(get.get(x-1, y)-b));
					if(y>0) diff=Math.max(diff, Math.abs(get.get(x, y-1)-b));
					if(x<bitmapWidth-1) diff=Math.max(diff, Math.abs(get.get(x+1, y)-b));
					if(y<bitmapHeight-1) diff=Math.max(diff, Math.abs(get.get(x, y+1)-b));
					
					
					int index=x*bitmapWidth+y;
					outlineBitmap.put(index, (byte)diff);
				}
			}
			boolean[] instant={false};
			openglTask.accept(()->{
				instant[0]=true;
				texture=uploadTexture(this.bitmapWidth, this.bitmapHeight, GL11.GL_ALPHA, fillBitmap);
				outlineTexture=uploadTexture(this.bitmapWidth, this.bitmapHeight, GL11.GL_ALPHA, outlineBitmap);
			});
			if(!instant[0]){
				renderRequest.run();
			}
		}
		
		void free(){
			openglTask.accept(()->{
				charInfo.free();
				texture.delete();
				outlineTexture.delete();
			});
		}
	}
	
	private final ReadWriteLock                                            cacheLock  =new ReentrantReadWriteLock();
	private final List<Bitmap>                                             bitmapCache=new ArrayList<>();
	private final Function<BinaryDrawing.DrawMode, BinaryDrawing.BulkDraw> bulkHook;
	private final Runnable                                                 renderRequest;
	private final Consumer<Runnable>                                       openglTask;
	
	private static byte[] readData(String ttfPath){
		try(var io=Objects.requireNonNull(TTFont.class.getResourceAsStream(ttfPath))){
			return io.readAllBytes();
		}catch(IOException e){
			throw new RuntimeException(e);
		}
	}
	
	public TTFont(String ttfPath, Function<BinaryDrawing.DrawMode, BinaryDrawing.BulkDraw> bulkHook, Runnable renderRequest, Consumer<Runnable> openglTask){
		this(readData(ttfPath), bulkHook, renderRequest, openglTask);
	}
	public TTFont(byte[] ttfData, Function<BinaryDrawing.DrawMode, BinaryDrawing.BulkDraw> bulkHook, Runnable renderRequest, Consumer<Runnable> openglTask){
		this.bulkHook=bulkHook;
		this.renderRequest=renderRequest;
		this.openglTask=openglTask;
		
		
		ttfBB=ByteBuffer.allocateDirect(ttfData.length).order(ByteOrder.nativeOrder());
		ttfBB.put(ttfData);
		ttfBB.flip();
		
		info=STBTTFontinfo.create();
		if(!STBTruetype.stbtt_InitFont(info, ttfBB)){
			throw new IllegalStateException("Failed to initialize font information.");
		}
		
		try(MemoryStack stack=stackPush()){
			IntBuffer pAscent =stack.mallocInt(1);
			IntBuffer pDescent=stack.mallocInt(1);
			IntBuffer pLineGap=stack.mallocInt(1);
			
			STBTruetype.stbtt_GetFontVMetrics(info, pAscent, pDescent, pLineGap);
			
		}
		replacer=STBTruetype.stbtt_FindGlyphIndex(info, 11111);
		int min=255;
		for(int i=0;i<256;i++){
			int g=STBTruetype.stbtt_FindGlyphIndex(info, i);
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
		
		var    r=cacheLock.readLock();
		Bitmap best;
		try{
			r.lock();
			
			if(bitmapCache.isEmpty()) bitmapCache.add(new Bitmap(Math.min(pixelHeight, 20)));
			best=bitmapCache.get(0);
			for(Bitmap bitmap : bitmapCache){
				if(bitmap.texture==null) continue;
				
				var bestDist=Math.abs(best.pixelHeight-pixelHeight);
				var thisDist=Math.abs(bitmap.pixelHeight-pixelHeight);
				if(thisDist<0.5){
					bitmap.lastUsed=System.currentTimeMillis();
					return bitmap;
				}
				if(thisDist<bestDist) best=bitmap;
				
			}
		}finally{
			r.unlock();
		}
		
		gen:
		if(!generating){
			try{
				r.lock();
				
				for(Bitmap bitmap : bitmapCache){
					var thisDist=Math.abs(bitmap.pixelHeight-pixelHeight);
					if(thisDist<0.5) break gen;
				}
			}finally{
				r.unlock();
			}
			
			generating=true;
			async(()->{
				try{
					Bitmap bitmap=new Bitmap(pixelHeight);
					
					try{
						r.lock();
						if(bitmapCache.size()>7){
							IntStream.range(0, bitmapCache.size())
							         .boxed()
							         .min(Comparator.comparingLong(i->bitmapCache.get(i).lastUsed))
							         .ifPresent(i->bitmapCache.remove((int)i).free());
						}
						bitmap.lastUsed=System.currentTimeMillis();
					}finally{
						r.unlock();
					}
					var w=cacheLock.writeLock();
					try{
						w.lock();
						bitmapCache.add(bitmap);
					}finally{
						w.unlock();
					}
					renderRequest.run();
				}finally{
					generating=false;
				}
			});
		}
		
		best.lastUsed=System.currentTimeMillis();
		return best;
	}
	
	@Override
	public Bounds getStringBounds(String string, float pixelHeight){
		pushMax(string);
		
		float minX=0;
		float minY=Float.MAX_VALUE;
		float maxX=Float.MIN_VALUE;
		float maxY=Float.MIN_VALUE;
		
		Bitmap bitmap=getBitmap(pixelHeight);
		
		float scale=pixelHeight/bitmap.pixelHeight;
		
		try(MemoryStack stack=stackPush()){
			
			FloatBuffer x=stack.floats(0.0f);
			FloatBuffer y=stack.floats(0.0f);
			
			STBTTAlignedQuad q=STBTTAlignedQuad.malloc(stack);
			
			for(int i=0;i<string.length();i++){
				char cp=string.charAt(i);
				if(cp<=31) cp=' ';
				try{
					STBTruetype.stbtt_GetBakedQuad(bitmap.charInfo, bitmap.bitmapWidth, bitmap.bitmapHeight, cp-min, x, y, q, true);
					
					minX=Math.min(q.x0(), minX);
					minX=Math.min(q.x1(), minX);
					minY=Math.min(q.y0(), minY);
					minY=Math.min(q.y1(), minY);
					
					maxX=Math.max(q.x0(), maxX);
					maxX=Math.max(q.x1(), maxX);
					maxY=Math.max(q.y0(), maxY);
					maxY=Math.max(q.y1(), maxY);
				}catch(IllegalArgumentException e){
					LogUtil.printlnEr(e, cp);
				}
			}
		}
		return new Bounds((maxX-minX)*scale, (maxY-minY)*scale);
	}
	
	private void pushMax(String string){
		int maxRequested=0;
		for(int i=0;i<string.length();i++){
			int cp=string.charAt(i);
			if(cp>255) continue;
			if(cp<=31) continue;
			maxRequested=Math.max(maxRequested, cp+1);
		}
		if(max<maxRequested){
			max=maxRequested;
			
			var r=cacheLock.writeLock();
			try{
				r.lock();
				bitmapCache.forEach(Bitmap::free);
				bitmapCache.clear();
			}finally{
				r.unlock();
			}
		}
	}
	
	@Override
	public void outlineString(String string, float pixelHeight, float x, float y){
		drawString(string, pixelHeight, x, y, bitmap->bitmap.outlineTexture);
	}
	
	@Override
	public void fillString(String string, float pixelHeight, float x, float y){
		drawString(string, pixelHeight, x, y, bitmap->bitmap.texture);
	}
	
	private void drawString(String string, float pixelHeight, float xOff, float yOff, Function<Bitmap, Texture> getTex){
		pushMax(string);
		
		Bitmap bitmap=getBitmap(pixelHeight);
		var    tex   =getTex.apply(bitmap);
		if(tex==null){
			return;
		}
		float scale=pixelHeight/bitmap.pixelHeight;
		
		try(MemoryStack stack=stackPush()){
			
			FloatBuffer x=stack.floats(0.0f);
			FloatBuffer y=stack.floats(0.0f);
			
			STBTTAlignedQuad q=STBTTAlignedQuad.malloc(stack);
			tex.bind(GL11.GL_TEXTURE_2D);
			GL11.glEnable(GL11.GL_TEXTURE_2D);
			
			try(var apply=bulkHook.apply(BinaryDrawing.DrawMode.QUADS)){
				for(int i=0;i<string.length();i++){
					char cp=string.charAt(i);
					if(cp<=31) cp=' ';
					
					
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
			}
			
			GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
			GL11.glDisable(GL11.GL_TEXTURE_2D);
		}
	}
	
	@Override
	public boolean canFontDisplay(char c){
		if(c<min) return false;
		if(c>max) return false;
		int g=STBTruetype.stbtt_FindGlyphIndex(info, c);
		return g!=replacer;
	}
}
