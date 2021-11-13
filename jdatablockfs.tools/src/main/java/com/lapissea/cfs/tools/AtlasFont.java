package com.lapissea.cfs.tools;

import com.lapissea.cfs.tools.render.GlUtils;
import com.lapissea.cfs.tools.render.RenderBackend;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.function.Consumer;

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
	
	private int             program;
	private int             drawSizeU;
	private int             outlineU;
	private GlUtils.Texture texture;
	
	public AtlasFont(MSDFAtlas atlas, RenderBackend renderer, Runnable renderRequest, Consumer<Runnable> openglTask){
		this.atlas=atlas;
		this.renderer=renderer;
		
		if(!isMask()){
			var atlasInfo=atlas.getInfo().getAtlas();
			var frag="""
				#version 130
				
				uniform sampler2D msdf;
				uniform float drawSize;
				uniform bool outline;
				
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
		for(StringDraw string : strings){
			fillString(string.color(), string.string(), string.pixelHeight(), string.x(), string.y());
		}
	}
	@Override
	public void outlineStrings(List<StringDraw> strings){
		for(StringDraw string : strings){
			outlineString(string.color(), string.string(), string.pixelHeight(), string.x(), string.y());
		}
	}
	public void fillString(Color color, String string, float pixelHeight, float x, float y){
		drawString(color, string, pixelHeight, x, y, false);
	}
	public void outlineString(Color color, String string, float pixelHeight, float x, float y){
		drawString(color, string, pixelHeight, x, y, true);
	}
	private void drawString(Color color, String string, float pixelHeight, float xOff, float yOff, boolean outline){
		
		if(texture==null) return;
		if(!isMask()){
			if(program==0) return;
		}
		
		glColor4f(color.getRed()/255F, color.getGreen()/255F, color.getBlue()/255F, color.getAlpha()/255F);
		
		var   metrics=atlas.getInfo().getMetrics();
		float fsScale=(float)(1/(metrics.getAscender()-metrics.getDescender()));
		var   scale  =(pixelHeight*fsScale);
		if(scale<(outline?20:5)){
			return;
		}
		
		float x=0, y=(float)metrics.getDescender();
		
		int aw, ah;
		{
			var a=atlas.getInfo().getAtlas();
			aw=a.getWidth();
			ah=a.getHeight();
		}
		
		if(!isMask()){
			glUseProgram(program);
			glUniform1f(drawSizeU, scale);
			glUniform1i(outlineU, outline?1:0);
		}
		texture.bind(GL_TEXTURE_2D);
		glEnable(GL_TEXTURE_2D);
		
		try(var ignored=renderer.bulkDraw(RenderBackend.DrawMode.QUADS)){
			for(int i=0;i<string.length();i++){
				char c    =string.charAt(i);
				var  glyph=atlas.getGlyph(c);
				
				var bounds  =glyph.getPlaneBounds();
				var uvBounds=glyph.getAtlasBounds();
				if(bounds==null||uvBounds==null){
					x+=glyph.getAdvance();
					continue;
				}
				
				
				float
					x0=(bounds.getLeft()+x)*scale+xOff,
					x1=(bounds.getRight()+x)*scale+xOff,
					y0=((-bounds.getBottom())+y)*scale+yOff,
					y1=((-bounds.getTop())+y)*scale+yOff;
				
				float
					u0=uvBounds.getLeft()/aw,
					u1=uvBounds.getRight()/aw,
					v0=uvBounds.getBottom()/ah,
					v1=uvBounds.getTop()/ah;
				
				v0=1-v0;
				v1=1-v1;
				
				GL11.glTexCoord2f(u0, v0);
				GL11.glVertex2f(x0, y0);
				
				GL11.glTexCoord2f(u1, v0);
				GL11.glVertex2f(x1, y0);
				
				GL11.glTexCoord2f(u1, v1);
				GL11.glVertex2f(x1, y1);
				
				GL11.glTexCoord2f(u0, v1);
				GL11.glVertex2f(x0, y1);
				
				x+=glyph.getAdvance();
			}
		}
		
		GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
		GL11.glDisable(GL11.GL_TEXTURE_2D);
		if(!isMask()) glUseProgram(0);
	}
	
	@Override
	public Bounds getStringBounds(String string){
		var width=string.chars().mapToDouble(c->atlas.getGlyph(c).getAdvance()).sum();
		return new Bounds(
			(float)(width*renderer.getFontScale()),
			(float)(atlas.getInfo().getMetrics().getLineHeight()*renderer.getFontScale())
		);
	}
	
	@Override
	public boolean canFontDisplay(char c){
		return atlas.getGlyphOptional(c).isPresent();
	}
}
