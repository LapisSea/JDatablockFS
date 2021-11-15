package com.lapissea.cfs.tools;

import com.lapissea.cfs.tools.render.GlUtils;
import com.lapissea.cfs.tools.render.RenderBackend;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.roaringbitmap.RoaringBitmap;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.lapissea.util.PoolOwnThread.async;
import static org.lwjgl.opengl.GL20.*;

public class AtlasFont extends DrawFont{
	
	private static ByteBuffer convertImageRGB(BufferedImage image){
		
		int[] pixels=new int[image.getWidth()*image.getHeight()];
		image.getRGB(0, 0, image.getWidth(), image.getHeight(), pixels, 0, image.getWidth());
		
		ByteBuffer buffer=BufferUtils.createByteBuffer(image.getWidth()*image.getHeight()*3);
		
		for(int y=0;y<image.getHeight();y++){
			for(int x=0;x<image.getWidth();x++){
				int pixel=pixels[y*image.getWidth()+x];
				buffer.put((byte)((pixel >> 16)&0xFF)); // Red component
				buffer.put((byte)((pixel >> 8)&0xFF)); // Green component
				buffer.put((byte)(pixel&0xFF)); // Blue component
			}
		}
		
		buffer.flip();
		return buffer;
	}
	private static ByteBuffer convertImageA(BufferedImage image){
		
		int[] pixels=new int[image.getWidth()*image.getHeight()];
		image.getRGB(0, 0, image.getWidth(), image.getHeight(), pixels, 0, image.getWidth());
		
		ByteBuffer buffer=BufferUtils.createByteBuffer(image.getWidth()*image.getHeight());
		
		for(int y=0;y<image.getHeight();y++){
			for(int x=0;x<image.getWidth();x++){
				int pixel=pixels[y*image.getWidth()+x];
				buffer.put((byte)(pixel&0xFF));
			}
		}
		
		buffer.flip();
		return buffer;
	}
	
	private final MSDFAtlas atlas;
	
	private final RenderBackend renderer;
	private final boolean       monospace;
	
	private int             program;
	private int             drawSizeU;
	private int             outlineU;
	private GlUtils.Texture texture;
	
	private final float[] advanceCache;
	
	private final RoaringBitmap isPresentCache=new RoaringBitmap();
	private       int           isPresentCacheSize;
	
	public AtlasFont(MSDFAtlas atlas, RenderBackend renderer, Runnable renderRequest, Consumer<Runnable> openglTask){
		this.atlas=atlas;
		this.renderer=renderer;
		
		
		var advanceCache=new float[256];
		for(int i=0;i<advanceCache.length;i++){
			advanceCache[i]=atlas.getGlyph(i).getAdvance();
		}
		var stats=IntStream.range(0, advanceCache.length).mapToDouble(i->advanceCache[i]).summaryStatistics();
		monospace=stats.getMax()-stats.getMin()<0.0001;
		if(monospace) this.advanceCache=new float[]{(float)stats.getMax()};
		else{
			this.advanceCache=advanceCache;
		}
		
		if(!isMask()){
			var atlasInfo=atlas.getInfo().getAtlas();
			var frag="""
				#version 130
				
				uniform sampler2D msdf;
				uniform float drawSize;
				uniform bool outline;
				uniform vec4 color;
				
				float median(float r, float g, float b) {
					return max(min(r, g), min(max(r, g), b));
				}
				float screenPxRange(){
					return (drawSize/$size)*$distanceRange;
				}
				float sample(vec2 uv){
					vec3 msd = texture(msdf, uv).rgb;
					float sd = median(msd.r, msd.g, msd.b);
					if(outline){
						float mid=0.5;
						if(sd>mid){
							float off=sd-mid;
							sd=mid-off;
						}
						sd+=6/drawSize;
					}
					float screenPxDistance = screenPxRange()*(sd - 0.5);
					float dist=screenPxDistance + 0.5;
					float opacity = clamp(dist, 0.0, 1.0);
					return opacity;
				}
				void main()
				{
					float opacity=sample(gl_TexCoord[0].xy);
					if(opacity<1.0/256)discard;
					gl_FragColor = vec4(gl_Color.rgb, gl_Color.a*opacity);
				}
				
				"""
				.replace("$texelSizeX", (1F/atlasInfo.getWidth())+"")
				.replace("$texelSizeY", (1F/atlasInfo.getHeight())+"")
				.replace("$distanceRange", atlasInfo.getDistanceRange()+"")
				.replace("$size", atlasInfo.getSize()+"");
			
			openglTask.accept(()->{
				program=GlUtils.makeShaderProgram(
					GlUtils.compileShader(GL_FRAGMENT_SHADER, frag)
				);
				drawSizeU=glGetUniformLocation(program, "drawSize");
				outlineU=glGetUniformLocation(program, "outline");
			});
		}
		async(()->{
			var img=atlas.getImage();
			if(isMask()){
				ByteBuffer bb=convertImageA(img);
				openglTask.accept(()->texture=GlUtils.uploadTexture(img.getWidth(), img.getHeight(), GL11.GL_ALPHA, bb));
			}else{
				ByteBuffer bb=convertImageRGB(img);
				openglTask.accept(()->texture=GlUtils.uploadTexture(img.getWidth(), img.getHeight(), GL11.GL_RGB, bb));
			}
			renderRequest.run();
		});
	}
	private boolean isMask(){
		return atlas.getInfo().getAtlas().getType().contains("mask");
	}
	
	@Override
	public void fillStrings(List<StringDraw> strings){
		drawString(strings, false);
	}
	@Override
	public void outlineStrings(List<StringDraw> strings){
		drawString(strings, true);
	}
	
	private void drawString(List<StringDraw> strings, boolean outline){
		if(texture==null) return;
		if(!isMask()){
			if(program==0) return;
		}
		if(strings.isEmpty()) return;
		
		var   metrics=atlas.getInfo().getMetrics();
		float fsScale=(float)(1/(metrics.getAscender()-metrics.getDescender()));
		
		var minSpace=outline?20:5;
		
		texture.bind(GL_TEXTURE_2D);
		glEnable(GL_TEXTURE_2D);
		
		if(!isMask()){
			glUseProgram(program);
			glUniform1i(outlineU, outline?1:0);
		}
		
		Map<Integer, List<StringDraw>> sizeGropus=strings.stream().collect(Collectors.groupingBy(draw->(int)(draw.pixelHeight()*100)));
		for(var draws : sizeGropus.values()){
			var scale=(draws.get(0).pixelHeight()*fsScale);
			
			
			if(!isMask()){
				glUniform1f(drawSizeU, scale);
			}
			
			if(scale<minSpace){
				continue;
			}
			
			float alphaMul=Math.min(1, (scale-minSpace)/3);
			
			try(var ignored=renderer.bulkDraw(RenderBackend.DrawMode.QUADS)){
				for(StringDraw draw : draws){
					var   col=draw.color();
					float r  =col.getRed()/255F, g=col.getGreen()/255F, b=col.getBlue()/255F, a=(col.getAlpha()/255F)*alphaMul;
					
					String string=draw.string();
					float  xScale=draw.xScale();
					float  xOff  =draw.x();
					float  yOff  =draw.y();
					
					float x=0, y=(float)metrics.getDescender();
					
					for(int i=0;i<string.length();i++){
						char c=string.charAt(i);
						
						var glyph=getBakedGlyph(c);
						
						if(glyph.empty){
							x+=glyph.advance;
							continue;
						}
						
						float
							x0=(glyph.x0+x)*scale*xScale+xOff,
							x1=(glyph.x1+x)*scale*xScale+xOff,
							y0=((-glyph.y0)+y)*scale+yOff,
							y1=((-glyph.y1)+y)*scale+yOff;
						
						doVert(x0, y0, glyph.u0, glyph.v0, r, g, b, a);
						doVert(x1, y0, glyph.u1, glyph.v0, r, g, b, a);
						doVert(x1, y1, glyph.u1, glyph.v1, r, g, b, a);
						doVert(x0, y1, glyph.u0, glyph.v1, r, g, b, a);
						
						x+=glyph.advance;
					}
				}
			}
		}
		
		glBindTexture(GL_TEXTURE_2D, 0);
		glDisable(GL_TEXTURE_2D);
		glUseProgram(0);
	}
	
	private static record BakedGlyph(
		boolean empty,
		float advance,
		float x0, float x1, float y0, float y1,
		float u0, float u1, float v0, float v1
	){}
	
	private final BakedGlyph[] glyphCacheArray=new BakedGlyph[256];
	
	private BakedGlyph getBakedGlyph(char c){
		if(c<glyphCacheArray.length){
			var glyph=glyphCacheArray[c];
			if(glyph==null){
				glyphCacheArray[c]=glyph=bakeGlyph(c);
			}
			return glyph;
		}
		
		return bakeGlyph(c);
	}
	
	private BakedGlyph bakeGlyph(char c){
		int aw, ah;
		{
			var a=atlas.getInfo().getAtlas();
			aw=a.getWidth();
			ah=a.getHeight();
		}
		
		var glyph   =atlas.getGlyph(c);
		var bounds  =glyph.getPlaneBounds();
		var uvBounds=glyph.getAtlasBounds();
		
		if(bounds==null||uvBounds==null) return new BakedGlyph(true, glyph.getAdvance(), 0, 0, 0, 0, 0, 0, 0, 0);
		return new BakedGlyph(false,
		                      glyph.getAdvance(),
		                      bounds.getLeft(), bounds.getRight(), bounds.getBottom(), bounds.getTop(),
		                      uvBounds.getLeft()/aw, uvBounds.getRight()/aw, 1-(uvBounds.getBottom()/ah), 1-(uvBounds.getTop()/ah)
		);
	}
	
	private void doVert(float x, float y, float u, float v, float r, float g, float b, float a){
		GL11.glColor4f(r, g, b, a);
		GL11.glTexCoord2f(u, v);
		GL11.glVertex2f(x, y);
	}
	
	@Override
	public Bounds getStringBounds(String string){
		float width=calcWidth(string);
		var   s    =renderer.getFontScale();
		return new Bounds(width*s, s);
	}
	
	private float calcWidth(String string){
		if(monospace){
			return string.length()*advanceCache[0];
		}
		var width=0F;
		for(int i=0;i<string.length();i++){
			var c=string.charAt(i);
			if(c<advanceCache.length){
				width+=advanceCache[c];
			}else{
				width+=atlas.getGlyph(c).getAdvance();
			}
		}
		return width;
	}
	
	@Override
	public boolean canFontDisplay(char c){
		while(c>=isPresentCacheSize){
			if(atlas.getGlyphOptional((char)isPresentCacheSize).isPresent()){
				isPresentCache.add(isPresentCacheSize);
			}
			isPresentCacheSize++;
		}
		return isPresentCache.contains(c);
	}
}
